#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "boto3>=1.42.77,<2",
# ]
# ///
"""Build and publish the decision-model experiment images through AWS CodeBuild."""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import re
import tempfile
import time
import zipfile
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

import boto3
from botocore.exceptions import ClientError


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REGION = "us-east-2"
AGENT_REPOSITORY = "decision-inference-agent"
BENCHMARK_REPOSITORY = "decision-inference-benchmark"
DECIDER_REPOSITORY = "strands-decider"
SOURCE_DIRS = (
    ".mvn",
    "a2a-agent",
    "benchmark-runner",
    "guardrail-contract",
    "guardrail-policy",
    "guardrail-runtime",
    "provider-jev",
    "provider-llm",
    "provider-strands",
    "deploy/decider",
)
ROOT_FILES = (".dockerignore", "mvnw", "mvnw.cmd", "pom.xml")
EXCLUDED_PARTS = {"target", ".quarkus", ".git", ".idea", ".vscode", "results", "__pycache__"}
TERMINAL_BUILD_STATUSES = {"SUCCEEDED", "FAILED", "FAULT", "STOPPED", "TIMED_OUT"}
IMAGE_TAG_PATTERN = re.compile(r"[A-Za-z0-9_][A-Za-z0-9._-]{0,127}")

logging.basicConfig(
    format="%(asctime)s %(levelname)s %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
    level=logging.INFO,
)
LOG = logging.getLogger("decision-inference-codebuild")


@dataclass(frozen=True)
class Settings:
    account_id: str
    region: str
    project_name: str
    role_name: str
    source_bucket: str
    source_prefix: str
    environment_image: str
    compute_type: str

    @property
    def role_arn(self) -> str:
        return f"arn:aws:iam::{self.account_id}:role/{self.role_name}"

    @property
    def registry(self) -> str:
        return f"{self.account_id}.dkr.ecr.{self.region}.amazonaws.com"


def is_excluded(relative_path: Path) -> bool:
    name = relative_path.name
    return (
        any(part in EXCLUDED_PARTS for part in relative_path.parts)
        or name == ".env"
        or name.startswith(".env.")
        or name.endswith(".env")
        or name.endswith((".pyc", ".pyo"))
    )


def source_files() -> list[tuple[Path, Path]]:
    candidates: list[Path] = [PROJECT_ROOT / name for name in ROOT_FILES]
    candidates.extend(
        (
            PROJECT_ROOT / "datasets" / "guardrail-corpus.v1.jsonl",
            PROJECT_ROOT / "datasets" / "provider-pricing.v1.json",
            PROJECT_ROOT / "deploy" / "codebuild" / "buildspec.yml",
        )
    )
    for directory in SOURCE_DIRS:
        candidates.extend(path for path in (PROJECT_ROOT / directory).rglob("*") if path.is_file())

    selected: dict[str, tuple[Path, Path]] = {}
    for path in candidates:
        if not path.exists():
            raise FileNotFoundError(f"Required CodeBuild source is missing: {path}")
        relative_path = path.relative_to(PROJECT_ROOT)
        if is_excluded(relative_path):
            continue
        if path.is_symlink():
            raise ValueError(f"CodeBuild source must not contain symlinks: {relative_path}")
        selected[relative_path.as_posix()] = (path, relative_path)
    return [selected[key] for key in sorted(selected)]


def source_sha256(files: list[tuple[Path, Path]]) -> str:
    digest = hashlib.sha256()
    for path, relative_path in files:
        encoded_path = relative_path.as_posix().encode("utf-8")
        digest.update(len(encoded_path).to_bytes(4, "big"))
        digest.update(encoded_path)
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()


def package_source(files: list[tuple[Path, Path]], destination: Path) -> None:
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path, relative_path in files:
            archive.write(path, relative_path.as_posix())
    LOG.info("Packaged %d source files (%.2f MiB)", len(files), destination.stat().st_size / (1 << 20))


