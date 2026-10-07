# Public Release Checklist

This directory is designed to become a standalone public repository. It was
never committed in the parent repository, so create the public repository from
this directory instead of exporting the parent repository's history.

## Before The First Commit

1. Initialize a fresh repository in this directory. Do not import the parent
   repository's history:

   ```shell
   git init
   ```

2. Run the automated identity and credential scan:

   ```shell
   ./scripts/publication-audit.sh
   ```

3. Inspect ignored files. `.env`, `.publication-denylist`, `results/`, build
   output, IDE state, and private deployment records must remain ignored.

   ```shell
   git status --short --ignored
   ```

4. Inspect the exact staged set before the first commit:

   ```shell
   git add --dry-run .
   git add .
   git diff --cached --check
   git diff --cached --stat
   ```

5. Run a dedicated secret scanner such as Gitleaks or TruffleHog against the
   staged tree. The local audit catches this project's known identity and cloud
   patterns, but it does not replace a maintained secret-signature database.

6. Rotate any runtime credential that has left its approved secret manager,
   then recreate deployment Secrets from the new values. Never copy the local
   `.env` or a rendered Kubernetes Secret into the public repository.

7. Choose and add a `LICENSE` before publishing. A public GitHub repository
   without a license does not grant reuse rights.

## Intentionally Public Evidence

- The controlled benchmark corpus contains synthetic and curated
  accelerated-compute prompts, tool arguments, evidence, and draft labels.
- Checked-in result artifacts contain benchmark inputs, model outputs,
  probabilities, latency, token counts, and cost calculations.
- Historical evidence retains immutable image digests and the Java exception
  type recorded by the measured build. It omits the private registry identity.
- Provider and model names are intentional because comparing them is the purpose
  of the experiment.

## Intentionally Private State

- API keys, OIDC client secrets, and local AWS profiles.
- Account IDs, employee-hosted domains, VPC endpoint IDs, subnet and security
  group IDs, instance IDs, role principals, cluster names, and raw cloud build
  records.
- Raw benchmark directories and environment-specific rendered manifests.

The deployment files under `deploy/` are templates. Render their `${NAME}`
placeholders outside the repository and review the result before applying it.
