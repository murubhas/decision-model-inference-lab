#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

scan_args=(
  --hidden
  --glob '!/.git/**'
  --glob '!/.idea/**'
  --glob '!/**/target/**'
  --glob '!/results/**'
  --glob '!/scripts/publication-audit.sh'
  --glob '!/.env'
  --glob '!/.env.*'
  --glob '!/**/*.png'
  --glob '!/**/*.pyc'
)

failed=0

check() {
  local label="$1"
  local pattern="$2"
  if rg -n -P "${scan_args[@]}" -- "$pattern" .; then
    printf 'publication audit failed: %s\n' "$label" >&2
    failed=1
  fi
}

check "email address" '(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?![A-Za-z0-9.-])'
check "local home path" '(?:/Users/|/home/)[^/[:space:]"<>]+'
check "AWS access-key identifier" '(?:AKIA|ASIA)[0-9A-Z]{16}'
check "TypeSafe API key" 'apikey_[A-Za-z0-9_]{20,}'
check "private AWS account identifier" '(?:arn:aws:[^:\s]+:[^:\s]*:|[.]dkr[.]ecr[.])\K[0-9]{12}'
check "AWS account field" '(?i:account(?:id)?)[^0-9\n]{0,12}[0-9]{12}'
check "concrete VPC endpoint identifier" 'vpce-[0-9a-f]{8,}'
check "concrete VPC identifier" '(?<![A-Za-z0-9-])vpc-[0-9a-f]{8,}(?![A-Za-z0-9-])'
check "concrete subnet identifier" 'subnet-[0-9a-f]{8,}'
check "concrete security-group identifier" '(?<![A-Za-z0-9-])sg-[0-9a-f]{8,}(?![A-Za-z0-9-])'
check "concrete EC2 instance identifier" '(?<![A-Za-z0-9-])i-[0-9a-f]{8,}(?![A-Za-z0-9-])'
check "private-key material" '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
check "employee-hosted domain" '[A-Za-z0-9.-]+[.]people[.]aws[.]dev'

public_repository_url=""
public_pages_url=""
repository_remote="$(git remote get-url github 2>/dev/null || git remote get-url origin 2>/dev/null || true)"
if [[ "$repository_remote" =~ ^git@github\.com(-[[:alnum:]_-]+)?:([[:alnum:]_.-]+/[[:alnum:]_.-]+)$ ]]; then
  repository_path="${BASH_REMATCH[2]}"
  repository_path="${repository_path%.git}"
  public_repository_url="https://github.com/$repository_path"
elif [[ "$repository_remote" =~ ^https://github\.com/[[:alnum:]_.-]+/[[:alnum:]_.-]+$ ]]; then
  public_repository_url="${repository_remote%.git}"
  repository_path="${public_repository_url#https://github.com/}"
fi
if [[ -n "$public_repository_url" ]]; then
  public_pages_url="https://${repository_path%%/*}.github.io/${repository_path#*/}"
fi

if [[ -f .publication-denylist ]]; then
  while IFS= read -r denied; do
    [[ -z "$denied" || "$denied" == \#* ]] && continue
    while IFS= read -r matched_line; do
      # Only the chosen repository and its Pages URL are approved public project identity.
      filtered_line="$matched_line"
      if [[ -n "$public_repository_url" ]]; then
        filtered_line="${matched_line//"$public_repository_url"/<public-repository>}"
        filtered_line="${filtered_line//"$public_pages_url"/<public-pages>}"
      fi
      if [[ "$filtered_line" == *"$denied"* ]]; then
        printf '%s\n' "$matched_line"
        printf 'publication audit failed: local denylist match\n' >&2
        failed=1
      fi
    done < <(rg -n -F "${scan_args[@]}" --glob '!/.publication-denylist' -- "$denied" . || true)
  done < .publication-denylist
fi

if [[ -e .env ]] && ! git check-ignore -q .env 2>/dev/null; then
  printf 'publication audit failed: .env is not ignored\n' >&2
  failed=1
fi

if (( failed != 0 )); then
  exit 1
fi

printf 'Publication audit passed: no configured identity or credential patterns found.\n'