def ensure_source_bucket(session: boto3.Session, settings: Settings) -> None:
    s3 = session.client("s3", region_name=settings.region)
    created = False
    try:
        s3.head_bucket(Bucket=settings.source_bucket)
        LOG.info("Source bucket already exists: %s", settings.source_bucket)
    except ClientError as exc:
        status = exc.response.get("ResponseMetadata", {}).get("HTTPStatusCode")
        code = exc.response.get("Error", {}).get("Code")
        if status != 404 and code not in {"404", "NoSuchBucket", "NotFound"}:
            raise
        request: dict[str, object] = {"Bucket": settings.source_bucket}
        if settings.region != "us-east-1":
            request["CreateBucketConfiguration"] = {"LocationConstraint": settings.region}
        s3.create_bucket(**request)
        created = True
        LOG.info("Created source bucket: %s", settings.source_bucket)

    if created:
        s3.put_public_access_block(
            Bucket=settings.source_bucket,
            PublicAccessBlockConfiguration={
                "BlockPublicAcls": True,
                "IgnorePublicAcls": True,
                "BlockPublicPolicy": True,
                "RestrictPublicBuckets": True,
            },
        )
        s3.put_bucket_encryption(
            Bucket=settings.source_bucket,
            ServerSideEncryptionConfiguration={
                "Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]
            },
        )
        s3.put_bucket_versioning(Bucket=settings.source_bucket, VersioningConfiguration={"Status": "Enabled"})


def ensure_ecr_repository(session: boto3.Session, settings: Settings, name: str) -> None:
    ecr = session.client("ecr", region_name=settings.region)
    try:
        ecr.describe_repositories(repositoryNames=[name])
        LOG.info("ECR repository already exists: %s", name)
        ecr.put_image_tag_mutability(repositoryName=name, imageTagMutability="IMMUTABLE")
        ecr.put_image_scanning_configuration(
            repositoryName=name,
            imageScanningConfiguration={"scanOnPush": True},
        )
    except ecr.exceptions.RepositoryNotFoundException:
        ecr.create_repository(
            repositoryName=name,
            imageTagMutability="IMMUTABLE",
            imageScanningConfiguration={"scanOnPush": True},
            encryptionConfiguration={"encryptionType": "AES256"},
        )
        LOG.info("Created ECR repository: %s", name)


def ensure_service_role(session: boto3.Session, settings: Settings) -> None:
    iam = session.client("iam")
    trust_policy = {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Principal": {"Service": "codebuild.amazonaws.com"},
                "Action": "sts:AssumeRole",
            }
        ],
    }
    try:
        iam.get_role(RoleName=settings.role_name)
        iam.update_assume_role_policy(
            RoleName=settings.role_name,
            PolicyDocument=json.dumps(trust_policy),
        )
        LOG.info("CodeBuild service role already exists: %s", settings.role_name)
    except iam.exceptions.NoSuchEntityException:
        iam.create_role(
            RoleName=settings.role_name,
            AssumeRolePolicyDocument=json.dumps(trust_policy),
            Description="Builds and publishes the decision-model guardrail experiment images",
        )
        LOG.info("Created CodeBuild service role: %s", settings.role_name)

    repository_arns = [
        f"arn:aws:ecr:{settings.region}:{settings.account_id}:repository/{AGENT_REPOSITORY}",
        f"arn:aws:ecr:{settings.region}:{settings.account_id}:repository/{BENCHMARK_REPOSITORY}",
        f"arn:aws:ecr:{settings.region}:{settings.account_id}:repository/{DECIDER_REPOSITORY}",
    ]
    bucket_arn = f"arn:aws:s3:::{settings.source_bucket}"
    log_group_arn = (
        f"arn:aws:logs:{settings.region}:{settings.account_id}:log-group:/aws/codebuild/{settings.project_name}"
    )
    permissions = {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Sid": "CreateCodeBuildLogGroup",
                "Effect": "Allow",
                "Action": "logs:CreateLogGroup",
                "Resource": "*",
            },
            {
                "Sid": "WriteCodeBuildLogs",
                "Effect": "Allow",
                "Action": ["logs:CreateLogStream", "logs:PutLogEvents"],
                "Resource": f"{log_group_arn}:*",
            },
            {
                "Sid": "ReadSourceBucketMetadata",
                "Effect": "Allow",
                "Action": ["s3:GetBucketAcl", "s3:GetBucketLocation", "s3:ListBucket"],
                "Resource": bucket_arn,
            },
            {
                "Sid": "ReadSourceBundles",
                "Effect": "Allow",
                "Action": ["s3:GetObject", "s3:GetObjectVersion"],
                "Resource": f"{bucket_arn}/{settings.source_prefix}/*",
            },
            {
                "Sid": "AuthenticateToEcr",
                "Effect": "Allow",
                "Action": "ecr:GetAuthorizationToken",
                "Resource": "*",
            },
            {
                "Sid": "PublishImages",
                "Effect": "Allow",
                "Action": [
                    "ecr:BatchCheckLayerAvailability",
                    "ecr:BatchGetImage",
                    "ecr:CompleteLayerUpload",
                    "ecr:DescribeImages",
                    "ecr:GetDownloadUrlForLayer",
                    "ecr:InitiateLayerUpload",
                    "ecr:PutImage",
                    "ecr:UploadLayerPart",
                ],
                "Resource": repository_arns,
            },
        ],
    }
    iam.put_role_policy(
        RoleName=settings.role_name,
        PolicyName="decision-inference-image-publish",
        PolicyDocument=json.dumps(permissions),
    )
    LOG.info("Updated the least-privilege CodeBuild service policy")


