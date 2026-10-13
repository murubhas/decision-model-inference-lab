# Public Release Checklist

This directory is a standalone repository with an internal GitLab remote
(`origin`) and a private GitHub mirror (`github`). The private mirror preserves
the existing history for synchronization. Its source and checked-in evidence
are prepared for public review, but public publication requires the checks below.

The mirrored Git history contains an employee email in commit metadata.
The source-file audit does not inspect author or committer metadata. To preserve
the requested privacy boundary, create the public repository from a reviewed
snapshot with a public or GitHub noreply identity. Keep the internal repository
and its history intact. Review the history before making the GitHub mirror
public. The private push does not rewrite existing commits or change visibility.

## Latest Local Review

The 2026-10-12 review passed the project identity and credential audit.
Gitleaks 8.30.1 found no secrets in the 205-file proposed public tree or the
existing one-commit Git history. This checks content, not the employee identity
in Git commit metadata described above.

The HTML deck has ten timed slides totaling 900 seconds and seven untimed
appendices. All 17 slides passed desktop layout checks at 1280x720, 1440x900,
and 1920x1080 in light and dark themes. The new inference-choices appendix also
passed mobile reading checks. The deck made no external requests and reported
no browser errors.

Public release still requires a license and a review of author identity and
history. The README now uses the chosen GitHub clone URL. Credential rotation remains deferred
at the project owner's request. Re-run the scans after any release changes.

## Before Public Publication

1. Prepare a separate public checkout from the reviewed tracked files plus any
   reviewed pending changes. Exclude `.git` and all ignored files. Initialize a
   fresh Git repository in that checkout and configure the intended public
   author identity before committing. Do not reinitialize or rewrite the
   internal repository.

2. Run the automated identity and credential scan:

   ```shell
   ./scripts/publication-audit.sh
   ```

3. Inspect ignored files. `.env`, `.publication-denylist`, `results/`, build
   output, IDE state, and private deployment records must remain ignored.

   ```shell
   git status --short --ignored
   ```

4. Inspect the exact staged set before committing and pushing:

   ```shell
   git add --dry-run .
   git add .
   git diff --cached --check
   git diff --cached --stat
   ```

5. Run a dedicated secret scanner such as Gitleaks or TruffleHog against the
   proposed public tree and any history being published. The local audit catches
   this project's known identity and cloud patterns, but it does not replace a
   maintained secret-signature database.

6. Rotate any runtime credential that has left its approved secret manager,
   then recreate deployment Secrets from the new values. Never copy the local
   `.env` or a rendered Kubernetes Secret into the public repository.

7. Choose and add a `LICENSE` before publishing. A public GitHub repository
   without a license does not grant reuse rights.

8. Verify the documented GitHub clone URL, the deck, documentation links, and
   offline quick start from the public checkout before publishing it.

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
