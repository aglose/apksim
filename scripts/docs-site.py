#!/usr/bin/env python3
"""Builds the documentation site (website/mkdocs.yml) from sources that stay where they are.

Pages come from three places, all flat in one folder:
  website/pages/*.md   written for the site (the landing page, the quick start)
  README.md            one page per '## ' section named in README_PAGES
  docs/*.md            as they are

They are written for github.com, so on the way in:
  - links are rewritten: a README anchor to the page its section became, docs/X.md to X.md, any other path in the
    repository to its page on github.com;
  - a table's code spans lose the backslash GitHub wants before a |;
  - lists get what Python-Markdown needs and GitHub does not: a blank line before a list that follows a paragraph,
    and each
    nesting level four spaces in, whether the source nests by two or by four.

Usage:
  scripts/docs-site.py [--serve] [--strict] [--out DIR]
    --serve    live preview (zensical serve); pages are assembled once, so run again after editing a source
    --strict   exit 1 when a link between pages has no target (CI)
    --no-build only assemble work/site/docs and check links
    --deploy   build, then publish the site to Firebase Hosting (https://apksim.web.app)
Needs Zensical, on the PATH or in work/site-venv (once):
  python3 -m venv work/site-venv && work/site-venv/bin/pip install -r website/requirements.txt
"""
import argparse
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = "https://github.com/aglose/apksim"
FIREBASE_PROJECT = "apksim"   # its Hosting site is https://apksim.web.app

# README '## ' heading -> page. A section not listed here stops the build: decide where a new one goes.
README_PAGES = {
    "What it offers": "features.md",
    "System design": "system-design.md",
    "Installation": "installation.md",
    "Devices and launch options": "devices-and-launch-options.md",
    "The simulator window": "simulator-window.md",
    "Skins": "using-skins.md",
    "Automation": "automation.md",
    "adb device": "adb-device.md",
    "In a browser tab": "browser-tab.md",
    "Instrumented tests and CI": "instrumented-tests.md",
    "Screenshot tests": "screenshot-tests.md",
    "Network: record, replay and conditions": "network-overview.md",
    "Replay, trace viewer and crawler": "replay-trace-crawler.md",
    "Agents, Bluetooth LE and scenes": "agents-ble-scenes.md",
    "Linux and Docker": "linux-and-docker.md",
    "Debugging": "debugging.md",
    "Configuration reference": "configuration-reference.md",
    "Performance": "performance.md",
    "How it was built": "how-it-was-built.md",
    "Limitations": "limitations.md",
    "Building from source and contributing": "building-and-contributing.md",
    "License": "license.md",
}
# Sections the site has its own page for: links to them go there, their text is not copied.
README_REPLACED = {"Contents": "index.md", "Quick start": "quickstart.md"}

FENCE = re.compile(r"^(\s*)(```+|~~~+)")
LIST_ITEM = re.compile(r"^(\s*)([-*+]|\d+[.)])\s+\S")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
LINK = re.compile(r"(\]\()([^)\s]+)((?:\s+\"[^\"]*\")?\))")


def slug(text):
    """GitHub's anchor for a heading (and pymdownx's slugify(case='lower'), which the site is set to)."""
    text = re.sub(r"`|\*\*|__|\[([^\]]*)\]\([^)]*\)", lambda m: m.group(1) or "", text)
    text = re.sub(r"[^\w\- ]", "", text.strip().lower())
    return text.replace(" ", "-")


def outside_fences(lines):
    """Yields (index, line, in_fence)."""
    fence = None
    for i, line in enumerate(lines):
        m = FENCE.match(line)
        if fence is None and m:
            fence = m.group(2)[0] * 3
            yield i, line, True
        elif fence is not None:
            if m and m.group(2).startswith(fence) and line.strip().strip("`~") == "":
                fence = None
            yield i, line, True
        else:
            yield i, line, False


