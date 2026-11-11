#!/usr/bin/env python3
"""A pull request's build on a running `apksim serve`, and one comment on the pull request that links to it
(docs/pr-preview.md). Standard library only.

    scripts/pr-preview.py --serve https://sim.example.com --apk app-debug.apk [--base-apk base.apk]
                          [--repo OWNER/NAME --pr N] [--name "PR title"] [--ttl SECONDS] [--dry-run]

What it does:
  1. Adds the build to the server (`POST /api/upload`, or `POST /api/apps` with --apk-url) with a time to live, so the
     preview goes away by itself, and the base build too when one is given (--base-apk / --base-apk-url).
  2. Waits until the server has them (a URL add downloads in the background).
  3. Makes one comment: links to the build, to the build next to its base with the pixels that differ (`/?compare=BASE,NEW`),
     and the dark-mode and tablet variants of each. With --repo and --pr (and GITHUB_TOKEN) it posts the comment, or
     updates the one it posted before (found by its hidden marker), so a pull request has one comment however often it is
     pushed to. Without them it prints the comment.

The server's token is read from the APKSIM_SERVE_TOKEN environment variable or --token-file, sent only to --serve as a
bearer token, and never put in the comment or in any link: the script refuses to post a comment that contains it.
Reviewers' links carry no credential; they rely on whatever puts them in front of the server (a reverse proxy's sign-in,
docs/serve.md "Behind a reverse proxy"). --public-url is the address the links use when it differs from --serve (the
runner reaches the server directly; reviewers go through the proxy).

--dry-run adds nothing and posts nothing: it prints the comment with placeholders for the ids.
"""
import argparse
import datetime
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

MARKER = "apksim-preview"
GITHUB_COMMENT_LIMIT = 60000  # GitHub's is 65536 characters


def die(message):
    print("pr-preview: " + message, file=sys.stderr)
    sys.exit(1)


class Serve:
    """The calls a preview needs, all with the one bearer token."""

    def __init__(self, url, token):
        self.url = url.rstrip("/")
        self.token = token

    def call(self, method, path, body=None, headers=None, timeout=600):
        req = urllib.request.Request(self.url + path, data=body, method=method)
        if self.token:
            # Not add_header: urllib sends that one on to another origin when it follows a redirect (a proxy's sign-in).
            req.add_unredirected_header("Authorization", "Bearer " + self.token)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode() or "null")
        except urllib.error.HTTPError as e:
            text = e.read().decode(errors="replace")[:300]
            die(f"{method} {path.split('?')[0]}: HTTP {e.code} {text}")
        except OSError as e:
            die(f"{method} {path.split('?')[0]}: {e}")

    def upload(self, path, ttl):
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            req = urllib.request.Request(self.url + "/api/upload?" + urllib.parse.urlencode({"ttl": ttl}), data=f, method="POST")
            req.add_header("Content-Length", str(size))
            req.add_header("X-Filename", urllib.parse.quote(os.path.basename(path)))
            if self.token:
                req.add_unredirected_header("Authorization", "Bearer " + self.token)
            try:
                with urllib.request.urlopen(req, timeout=900) as r:
                    return json.loads(r.read().decode())
            except urllib.error.HTTPError as e:
                die(f"upload of {path}: HTTP {e.code} {e.read().decode(errors='replace')[:300]}")
            except OSError as e:
                die(f"upload of {path}: {e}")

    def add_url(self, url, name, ttl, options):
        body = {"url": url, "ttl": ttl}
        if name:
            body["name"] = name
        if options:
            body["options"] = options
        return self.call("POST", "/api/apps", json.dumps(body).encode(), {"Content-Type": "application/json"})

    def wait_ready(self, app_id, seconds):
        end = time.time() + seconds
        while True:
            st = self.call("GET", "/api/apps/" + urllib.parse.quote(app_id))
            if st.get("state") == "ready":
                return st
            if st.get("state") == "failed":
                die(f"the server could not fetch {app_id}: {st.get('error')}")
            if time.time() > end:
                die(f"{app_id} was still {st.get('state')} after {seconds} s")
            time.sleep(1)