def project_payload(settings: Settings, source_location: str) -> dict[str, object]:
    return {
        "name": settings.project_name,
        "description": "Tests, builds, and publishes the guardrail agent, benchmark, and Decider images",
        "serviceRole": settings.role_arn,
        "timeoutInMinutes": 120,
        "queuedTimeoutInMinutes": 60,
        "concurrentBuildLimit": 1,
        "source": {
            "type": "S3",
            "location": source_location,
            "buildspec": "deploy/codebuild/buildspec.yml",
        },
        "artifacts": {"type": "NO_ARTIFACTS"},
        "cache": {
            "type": "LOCAL",
            "modes": ["LOCAL_DOCKER_LAYER_CACHE", "LOCAL_CUSTOM_CACHE"],
        },
        "environment": {
            "type": "LINUX_CONTAINER",
            "image": settings.environment_image,
            "computeType": settings.compute_type,
            "privilegedMode": True,
            "imagePullCredentialsType": "CODEBUILD",
            "environmentVariables": [
                {"name": "ACCOUNT_ID", "value": settings.account_id, "type": "PLAINTEXT"},
                {"name": "AGENT_REPOSITORY", "value": AGENT_REPOSITORY, "type": "PLAINTEXT"},
                {"name": "BENCHMARK_REPOSITORY", "value": BENCHMARK_REPOSITORY, "type": "PLAINTEXT"},
                {"name": "DECIDER_REPOSITORY", "value": DECIDER_REPOSITORY, "type": "PLAINTEXT"},
                {"name": "INCLUDE_DECIDER", "value": "false", "type": "PLAINTEXT"},
                {"name": "DOCKER_BUILDKIT", "value": "1", "type": "PLAINTEXT"},
            ],
        },
        "logsConfig": {
            "cloudWatchLogs": {
                "status": "ENABLED",
                "groupName": f"/aws/codebuild/{settings.project_name}",
                "streamName": "image-build",
            }
        },
    }


def ensure_codebuild_project(session: boto3.Session, settings: Settings, source_location: str) -> None:
    codebuild = session.client("codebuild", region_name=settings.region)
    payload = project_payload(settings, source_location)
    exists = bool(codebuild.batch_get_projects(names=[settings.project_name])["projects"])
    for attempt in range(12):
        try:
            if exists:
                codebuild.update_project(**payload)
                LOG.info("Updated CodeBuild project: %s", settings.project_name)
            else:
                codebuild.create_project(**payload)
                LOG.info("Created CodeBuild project: %s", settings.project_name)
            return
        except ClientError as exc:
            message = exc.response.get("Error", {}).get("Message", "")
            role_delay = exc.response.get("Error", {}).get("Code") == "InvalidInputException" and "AssumeRole" in message
            if not role_delay or attempt == 11:
                raise
            time.sleep(5)


