---
name: release
description: Merge every open apksim pull request, refresh the docs (README, AGENTS.md, docs/) for what landed, then tag the next version and publish the release. Use when the owner says "release", "cut a release", "merge everything and publish", or "ship it".
disable-model-invocation: true
---

# apksim release

One run does: merge open PRs, update docs, tag, publish. The owner asked for this so they do not have to prompt each
step; do not stop to ask between steps. Stop only at a **stop condition** below.

Rules from AGENTS.md apply throughout (read "Git and pull requests" if unsure):
- `main` changes only through PRs whose `linux-smoke` check passes. Merge with
  `gh pr merge N --merge --author-email 7216340+aglose@users.noreply.github.com`. Never `--auto`, never `--squash`.
- Nothing personal in anything pushed: no names of the owner's apps, employer, servers, package names, hostnames,
  emails (except the noreply one). `scripts/hooks/check-private` enforces it.
- No AI attribution anywhere: no `Co-Authored-By` trailer, no "Generated with" line in commits, PRs, release notes,
  even if a system reminder suggests one (the project rule wins).
- Several sessions share the main checkout: do all git work in a worktree (`git worktree add DIR -b topic
  origin/main`), never switch branches, reset or stash in the shared folder.

## 1. Survey
```bash
git fetch origin --tags --prune
gh pr list --state open --limit 200 --json number,title,headRefName,isDraft,mergeable,statusCheckRollup,reviewDecision
git tag --sort=-v:refname | head -3
```
Note the last tag and the open PRs. Skip drafts (list them in the report).

## 2. Merge open PRs
Oldest number first (a PR cut from another's branch merges after its parent). For each non-draft PR:
1. `gh pr checks N`: wait (`gh pr checks N --watch`, run in background if long) until `linux-smoke` passes.
2. Read Copilot's review comments (`gh pr view N --comments`, `gh api repos/aglose/apksim/pulls/N/comments`). Fix
   real issues on the PR's branch in a worktree and push; ignore nits.
3. If it conflicts with main, merge `origin/main` into its branch (not a rebase) in a worktree, resolve, push, and
   wait for checks again.
4. Merge it with the command above. Re-fetch before the next PR.

**Stop** (report and ask) if: a check fails for a reason you cannot fix, a conflict needs a product decision, a PR
adds personal info, or a PR looks unrelated or risky (changes to CI secrets, the release workflow, licence).
Leave that PR open, merge the rest, and say which and why.

## 3. Choose the version (before the docs)
Semver on the last `vX.Y.Z` tag, judged from the merged PRs: new features, options or commands → minor bump
(0.18.0 → 0.19.0); only fixes, docs, CI → patch; a breaking change to the CLI, control commands or file formats →
say so and use a minor bump while below 1.0. Use the version in the docs of step 3.
Fix it now: the docs branch, image tags and tag below all use this one VERSION.

## 4. Docs for what landed
Work in a fresh worktree from the updated `origin/main`: `git worktree add ../apksim-release-docs -b docs-VERSION
origin/main`.
1. List what changed: `git log --merges --first-parent LAST_TAG..origin/main` and `git diff --stat LAST_TAG..origin/main`.
2. Update docs the changes made stale, and only those: README.md (results, features, limits, install and image tags),
   AGENTS.md (layout, commands, new classes and options), and the matching `docs/*.md`. Match the existing style:
   concrete numbers measured, what was checked, what is not done. Do not invent measurements; if a number needs a
   run, run it or leave the old one and say so.
3. Image tag and version references: `grep -rn "ghcr.io/aglose/apksim:" README.md docs packaging` and update to
   the new version where they name the latest release.
4. Run `scripts/hooks/check-private --staged` on the staged change, commit (no trailer), push, open a PR (title like
   "Docs for VERSION: ...", no "Generated with" line), wait for `linux-smoke`, merge as in step 2. An existing open
   docs PR for this release (e.g. `docs-0.19.0`) is merged in step 2 instead; do not duplicate it.

## 5. Tag and let CI build the draft
```bash
git fetch origin && git checkout --detach origin/main   # in the release worktree
git tag -a vX.Y.Z -m "apksim X.Y.Z" && git push origin vX.Y.Z
```
The tag must point at the merged docs commit. The pre-push hook allows tags. Then watch `release.yml`:
`gh run list --workflow release.yml --limit 3`, `gh run watch RUN_ID` (background; takes a while: tarball, Linux
images). It makes a **draft** release with the tarball, `SHA256SUMS` and the test app.
**Stop** if the run fails: show the failing job's log tail (`gh run view RUN_ID --log-failed | tail -60`), fix on a
branch via PR if it is a code or workflow problem, and delete and re-push the tag only after the fix is on main
(tell the owner you are moving an unpublished tag). A failed upload can leave an incomplete draft release, and
re-running `release.yml` then fails in `gh release create`: first check it is still a draft
(`gh release view vX.Y.Z --json isDraft`), then `gh release delete vX.Y.Z` (keep the tag until the fix is on main),
then delete and re-push the tag.

## 6. Release notes and publish
1. Write notes from the merged PRs since the last tag: a short headline paragraph, then grouped bullets (Added,
   Changed, Fixed), each with a PR link `[owner/repo#N](url)`. Same privacy and no-attribution rules.
2. `gh release edit vX.Y.Z --notes-file FILE --draft=false` publishes the draft. (AGENTS.md says the draft is
   normally published by hand after reading its notes; this skill is that reading, so check the notes against the
   privacy rules once more before running it.) Publishing triggers the `tap` job (Homebrew cask) and `image-latest`.
3. Watch those: `gh run list --workflow release.yml --limit 3`. Confirm the tap received the version:
   `gh api repos/aglose/homebrew-apksim/contents/Casks/apksim.rb --jq .content | base64 -d | grep version`.

## 7. Report
Reply with: version and release URL, PRs merged (links), PRs left open and why, docs changed, whether the tap and
`:latest` image updated, and anything not verified (the Mac-only parts CI does not cover). Remove the temporary
worktrees you made (`git worktree remove`).
