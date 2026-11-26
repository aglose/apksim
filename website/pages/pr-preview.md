---
title: Pull Request Previews
---

<div class="page-hero" markdown>

# PR previews &amp; compare mode

<p class="sim-lead">Comment live browser links on every pull request so reviewers can try the new APK, drive it side by side against <code>main</code> with real-time pixel diffs, inspect network requests, and file bug reports in one click.</p>

<div class="sim-pills">
<span class="sim-pill">Side-by-side pixel diff</span>
<span class="sim-pill">GitHub Action included</span>
<span class="sim-pill">Expiring TTL builds</span>
<span class="sim-pill">In-tab network inspector</span>
</div>

</div>

## Three tools for PR review

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Visual diff</div>
**Compare mode (`?compare=A,B`)**

Runs two builds side by side in one tab, mirrors every tap, drag, rotation, and dark-mode toggle to both, and paints pixel differences onto a third diff screen.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">CI automation</div>
**One updating PR comment**

`scripts/pr-preview.py` and `.github/actions/preview` upload the PR and base APKs with a TTL (default 3 days) and keep a single updated comment on the PR.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Reviewer tools</div>
**Report button &amp; network rules**

When enabled on the server, reviewers can download a bug-report zip (screenshot + `.apkrec` + note) and inspect, mock, patch, or delay HTTP(S) responses live.
</div>
</div>

## Side-by-side compare mode

Open any two app IDs from `apksim serve` together:

```text
https://sim.example.com/?compare=BASE_ID,NEW_ID&labels=Base,PR&appearance=dark&device=tablet
```

- **Mirrored input:** every tap, swipe, key press, deep link, locale, and theme switch goes to both apps. Use **Drive: A only / B only** in the toolbar to re-align screens if flows diverge.
- **Pixel diff screen:** marks changed pixels in red with a yellow bounding box and displays the diff count and percentage. Adjust the tolerance threshold (`exact`, `8`, `16`, `32`) in the toolbar.
- **Shared query parameters:** `device`, `appearance`, `locale`, `fontscale`, `open`, `orientation`, `labels=A,B`, and `codec=jpeg` apply to both sessions.

## Automated PR previews in GitHub Actions

Add `.github/actions/preview` to your pull-request workflow:

```yaml
name: preview
on:
  pull_request:
jobs:
  preview:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: write
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - name: Build PR and base APKs
        run: |
          ./gradlew :app:assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk "$RUNNER_TEMP/pr.apk"
          git checkout --quiet "${{ github.event.pull_request.base.sha }}"
          ./gradlew :app:assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk "$RUNNER_TEMP/base.apk"
      - uses: aglose/apksim/.github/actions/preview@main
        with:
          serve-url: https://sim.example.com
          public-url: https://sim.example.com/preview   # optional, if behind a proxy
          token: ${{ secrets.APKSIM_SERVE_TOKEN }}
          apk: ${{ runner.temp }}/pr.apk
          base-apk: ${{ runner.temp }}/base.apk
```

Or run the script directly from any CI system:

```bash
scripts/pr-preview.py --serve https://sim.example.com \
  --apk pr.apk --base-apk base.apk \
  --name "Fix login screen" --repo OWNER/NAME --pr 123
```

!!! info "Credentials never appear in PR comments"

    `pr-preview.py` sends `APKSIM_SERVE_TOKEN` only to `--serve` as a bearer header and refuses to post any comment containing the token. Place `apksim serve` behind your team's SSO/OAuth reverse proxy so reviewers access preview links transparently.

## Report button &amp; network inspector

Both features are **off by default** on `apksim serve` and enabled per server:

<div class="beam-security" markdown>
<div class="beam-sec-col" markdown>
<div class="sim-card-label">Bug reports · <code>--allow-rec</code></div>

- Adds a **Report a problem** card that bundles `note.txt`, `settings.json`, `screenshot.png`, `recording.apkrec` (last 5 min), and `network.json` into a single zip in the browser.
- Add `--issue-url 'https://github.com/ORG/REPO/issues/new?title={title}&body={body}'` to open a prefilled issue form in the reviewer's own browser (no server tracker credentials needed).
</div>
<div class="beam-sec-col" markdown>
<div class="sim-card-label">Network inspector · <code>--inspect-network</code></div>

- Streams live HTTP/1.1, HTTP/2, and gRPC request metadata, headers, and bodies into a panel (sensitive auth/cookie headers are redacted).
- Add **response rules** to mock status codes/bodies, inject latency, apply **JSON merge patches** (RFC 7386) to live responses, or **forward** matching requests to an allow-listed mock server (`--inspect-forward-allow`).
- Use `--inspect-network optional` so sessions only enable the inspector when requested (`?inspect=1`).
</div>
</div>

<p class="spec-note">For full wire-format details on inspector rules and TTL headers, see <a href="https://github.com/aglose/apksim/blob/main/docs/pr-preview.md">docs/pr-preview.md on GitHub</a>.</p>
