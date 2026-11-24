#!/usr/bin/env python3
"""Unit checks for scripts/crawl-select.py and crawl.py's --diff-seed / ui_diff (no apksim, <1 s)."""
import importlib.util, json, os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import crawl  # noqa: E402

spec = importlib.util.spec_from_file_location("crawl_select", os.path.join(HERE, "crawl-select.py"))
crawl_select = importlib.util.module_from_spec(spec)
spec.loader.exec_module(crawl_select)

failures = []


def check(ok, what, detail=""):
    print(("ok   " if ok else "FAIL ") + what + (f": {detail}" if detail and not ok else ""), flush=True)
    if not ok:
        failures.append(what)


def sample_doc():
    return {
        "deps_packages": ["com/example/"],
        "start_deps": {
            "classes": ["com.example.App"],
            "sources": ["com/example/App.kt"],
            "resources": ["style/AppTheme"],
        },
        "screens": [
            {
                "id": "Main-1111", "title": "Home", "activity": "com.example.Main",
                "texts": ["Home", "Welcome", "12:34"],
                "actions": [{"label": "Settings", "kind": "control"}],
                "deps": {
                    "sources": ["com/example/HomeScreen.kt", "com/example/SharedCard.kt"],
                    "resources": ["string/home_title", "drawable/ic_home"],
                },
            },
            {
                "id": "Main-2222", "title": "Settings", "activity": "com.example.Main",
                "texts": ["Settings", "Server URL", "Save"],
                "actions": [{"label": "Save", "kind": "control"}],
                "deps": {
                    "sources": ["com/example/SettingsScreen.kt", "com/example/SharedCard.kt"],
                    "resources": ["string/settings_url_hint", "string/save_btn"],
                },
            },
            {
                "id": "Main-3333", "title": "Profile", "activity": "com.example.Main",
                "texts": ["Profile"],
                "actions": [],
                "deps": {
                    "sources": ["com/example/ProfileScreen.kt"],
                    "resources": ["drawable/ic_avatar"],
                },
            },
        ],
    }