def headings(lines):
    seen = {}
    out = []
    for i, line, fenced in outside_fences(lines):
        m = None if fenced else HEADING.match(line)
        if m:
            s = slug(m.group(2))
            n = seen.get(s, 0)
            seen[s] = n + 1
            out.append((i, len(m.group(1)), m.group(2), s if n == 0 else "%s-%d" % (s, n)))
    return out


def fix_lists(lines):
    """Lists as Python-Markdown wants them: a blank line before a list that follows a paragraph, and each nesting
    level four spaces in, whether the source nested by two spaces or by four."""
    out = []
    stack = []            # the indentation of the list items open above this line, outermost first
    block_has_list = False
    prev_blank = True
    shift = None          # inside a fence: (closing marker, spaces to remove, spaces to add)
    for line in lines:
        if shift is not None:
            body = line[shift[1]:] if line[:shift[1]].strip() == "" else line.lstrip(" ")
            out.append(" " * shift[2] + body if line.strip() else line)
            m = FENCE.match(line)
            if m and m.group(2).startswith(shift[0]) and line.strip().strip("`~") == "":
                shift = None
            continue
        if not line.strip():
            out.append(line)
            prev_blank = True
            block_has_list = False
            continue
        indent = len(line) - len(line.lstrip(" "))
        fence = FENCE.match(line)
        item = LIST_ITEM.match(line)
        if item:
            while stack and stack[-1] > indent:
                stack.pop()
            if not stack or indent >= stack[-1] + 2:
                stack.append(indent)
            if not prev_blank and not block_has_list and len(stack) == 1:
                out.append("")
            block_has_list = True
            out.append(" " * (4 * (len(stack) - 1)) + line.lstrip(" "))
        elif indent == 0 and not fence:
            stack = []
            out.append(line)
        elif not stack:
            if fence:
                shift = (fence.group(2)[0] * 3, 0, 0)
            out.append(line)
        else:
            # inside a list item: its text, or a fence, sits one level in from the item's marker
            while len(stack) > 1 and stack[-1] >= indent:
                stack.pop()
            if indent == 0:
                stack = []
                shift = (fence.group(2)[0] * 3, 0, 0)
                out.append(line)
            else:
                inner = 4 * len(stack)
                if fence:
                    shift = (fence.group(2)[0] * 3, indent, inner)
                out.append(" " * inner + line.lstrip(" "))
        prev_blank = False
    return out


