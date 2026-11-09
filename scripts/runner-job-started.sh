#!/bin/bash
# A self-hosted runner's job-started hook (docs/startup-gate.md, "A self-hosted Mac"): it runs on the runner before
# every job's own steps, outside anything a pull request can change.
# The repository is public, and a fork's pull request may rewrite .github/workflows itself, so the `runs-on` condition
# in ci.yml is not what keeps forks off the Mac: this is. It lets through only ci.yml's startup-gate job of this
# repository, for a push, a manual run, or a pull request whose head branch is in this repository.
#
# Exiting non-zero is not enough: the runner runs the hook as a step of the job, and a failed step only fails the job,
# so a later step with `if: always()` or `failure()` (or an action's `pre-if`) still runs (actions/runner,
# JobExtension.cs and StepsRunner.cs). A refused job's Runner.Worker is killed instead, so nothing after the hook runs;
# the runner reports the job failed and takes the next one.
#
# Install before the runner first starts: copy it outside the runner's work folder (the job must not be able to rewrite
# it), e.g. to ~/actions-runner-hooks/job-started.sh, and add to the runner's .env (restart the runner after a change):
#   ACTIONS_RUNNER_HOOK_JOB_STARTED=/Users/RUNNER_USER/actions-runner-hooks/job-started.sh
set -u

refuse() {
  echo "self-hosted runner hook: refusing this job: $*" >&2
  local pid=$$ parent target=
  while parent=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ') && [ -n "$parent" ] && [ "$parent" -gt 1 ]; do
    case "$(ps -o comm= -p "$parent")" in *Runner.Worker) target=$parent; break ;; esac
    pid=$parent
  done
  # No Runner.Worker above the hook (run by hand, or a runner that names it otherwise): end the hook's parent.
  [ -n "$target" ] || target=$PPID
  echo "self-hosted runner hook: ending the job (killing process $target)" >&2
  kill -KILL "$target"
  sleep 5  # the worker is gone long before this
  exit 1
}

[ -n "${GITHUB_REPOSITORY:-}" ] || refuse "GITHUB_REPOSITORY is not set"
[ -r "${GITHUB_EVENT_PATH:-}" ] || refuse "no event payload (GITHUB_EVENT_PATH)"
[ "${GITHUB_JOB:-}" = startup-gate ] || refuse "job '${GITHUB_JOB:-}' is not startup-gate"
case "${GITHUB_WORKFLOW_REF:-}" in
  "$GITHUB_REPOSITORY/.github/workflows/ci.yml@"*) ;;
  *) refuse "workflow '${GITHUB_WORKFLOW_REF:-}' is not this repository's ci.yml" ;;
esac

# The event payload is GitHub's, not the workflow's: a fork cannot change who the head repository is.
/usr/bin/python3 -I - "$GITHUB_EVENT_PATH" "$GITHUB_REPOSITORY" "${GITHUB_EVENT_NAME:-}" <<'EOF' || refuse "(the reason is above)"
import json, sys
path, repo, event = sys.argv[1:]
payload = json.load(open(path))
def refuse(why):
    print(f"self-hosted runner hook: {why}", file=sys.stderr)
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
