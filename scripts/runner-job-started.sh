#!/bin/bash
# A self-hosted runner's job-started hook (docs/startup-gate.md, "A self-hosted Mac"): it runs on the runner before
# every job, outside anything a pull request can change, and a non-zero exit fails the job before any of its steps.
# The repository is public, and a fork's pull request may rewrite .github/workflows itself, so the `runs-on` condition
# in ci.yml is not what keeps forks off the Mac: this is. It lets through only ci.yml's startup-gate job of this
# repository, for a push, a manual run, or a pull request whose head branch is in this repository.
#
# Install: copy it outside the runner's work folder (the job must not be able to rewrite it), e.g. to
# ~/actions-runner-hooks/job-started.sh, then add to the runner's .env and restart the runner (./svc.sh stop; start):
#   ACTIONS_RUNNER_HOOK_JOB_STARTED=/Users/RUNNER_USER/actions-runner-hooks/job-started.sh
set -u

refuse() { echo "self-hosted runner hook: refusing this job: $*" >&2; exit 1; }

[ -n "${GITHUB_REPOSITORY:-}" ] || refuse "GITHUB_REPOSITORY is not set"
[ -r "${GITHUB_EVENT_PATH:-}" ] || refuse "no event payload (GITHUB_EVENT_PATH)"
[ "${GITHUB_JOB:-}" = startup-gate ] || refuse "job '${GITHUB_JOB:-}' is not startup-gate"
case "${GITHUB_WORKFLOW_REF:-}" in
  "$GITHUB_REPOSITORY/.github/workflows/ci.yml@"*) ;;
  *) refuse "workflow '${GITHUB_WORKFLOW_REF:-}' is not this repository's ci.yml" ;;
esac

# The event payload is GitHub's, not the workflow's: a fork cannot change who the head repository is.
/usr/bin/python3 -I - "$GITHUB_EVENT_PATH" "$GITHUB_REPOSITORY" "${GITHUB_EVENT_NAME:-}" <<'EOF' || exit 1
import json, sys
path, repo, event = sys.argv[1:]
payload = json.load(open(path))
def refuse(why):
    print(f"self-hosted runner hook: refusing this job: {why}", file=sys.stderr)
    sys.exit(1)
if payload.get("repository", {}).get("full_name") != repo:
    refuse("the event is not this repository's")
if event in ("push", "workflow_dispatch"):
    sys.exit(0)
if event == "pull_request":
    head = ((payload.get("pull_request") or {}).get("head") or {}).get("repo") or {}
    if head.get("full_name") == repo:
        sys.exit(0)
    refuse(f"the pull request's head is in {head.get('full_name') or 'a deleted repository'}, not {repo}")
refuse(f"event '{event}' is not push, workflow_dispatch or pull_request")
EOF
echo "self-hosted runner hook: $GITHUB_EVENT_NAME of $GITHUB_REPOSITORY, startup-gate: allowed"