def upload_source(session: boto3.Session, settings: Settings, source_key: str, bundle: Path) -> None:
    LOG.info("Uploading source to s3://%s/%s", settings.source_bucket, source_key)
    session.client("s3", region_name=settings.region).upload_file(str(bundle), settings.source_bucket, source_key)


def start_build(
    session: boto3.Session,
    settings: Settings,
    source_location: str,
    image_tag: str,
    digest: str,
    include_decider: bool,
) -> dict[str, object]:
    overrides = [
        {"name": "ACCOUNT_ID", "value": settings.account_id, "type": "PLAINTEXT"},
        {"name": "AGENT_REPOSITORY", "value": AGENT_REPOSITORY, "type": "PLAINTEXT"},
        {"name": "BENCHMARK_REPOSITORY", "value": BENCHMARK_REPOSITORY, "type": "PLAINTEXT"},
        {"name": "DECIDER_REPOSITORY", "value": DECIDER_REPOSITORY, "type": "PLAINTEXT"},
        {"name": "INCLUDE_DECIDER", "value": str(include_decider).lower(), "type": "PLAINTEXT"},
        {"name": "DOCKER_BUILDKIT", "value": "1", "type": "PLAINTEXT"},
        {"name": "IMAGE_TAG", "value": image_tag, "type": "PLAINTEXT"},
        {"name": "SOURCE_SHA256", "value": digest, "type": "PLAINTEXT"},
    ]
    response = session.client("codebuild", region_name=settings.region).start_build(
        projectName=settings.project_name,
        sourceTypeOverride="S3",
        sourceLocationOverride=source_location,
        environmentVariablesOverride=overrides,
    )
    return response["build"]


def wait_for_build(session: boto3.Session, settings: Settings, build_id: str) -> dict[str, object]:
    codebuild = session.client("codebuild", region_name=settings.region)
    last_state: tuple[str, str] | None = None
    while True:
        build = codebuild.batch_get_builds(ids=[build_id])["builds"][0]
        state = (build["buildStatus"], build.get("currentPhase", "UNKNOWN"))
        if state != last_state:
            LOG.info("CodeBuild status=%s phase=%s", *state)
            last_state = state
        if state[0] in TERMINAL_BUILD_STATUSES:
            return build
        time.sleep(15)


def image_reference(session: boto3.Session, settings: Settings, repository: str, tag: str) -> str:
    response = session.client("ecr", region_name=settings.region).describe_images(
        repositoryName=repository,
        imageIds=[{"imageTag": tag}],
    )
    digest = response["imageDetails"][0]["imageDigest"]
    return f"{settings.registry}/{repository}@{digest}"