def add_build(serve, apk, apk_url, name, ttl, options, wait):
    """The id of the build on the server: uploaded, or added by URL (then waited for)."""
    r = serve.upload(apk, ttl) if apk else serve.add_url(apk_url, name, ttl, options)
    if apk and options:
        die("launch options reach the server only with --apk-url (an upload carries none)")
    st = serve.wait_ready(r["id"], wait)
    return r["id"], st


def links(public, build, base, label, base_label):
    """Every link the comment has, keyed by what it shows. No credential in any of them."""
    def q(**kw):
        return urllib.parse.urlencode({k: v for k, v in kw.items() if v})

    root = public.rstrip("/") + "/"
    out = {}
    variants = [("", {}), (" (dark)", {"appearance": "dark"}), (" (tablet)", {"device": "tablet"})]
    for suffix, extra in variants:
        out["build" + suffix] = root + "?" + q(app=build, **extra)
        if base:
            out["compare" + suffix] = root + "?" + q(compare=f"{base},{build}", labels=f"{base_label},{label}", **extra)
    return out


def comment(key, name, sha, ls, has_base, expires, now, base_name):
    def row(title, prefix):
        parts = [f"[Open]({ls[prefix]})", f"[dark mode]({ls[prefix + ' (dark)']})", f"[tablet]({ls[prefix + ' (tablet)']})"]
        return f"| {title} | " + " · ".join(parts) + " |"

    lines = [f"<!-- {MARKER}:{key} -->", f"### apksim preview: {name}" + (f" @ `{sha[:7]}`" if sha else ""), "",
             "| | |", "|---|---|", row("Try this build", "build")]
    if has_base:
        lines.append(row(f"Next to {base_name} (differences marked)", "compare"))
    lines += ["", "The app runs on the preview server and you drive it in the browser; in the comparison your taps, scrolls and "
              "device changes go to both builds, and the third screen marks the pixels that differ. "
              "The links carry no credential: they open behind the team's sign-in.", "",
              f"Expires {expires:%Y-%m-%d %H:%M} UTC · updated {now:%Y-%m-%d %H:%M} UTC"]
    return "\n".join(lines) + "\n"


