#!/usr/bin/env python3
"""PostToolUse hook: after a Bash call that ran `gh pr create`, remind the session to run the pr-reviewer agent.

Hooks cannot start a subagent themselves, so this adds context to the conversation instead (AGENTS.md, "Git and pull
requests": every PR gets a review by the pr-reviewer agent, on Sonnet).
"""
import json
import re
import sys

try:
    event = json.load(sys.stdin)
except ValueError:
    sys.exit(0)

command = (event.get("tool_input") or {}).get("command") or ""
if not re.search(r"\bgh\s+pr\s+create\b", command):
    sys.exit(0)

response = event.get("tool_response")
text = json.dumps(response) if not isinstance(response, str) else response
urls = sorted(set(re.findall(r"https://github\.com/[\w.-]+/[\w.-]+/pull/\d+", text)))
if not urls:
    sys.exit(0)  # the create failed or printed no PR: nothing to review

print(json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "PostToolUse",
        "additionalContext": (
            "A pull request was just created: " + ", ".join(urls) + ". Per AGENTS.md (\"Git and pull requests\"), "
            "start the `pr-reviewer` agent (Agent tool, subagent_type \"pr-reviewer\", which runs on Sonnet) on it "
            "now, giving it the PR number and this worktree's path. When it reports, fix the confirmed findings on "
            "the PR's branch and push, and tell the user what it found and what was fixed or left (with reasons)."
        ),
    }
}))