def console_url(settings: Settings, build_id: str) -> str:
    return (
        "https://console.aws.amazon.com/codesuite/codebuild/"
        f"{settings.account_id}/projects/{settings.project_name}/build/{build_id}?region={settings.region}"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", help="Optional local AWS profile")
    parser.add_argument(
        "--account-id",
        help="Optional expected AWS account ID; defaults to the STS caller account",
    )
    parser.add_argument("--region", default=DEFAULT_REGION)
    parser.add_argument("--project-name", default="decision-inference-images")
    parser.add_argument("--role-name", default="decision-inference-codebuild")
    parser.add_argument("--source-bucket")
    parser.add_argument("--source-prefix", default="decision-inference-images/codebuild")
    parser.add_argument("--environment-image", default="aws/codebuild/amazonlinux-x86_64-standard:6.0")
    parser.add_argument("--compute-type", default="BUILD_GENERAL1_MEDIUM")
    parser.add_argument("--tag", help="Immutable ECR tag; defaults to a source digest plus UTC timestamp")
    parser.add_argument("--ensure-only", action="store_true", help="Provision resources without uploading or building")
    parser.add_argument(
        "--include-decider",
        action="store_true",
        help="Also build the pinned CUDA Strands Decider image and publish its model weights",
    )
    parser.add_argument("--wait", action="store_true", help="Wait for completion and print immutable image references")
    parser.add_argument("--dry-run", action="store_true", help="Validate and describe the plan without AWS calls")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    session = None
    if args.dry_run:
        account_id = args.account_id or "dry-run-account"
    else:
        session = boto3.Session(profile_name=args.profile, region_name=args.region)
        caller_account = session.client("sts").get_caller_identity()["Account"]
        if args.account_id and caller_account != args.account_id:
            raise SystemExit(f"Refusing account {caller_account}; expected {args.account_id}")
        account_id = args.account_id or caller_account

    settings = Settings(
        account_id=account_id,
        region=args.region,
        project_name=args.project_name,
        role_name=args.role_name,
        source_bucket=args.source_bucket or f"decision-inference-codebuild-{account_id}-{args.region}",
        source_prefix=args.source_prefix.strip("/"),
        environment_image=args.environment_image,
        compute_type=args.compute_type,
    )
    files = source_files()
    digest = source_sha256(files)
    timestamp = datetime.now(UTC).strftime("%Y%m%d%H%M%S")
    image_tag = args.tag or f"src-{digest[:12]}-{timestamp}"
    if not IMAGE_TAG_PATTERN.fullmatch(image_tag):
        raise SystemExit(f"Invalid ECR image tag: {image_tag!r}")
    source_key = f"{settings.source_prefix}/source/{image_tag}.zip"
    source_location = f"{settings.source_bucket}/{source_key}"

    LOG.info("CodeBuild project: %s", settings.project_name)
    LOG.info("AWS target: account=%s region=%s", settings.account_id, settings.region)
    LOG.info("Managed image: %s with Corretto 25 selected by buildspec", settings.environment_image)
    LOG.info("Source: %d allowlisted files, sha256=%s", len(files), digest)
    LOG.info("Source object: s3://%s", source_location)
    LOG.info("Image tag: %s", image_tag)
    LOG.info("Include self-hosted Decider image: %s", args.include_decider)
    LOG.info("Runtime credentials included in source: no")

    if args.dry_run:
        LOG.info("Dry run complete; no AWS API was called")
        return

    assert session is not None

    LOG.info("Step 1/5: ensure the private source bucket")
    ensure_source_bucket(session, settings)
    LOG.info("Step 2/5: ensure immutable ECR repositories")
    ensure_ecr_repository(session, settings, AGENT_REPOSITORY)
    ensure_ecr_repository(session, settings, BENCHMARK_REPOSITORY)
    ensure_ecr_repository(session, settings, DECIDER_REPOSITORY)
    LOG.info("Step 3/5: ensure the CodeBuild service role")
    ensure_service_role(session, settings)
    LOG.info("Step 4/5: ensure the privileged CodeBuild project")
    ensure_codebuild_project(session, settings, source_location)

    if args.ensure_only:
        LOG.info("Provisioning complete; no source was uploaded and no build was started")
        return

    LOG.info("Step 5/5: package, upload, and start the build")
    with tempfile.TemporaryDirectory() as temp_dir:
        bundle = Path(temp_dir) / "decision-model-inference-lab-source.zip"
        package_source(files, bundle)
        upload_source(session, settings, source_key, bundle)
    build = start_build(
        session,
        settings,
        source_location,
        image_tag,
        digest,
        args.include_decider,
    )
    build_id = str(build["id"])
    LOG.info("Build id: %s", build_id)
    LOG.info("Console: %s", console_url(settings, build_id))

    if not args.wait:
        LOG.info("Build started asynchronously; rerun with --wait to block for digest output")
        return

    final_build = wait_for_build(session, settings, build_id)
    if final_build["buildStatus"] != "SUCCEEDED":
        raise SystemExit(f"CodeBuild finished with {final_build['buildStatus']}")
    result = {
        "sourceSha256": digest,
        "tag": image_tag,
        "agent": image_reference(session, settings, AGENT_REPOSITORY, image_tag),
        "benchmark": image_reference(session, settings, BENCHMARK_REPOSITORY, image_tag),
    }
    if args.include_decider:
        result["decider"] = image_reference(session, settings, DECIDER_REPOSITORY, image_tag)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