class Site:
    def __init__(self):
        self.pages = {}      # name -> lines
        self.anchors = {}    # name -> set of anchors
        self.readme_anchor = {}  # README anchor -> (page, anchor or None)
        self.problems = []

    def load(self):
        with open(os.path.join(ROOT, "README.md"), encoding="utf-8") as f:
            readme = f.read().split("\n")
        heads = headings(readme)
        tops = [h for h in heads if h[1] == 2]
        for n, (start, _, title, anchor) in enumerate(tops):
            end = tops[n + 1][0] if n + 1 < len(tops) else len(readme)
            if title in README_REPLACED:
                page = README_REPLACED[title]
                for h in heads:
                    if start <= h[0] < end:
                        self.readme_anchor[h[3]] = (page, None)
                continue
            if title not in README_PAGES:
                sys.exit("docs-site: README section '%s' has no page: add it to README_PAGES in %s and to "
                         "website/mkdocs.yml's nav" % (title, os.path.relpath(__file__, ROOT)))
            page = README_PAGES[title]
            body = []
            for i, line, fenced in outside_fences(readme[start:end]):
                m = None if fenced else HEADING.match(line)
                body.append(line[1:] if m else line)   # '## X' is the page's title, '### Y' its sections
            self.pages[page] = body
            for h in heads:
                if start <= h[0] < end:
                    self.readme_anchor[h[3]] = (page, None if h[0] == start else h[3])
        self.readme_anchor["apksim"] = ("index.md", None)
        for folder in ("docs", os.path.join("website", "pages")):
            for name in sorted(os.listdir(os.path.join(ROOT, folder))):
                if name.endswith(".md"):
                    if name in self.pages:
                        sys.exit("docs-site: two sources for the page " + name)
                    with open(os.path.join(ROOT, folder, name), encoding="utf-8") as f:
                        self.pages[name] = f.read().split("\n")
                    self.origin = getattr(self, "origin", {})
                    self.origin[name] = folder
        self.repeated = {}   # name -> anchors of a heading whose text an earlier heading of the page has
        for name, lines in self.pages.items():
            heads = headings(lines)
            self.anchors[name] = {h[3] for h in heads}
            self.repeated[name] = {h[3] for h in heads if h[3] != slug(h[2])}
        # github.com numbers a repeated heading's anchor (#automation-1) and the site's Markdown numbers it another
        # way, so a link to either lands somewhere else: the README's headings must differ
        for _, _, title, anchor in headings(readme):
            if anchor != slug(title):
                self.problems.append("README.md: two headings named '%s': links to #%s go to the first"
                                     % (title, slug(title)))

    def target(self, page, url):
        """Where a link written in `page`'s source goes on the site."""
        if re.match(r"^[a-z][a-z0-9+.-]*:", url) or url.startswith("//"):
            return url
        folder = getattr(self, "origin", {}).get(page)   # None: a README section
        path, _, anchor = url.partition("#")
        if folder and folder.startswith("website"):
            return url                                    # written for the site
        if not path:
            if folder is None:                            # an anchor in the README
                if anchor not in self.readme_anchor:
                    self.problems.append("%s: README has no anchor #%s" % (page, anchor))
                    return url
                to, a = self.readme_anchor[anchor]
                return ("" if to == page else to) + ("#" + a if a else "")
            return url
        full = os.path.normpath(os.path.join(folder or "", path))
        if full == "README.md":
            if not anchor:
                return "index.md"
            if anchor not in self.readme_anchor:
                self.problems.append("%s: README has no anchor #%s" % (page, anchor))
                return REPO + "#" + anchor
            to, a = self.readme_anchor[anchor]
            return to + ("#" + a if a else "")
        if os.path.dirname(full) == "docs" and os.path.exists(os.path.join(ROOT, full)):
            if full.endswith(".md") or full.endswith(".svg"):
                return os.path.basename(full) + ("#" + anchor if anchor else "")
        if full.startswith(".."):
            self.problems.append("%s: link leaves the repository: %s" % (page, url))
            return url
        if not os.path.exists(os.path.join(ROOT, full)):
            self.problems.append("%s: no such file: %s" % (page, url))
        kind = "tree" if os.path.isdir(os.path.join(ROOT, full)) else "blob"
        return "%s/%s/main/%s%s" % (REPO, kind, full, "#" + anchor if anchor else "")

    def convert(self, page):
        lines = self.pages[page]
        if getattr(self, "origin", {}).get(page, "").startswith("website"):
            return lines                                  # written for the site's Markdown already
        out = []
        for _, line, fenced in outside_fences(lines):
            if not fenced:
                # not inside `code spans`
                parts = re.split(r"(`[^`]*`)", line)
                for k in range(0, len(parts), 2):
                    parts[k] = LINK.sub(lambda m: m.group(1) + self.target(page, m.group(2)) + m.group(3), parts[k])
                if line.lstrip().startswith("|"):
                    # GitHub reads \| inside a table's code span as |; Python-Markdown shows the backslash
                    # and an option's alternatives (`--window front|nofocus|...`) may wrap after each |
                    for k in range(1, len(parts), 2):
                        code = parts[k][1:-1].replace("\\|", "|")
                        if "|" in code:
                            code = code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                            parts[k] = "<code>" + code.replace("|", "&#124;<wbr>") + "</code>"
                line = "".join(parts)
            out.append(line)
        return fix_lists(out)

    def check(self, page, lines):
        for _, line, fenced in outside_fences(lines):
            if fenced:
                continue
            for part in re.split(r"(`[^`]*`)", line)[::2]:
                for m in LINK.finditer(part):
                    url = m.group(2)
                    if re.match(r"^[a-z][a-z0-9+.-]*:", url) or url.startswith("//"):
                        continue
                    path, _, anchor = url.partition("#")
                    to = path or page
                    if to.endswith(".md"):
                        if to not in self.pages:
                            self.problems.append("%s: link to a page that does not exist: %s" % (page, url))
                        elif anchor and anchor in self.repeated[to]:
                            self.problems.append("%s: %s#%s is a repeated heading's numbered anchor, which the "
                                                 "site numbers differently" % (page, to, anchor))
                        elif anchor and anchor not in self.anchors[to]:
                            self.problems.append("%s: %s has no heading #%s" % (page, to, anchor))