def main():
    doc = sample_doc()
    repo = tempfile.mkdtemp(prefix="crawl-select-check-")
    # Create a tiny git repo to test real git-diff values XML narrowing
    subprocess.run(["git", "-C", repo, "init", "-q"], check=True)
    subprocess.run(["git", "-C", repo, "config", "user.email", "test@example.com"], check=True)
    subprocess.run(["git", "-C", repo, "config", "user.name", "Test"], check=True)

    def write(rel, content):
        p = os.path.join(repo, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write(content)
        return p

    f_settings = write("app/src/main/kotlin/com/example/SettingsScreen.kt",
                       "package com.example\nimport app.generated.resources.Res\nimport app.generated.resources.kmp_settings_title\n"
                       "fun Settings() { Res.string.kmp_settings_title }\n")
    f_shared = write("app/src/main/kotlin/com/example/SharedCard.kt", "package com.example\nfun SharedCard() {}\n")
    f_app = write("app/src/main/kotlin/com/example/App.kt", "package com.example\nclass App\n")
    f_unused = write("app/src/main/kotlin/com/example/UnusedScreen.kt", "package com.example\nfun Unused() {}\n")
    f_other = write("lib/src/main/kotlin/org/other/Helper.kt", "package org.other\nfun help() {}\n")
    f_profile = write("app/src/main/kotlin/com/example/ProfileScreen.kt",
                      "package com.example\nfun Profile() { Res.drawable.ic_badge_gold }\n")
    f_strings = write("app/src/main/res/values/strings.xml",
                      '<resources><string name="home_title">Home</string><string name="settings_url_hint">Server URL</string></resources>\n')
    f_kmp_strings = write("shared/src/commonMain/composeResources/values/strings.xml",
                          '<resources><string name="kmp_home">Home</string><string name="kmp_settings_title">Settings</string></resources>\n')
    write("README.md", "hi\n")
    subprocess.run(["git", "-C", repo, "add", "."], check=True)
    subprocess.run(["git", "-C", repo, "commit", "-q", "-m", "base"], check=True)

    # 1. Single screen Kotlin edit selects only that screen
    r = crawl_select.select_screens(doc, [("app/src/main/kotlin/com/example/SettingsScreen.kt", f_settings)], repo=repo)
    check(r["mode"] == "partial" and r["selected"] == ["Main-2222"], "single screen edit selects only Settings", str(r))

    # 2. Shared composable edit selects both dependent screens in baseline order
    r = crawl_select.select_screens(doc, [("app/src/main/kotlin/com/example/SharedCard.kt", f_shared)], repo=repo)
    check(r["mode"] == "partial" and r["selected"] == ["Main-1111", "Main-2222"], "shared file selects both screens", str(r))

    # 3. start_deps edit triggers full fallback
    r = crawl_select.select_screens(doc, [("app/src/main/kotlin/com/example/App.kt", f_app)], repo=repo)
    check(r["mode"] == "full" and "start_deps" in (r["fallback_reason"] or ""), "start_deps triggers full walk", str(r))

    # 4. Probed-but-unexecuted file selects 0 screens
    r = crawl_select.select_screens(doc, [("app/src/main/kotlin/com/example/UnusedScreen.kt", f_unused)], repo=repo)
    check(r["mode"] == "none" and r["selected"] == [], "unexecuted probed file selects 0 screens", str(r))

    # 4b. Structured reasons, ignored and unplaced (issue 632)
    check(r["unplaced"] == ["app/src/main/kotlin/com/example/UnusedScreen.kt"], "unexecuted file is reported unplaced", str(r))
    r = crawl_select.select_screens(doc, [("app/src/main/kotlin/com/example/SettingsScreen.kt", f_settings),
                                          ("README.md", os.path.join(repo, "README.md"))], repo=repo)
    check(r["screens"] == {"Main-2222": [{"file": "app/src/main/kotlin/com/example/SettingsScreen.kt", "via": "source"}]}
          and r["ignored"] == ["README.md"] and r["full"] is None and r["diff_controls"] == "all",
          "structured reasons, ignored and diff_controls", str(r))
    r = crawl_select.select_screens(doc, [("app/src/main/kotlin/com/example/App.kt", f_app)], repo=repo)
    check(r["full"] == {"file": "app/src/main/kotlin/com/example/App.kt", "rule": "start_deps"}, "full names file and rule", str(r))

    # 4c. A constants-only file selects the screens of the files that use its constants
    f_const = write("app/src/main/kotlin/com/example/Limits.kt", "package com.example\nconst val MAX_ROWS = 5\n")
    write("app/src/main/kotlin/com/example/UsesLimits.kt", "package com.example\nfun x() = MAX_ROWS\n")
    doc2 = json.loads(json.dumps(doc))
    doc2["screens"][1]["deps"]["sources"].append("com/example/UsesLimits.kt")
    r = crawl_select.select_screens(doc2, [("app/src/main/kotlin/com/example/Limits.kt", f_const)], repo=repo)
    check(r["mode"] == "partial" and r["selected"] == ["Main-2222"] and r["screens"]["Main-2222"][0]["via"] == "constant"
          and r["unplaced"] == [], "constants file selects screens of its users", str(r))
    r = crawl_select.select_screens(doc, [("app/src/main/kotlin/com/example/Limits.kt", f_const)], repo=repo)
    check(r["mode"] == "none" and r["unplaced"], "constants nobody uses stay unplaced", str(r))

    jc = write("app/src/main/java/com/example/JLimits.java", "package com.example;\n/** Limits class. */\npublic final class JLimits { public static final int X = 1; }\n")
    kc = write("app/src/main/kotlin/com/example/KLimits.kt", "package com.example\n/** Limits class for rows. */\nobject C { const val Z = 1 }\n")
    check(crawl_select.declared_constants(jc) == ["X"], "java static final constants-only", "")
    check(crawl_select.declared_constants(kc) == ["Z"], "kotlin comment and one-line object", "")
    check(crawl_select.declared_constants(write("app/src/main/java/com/example/JCode.java", "package com.example;\nclass J { static final int Y = 1; int f() { return Y; } }\n")) == [], "java with a method is code", "")

    # 4d. A screen never explored is selected by any change that is not ignored
    doc3 = json.loads(json.dumps(doc))
    doc3["screens"].append({"id": "Main-9999", "title": "Deep"})
    r = crawl_select.select_screens(doc3, [("app/src/main/kotlin/com/example/UnusedScreen.kt", f_unused)], repo=repo)
    check(r["mode"] == "partial" and r["selected"] == ["Main-9999"], "unexplored screen selected by an unplaced change", str(r))
    r = crawl_select.select_screens(doc3, [("README.md", os.path.join(repo, "README.md"))], repo=repo)
    check(r["mode"] == "none", "unexplored screen not selected by an ignored change", str(r))

    # 5. Unprobed package triggers full fallback
    r = crawl_select.select_screens(doc, [("lib/src/main/kotlin/org/other/Helper.kt", f_other)], repo=repo)
    check(r["mode"] == "full", "unprobed package triggers full walk", str(r))

    # 6. Ignored non-Android files (README, iosApp, tests) select 0 screens
    r = crawl_select.select_screens(doc, [("README.md", os.path.join(repo, "README.md")), ("iosApp/Foo.swift", "")], repo=repo)
    check(r["mode"] == "none", "docs and iosApp files ignored", str(r))

    # 7. File-based resource (drawable-night) maps to drawable/<stem>
    r = crawl_select.select_screens(doc, [("app/src/main/res/drawable-night/ic_avatar.xml", "")], repo=repo)
    check(r["mode"] == "partial" and r["selected"] == ["Main-3333"], "drawable-night maps to Profile", str(r))

    # 8. values/strings.xml git-diff narrows to ONLY the changed string key
    write("app/src/main/res/values/strings.xml",
          '<resources><string name="home_title">Home</string><string name="settings_url_hint">Custom Server URL</string></resources>\n')
    r = crawl_select.select_screens(doc, [("app/src/main/res/values/strings.xml", f_strings)], repo=repo, diff="HEAD")
    check(r["mode"] == "partial" and r["selected"] == ["Main-2222"], "strings.xml git diff selects only Settings (not Home)", str(r))

    # 8b. Compose Multiplatform composeResources/values/strings.xml narrows to referencing screen
    write("shared/src/commonMain/composeResources/values/strings.xml",
          '<resources><string name="kmp_home">Home</string><string name="kmp_settings_title">App Settings</string></resources>\n')
    r = crawl_select.select_screens(doc, [("shared/src/commonMain/composeResources/values/strings.xml", f_kmp_strings)],
                                    repo=repo, diff="HEAD")
    check(r["mode"] == "partial" and r["selected"] == ["Main-2222"],
          "KMP composeResources strings.xml selects only Settings", str(r))

    # 8c. Compose Multiplatform composeResources/drawable/ maps via Res.drawable.<ident>
    r = crawl_select.select_screens(doc, [("shared/src/commonMain/composeResources/drawable/ic-badge-gold.xml", "")],
                                    repo=repo)
    check(r["mode"] == "partial" and r["selected"] == ["Main-3333"],
          "KMP composeResources drawable maps to Profile", str(r))

    # 8d. Compose Multiplatform composeResources/files/ falls back conservatively to full
    r = crawl_select.select_screens(doc, [("shared/src/commonMain/composeResources/files/config.json", "")], repo=repo)
    check(r["mode"] == "full", "KMP composeResources/files/ falls back to full", str(r))

    # 9. Manifest / build file triggers full fallback
    r = crawl_select.select_screens(doc, [("app/build.gradle.kts", "")], repo=repo)
    check(r["mode"] == "full", "build.gradle.kts triggers full walk", str(r))

    # 10. Semantic ui_diff ignores clock ticks and reports exact text/control replacements
    base_s = doc["screens"][1]
    new_s = dict(base_s, texts=["Settings", "Custom Server URL", "Save", "09:42"],
                 actions=[{"label": "Save changes", "kind": "control"}])
    ud = crawl.ui_diff(base_s, new_s)
    check(ud.get("texts", {}).get("replaced") == [["Server URL", "Custom Server URL"]],
          "ui_diff detects replaced text and ignores clock tick", str(ud))
    check(ud.get("controls", {}).get("replaced") == [["Save", "Save changes"]],
          "ui_diff detects replaced control label", str(ud))
    summary = crawl.format_ui_diff(ud)
    check("Server URL" in summary and "Custom Server URL" in summary and "Save changes" in summary,
          "format_ui_diff summarizes changes cleanly", summary)

    # 11. compare_with_seed pairs carried, modified, replaced, new, and gone screens
    class DummyNew:
        def __init__(self):
            self.order = ["Main-1111", "Main-2222", "Main-3333-new", "Main-4444"]
            self.screens = {
                "Main-1111": dict(doc["screens"][0], origin="carried", captures={}),
                "Main-2222": dict(new_s, origin="walked", captures={}),
                "Main-3333-new": dict(doc["screens"][2], id="Main-3333-new", replaces="Main-3333", origin="walked", captures={}),
                "Main-4444": {"id": "Main-4444", "title": "New", "texts": ["New"], "actions": [], "origin": "walked", "captures": {}},
            }
            self.instances = []
            self.edges = []
            self.explored = set(self.screens)
            self.partial = set()
            for sc in self.screens.values():
                sc.setdefault("parent", None)

        def event(self, kind, **fields):
            events.append((kind, fields))

    events = []
    out_dir = tempfile.mkdtemp(prefix="crawl-diff-seed-check-")
    new_doc = {"combos": [], "findings": []}
    args = crawl.parse(["app.apk", "--out", out_dir, "--seed", out_dir, "--only", "Main-2222", "--diff-seed"])
    sc = crawl.SeedCompare(DummyNew(), doc, out_dir, new_doc, args, out_dir)
    sc.finish(None)
    by_id = {p["id"]: p for p in new_doc["compare"]}
    check(by_id["Main-1111"]["status"] == "same", "carried screen is same", str(by_id["Main-1111"]))
    check(by_id["Main-2222"]["status"] == "changed" and "Custom Server URL" in (by_id["Main-2222"]["why"] or ""),
          "text-edited screen is changed with semantic diff", str(by_id["Main-2222"]))
    check(by_id["Main-3333-new"]["status"] == "changed" and by_id["Main-3333-new"]["base"] == "Main-3333",
          "replaced screen paired with old id", str(by_id["Main-3333-new"]))
    check(by_id["Main-4444"]["status"] == "new", "newly discovered screen marked new", str(by_id["Main-4444"]))

    cmp_ev = {f["id"]: f for k, f in events if k == "compare"}
    check(set(cmp_ev) == {"Main-2222", "Main-3333-new", "Main-4444"} and cmp_ev["Main-4444"]["status"] == "new"
          and cmp_ev["Main-2222"]["status"] == "changed", "a compare event per walked screen, new ones included", str(events))

    if failures:
        print(f"{len(failures)} check(s) failed")
        sys.exit(1)
    print("all checks passed")


if __name__ == "__main__":
    main()
