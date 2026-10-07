# CodeBuild Image Pipeline

`build_images.py` packages an allowlisted, secret-free source bundle, verifies
the Maven reactor on Corretto 25, builds JVM images, optionally builds the pinned
Strands Decider image, and publishes immutable ECR tags through CodeBuild.

The helper derives the AWS account from STS by default. No account ID, profile,
bucket, build ID, or account-specific registry reference is checked into the
deployment configuration. Published evidence may retain immutable image digests
without the private registry identity.

## Dry Run

From the repository root:

```shell
uv run deploy/codebuild/build_images.py --dry-run --include-decider
```

The dry run validates the source allowlist and computes its SHA-256 without
making an AWS API call.

## Provision And Build

Use a normal AWS credential source, optionally selected through `--profile`:

```shell
uv run deploy/codebuild/build_images.py \
  --profile replace-with-profile \
  --region us-east-2 \
  --include-decider \
  --wait
```

You can pass `--account-id` as an additional guard. When omitted, the script uses
the STS caller account; when supplied, a mismatch fails before resources change.

Defaults:

| Resource | Default |
| --- | --- |
| CodeBuild project | `decision-inference-images` |
| CodeBuild role | `decision-inference-codebuild` |
| Source bucket | `decision-inference-codebuild-<account>-<region>` |
| Agent ECR repository | `decision-inference-agent` |
| Benchmark ECR repository | `decision-inference-benchmark` |
| Decider ECR repository | `strands-decider` |

The final output contains digest-pinned `agent`, `benchmark`, and optional
`decider` image references. Supply those values as `AGENT_IMAGE`,
`BENCHMARK_IMAGE`, and `DECIDER_IMAGE` when rendering the EKS templates.

## Security Properties

- `.env`, result directories, IDE state, build output, and Python caches are
  excluded from the source archive.
- Runtime credentials are not CodeBuild inputs and are never baked into images.
- ECR repositories use immutable tags and scan on push.
- The generated service role is limited to its source prefix, logs, and named
  ECR repositories.
- The source bucket blocks public access and enables encryption and versioning.

Build and deployment records from a specific account are intentionally local
artifacts. Publish source hashes, image digests, and sanitized benchmark evidence
instead of raw cloud-operation records.