def nav_pages(config):
    return set(re.findall(r":\s+([\w.-]+\.md)\s*$", config, re.M))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serve", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--deploy", action="store_true",
                    help="after a build with no problems, publish to Firebase Hosting (needs the firebase CLI, "
                         "signed in or with GOOGLE_APPLICATION_CREDENTIALS set)")
    ap.add_argument("--out", help="the built site's folder (default work/site/out)")
    args = ap.parse_args()

    work = os.path.join(ROOT, "work", "site")
    src = os.path.join(work, "docs")
    shutil.rmtree(src, ignore_errors=True)
    os.makedirs(src)

    site = Site()
    site.load()
    for page in sorted(site.pages):
        lines = site.convert(page)
        site.check(page, lines)
        with open(os.path.join(src, page), "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
    for name in os.listdir(os.path.join(ROOT, "docs")):
        if name.endswith((".svg", ".png", ".jpg")):
            shutil.copy(os.path.join(ROOT, "docs", name), src)
    shutil.copytree(os.path.join(ROOT, "website", "assets"), os.path.join(src, "site-assets"))
    with open(os.path.join(ROOT, "website", "mkdocs.yml"), encoding="utf-8") as f:
        config = f.read()
    with open(os.path.join(work, "mkdocs.yml"), "w", encoding="utf-8") as f:
        f.write(config)
    shutil.copy(os.path.join(ROOT, "website", "firebase.json"), work)

    listed = nav_pages(config)
    for page in sorted(set(site.pages) - listed):
        site.problems.append("%s: not in website/mkdocs.yml's nav" % page)
    for page in sorted(listed - set(site.pages)):
        site.problems.append("website/mkdocs.yml: nav names %s, which no source gives" % page)

    for p in site.problems:
        print("docs-site: " + p, file=sys.stderr)
    print("docs-site: %d pages in %s, %d problems" % (len(site.pages), os.path.relpath(src, ROOT), len(site.problems)))
    if (args.strict or args.deploy) and site.problems:
        return 1
    if args.no_build:
        return 0

    venv = os.path.join(ROOT, "work", "site-venv")
    tool = shutil.which("zensical")
    if not tool and os.path.exists(os.path.join(venv, "bin", "zensical")):
        tool = os.path.join(venv, "bin", "zensical")
    if not tool:
        sys.exit("docs-site: zensical is not installed. Once:\n"
                 "  python3 -m venv work/site-venv && work/site-venv/bin/pip install -r website/requirements.txt")
    if args.serve:
        return subprocess.call([tool, "serve"], cwd=work)
    status = subprocess.call([tool, "build", "--clean"], cwd=work)
    if status == 0 and args.out:
        shutil.rmtree(args.out, ignore_errors=True)
        shutil.copytree(os.path.join(work, "out"), args.out)
    if status == 0 and args.deploy:
        firebase = shutil.which("firebase")
        if not firebase:
            sys.exit("docs-site: the firebase CLI is not installed: npm install -g firebase-tools")
        status = subprocess.call([firebase, "deploy", "--only", "hosting", "--project", FIREBASE_PROJECT], cwd=work)
    return status


if __name__ == "__main__":
    sys.exit(main())