class GitHub:
    def __init__(self, api, token, repo):
        self.api, self.token, self.repo = api.rstrip("/"), token, repo

    def call(self, method, path, body=None):
        req = urllib.request.Request(self.api + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
        req.add_unredirected_header("Authorization", "Bearer " + self.token)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        if body is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode() or "null")
        except urllib.error.HTTPError as e:
            die(f"GitHub {method} {path}: HTTP {e.code} {e.read().decode(errors='replace')[:300]}")
        except OSError as e:
            die(f"GitHub {method} {path}: {e}")

    def find(self, pr, marker):
        page = 1
        while True:
            comments = self.call("GET", f"/repos/{self.repo}/issues/{pr}/comments?per_page=100&page={page}")
            for c in comments:
                if marker in (c.get("body") or ""):
                    return c
            if len(comments) < 100:
                return None
            page += 1

    def post_or_update(self, pr, key, body):
        existing = self.find(pr, f"<!-- {MARKER}:{key} -->")
        if existing:
            self.call("PATCH", f"/repos/{self.repo}/issues/comments/{existing['id']}", {"body": body})
            return "updated", existing.get("html_url")
        made = self.call("POST", f"/repos/{self.repo}/issues/{pr}/comments", {"body": body})
        return "posted", (made or {}).get("html_url")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serve", help="the server's address, as the script reaches it (https://sim.example.com)")
    ap.add_argument("--public-url", help="the address the links use, when reviewers reach the server another way (default --serve)")
    ap.add_argument("--token-file", help="the server's token (first line); else APKSIM_SERVE_TOKEN")
    ap.add_argument("--apk", help="the pull request's APK, uploaded")
    ap.add_argument("--apk-url", help="the pull request's APK at this URL, which the server downloads")
    ap.add_argument("--base-apk", help="the base build's APK, uploaded")
    ap.add_argument("--base-apk-url", help="the base build's APK at this URL")
    ap.add_argument("--option", action="append", default=[], help="a launch option of the server for the build (with --apk-url); repeatable, e.g. --option --activity --option .Main")
    ap.add_argument("--name", default="this build", help="what the comment calls the build (a title)")
    ap.add_argument("--base-name", default="the base build", help="what the comment calls the base build")
    ap.add_argument("--key", default="default", help="one comment per key on a pull request (several apps, several jobs)")
    ap.add_argument("--sha", default=os.environ.get("GITHUB_SHA", ""), help="the commit, shown short")
    ap.add_argument("--ttl", type=int, default=3 * 86400, help="seconds the builds stay on the server (60 to 604800; default 3 days)")
    ap.add_argument("--wait", type=int, default=300, help="seconds to wait for the server to have each build")
    ap.add_argument("--repo", help="OWNER/NAME of the repository to comment on (default $GITHUB_REPOSITORY)")
    ap.add_argument("--pr", type=int, help="the pull request's number: without it the comment is only printed")
    ap.add_argument("--github-api", default=os.environ.get("GITHUB_API_URL", "https://api.github.com"))
    ap.add_argument("--dry-run", action="store_true", help="add nothing, post nothing: print the comment with placeholder ids")
    a = ap.parse_args(argv)

    if not a.serve:
        die("--serve is required")
    if not re.match(r"^https?://", a.serve):
        die("--serve is an http(s) address")
    if not (a.apk or a.apk_url) and not a.dry_run:
        die("--apk or --apk-url is required")
    if a.apk and a.apk_url or a.base_apk and a.base_apk_url:
        die("give an APK or an address for each build, not both")
    if not 60 <= a.ttl <= 7 * 86400:
        die("--ttl is 60 to 604800 seconds")
    token = os.environ.get("APKSIM_SERVE_TOKEN", "")
    if a.token_file:
        with open(a.token_file) as f:
            token = f.readline().strip()
    public = a.public_url or a.serve
    if urllib.parse.urlparse(public).query or (token and token in public):
        die("the links' address must be a plain address with no query or token")

    has_base = bool(a.base_apk or a.base_apk_url)
    if a.dry_run:
        build_id, base_id = "BUILD_ID", "BASE_ID" if has_base else None
    else:
        serve = Serve(a.serve, token)
        build_id, _ = add_build(serve, a.apk, a.apk_url, a.name, a.ttl, a.option, a.wait)
        base_id = add_build(serve, a.base_apk, a.base_apk_url, a.base_name, a.ttl, [], a.wait)[0] if has_base else None
    now = datetime.datetime.now(datetime.timezone.utc)
    ls = links(public, build_id, base_id, "New", "Base")
    body = comment(a.key, a.name, a.sha, ls, has_base, now + datetime.timedelta(seconds=a.ttl), now, a.base_name)
    if token and token in body:
        die("the server's token is in the comment; refusing to post it")
    if len(body) > GITHUB_COMMENT_LIMIT:
        die("the comment is too long")

    repo, gh_token = a.repo or os.environ.get("GITHUB_REPOSITORY"), os.environ.get("GITHUB_TOKEN")
    if a.dry_run or not a.pr:
        sys.stdout.write(body)
        return 0
    if not repo or not gh_token:
        die("posting needs --repo (or GITHUB_REPOSITORY) and GITHUB_TOKEN")
    what, url = GitHub(a.github_api, gh_token, repo).post_or_update(a.pr, a.key, body)
    print(f"{what} the preview comment on {repo}#{a.pr}" + (f": {url}" if url else ""))
    print("build id " + build_id + (", base id " + base_id if base_id else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
