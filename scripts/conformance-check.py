#!/usr/bin/env python3
"""Checks scripts/conformance.py without a device: its reading of `am instrument -r` output (a crash included) and
its table, from two made-up JUnit XML files with a test in each category. CI runs it from scripts/ci-smoke.sh."""
import importlib.util
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("conformance", os.path.join(HERE, "conformance.py"))
conformance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(conformance)


def status(cls, test, code, stack=None):
    lines = [f"INSTRUMENTATION_STATUS: class={cls}", f"INSTRUMENTATION_STATUS: test={test}"]
    if stack:
        lines.append(f"INSTRUMENTATION_STATUS: stack={stack}")
    return "\n".join(lines + [f"INSTRUMENTATION_STATUS_CODE: {code}"])


raw = "\n".join([
    status("a.B", "ok", 1),
    status("a.B", "ok", 0),
    status("a.B", "bad", 1),
    status("a.B", "bad", -2, "java.lang.AssertionError: no\n\tat a.B.bad(B.kt:3)"),
    status("a.B", "skip", -4),
    "INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
    "INSTRUMENTATION_CODE: 0",
])
results, finished = conformance.parse_raw(raw)
assert results[("a.B", "ok")] == ("passed", ""), results
assert results[("a.B", "bad")][0] == "failed" and "at a.B.bad" in results[("a.B", "bad")][1], results
assert results[("a.B", "skip")][0] == "skipped", results
assert not finished, "a crashed run must not count as finished"
assert conformance.parse_raw("INSTRUMENTATION_CODE: -1\n")[1], "a complete run counts as finished"
assert conformance.cause("java.lang.AssertionError: expected:<3> but was:<1>\n\tat x") == \
    "java.lang.AssertionError: expected:<N> but was:<N>"
assert conformance.cause("java.lang.RuntimeException: wrap\nCaused by: java.io.IOException: 'x' gone") == \
    "java.io.IOException: … gone"

with tempfile.TemporaryDirectory() as d:
    here = {("a.B", "both"): ("passed", ""), ("a.B", "here"): ("failed", "java.lang.IllegalStateException: x"),
            ("a.B", "neither"): ("failed", "java.lang.Error: e"), ("a.B", "emu"): ("passed", ""),
            ("a.B", "skipped"): ("skipped", ""), ("a.B", "onlyhere"): ("passed", "")}
    emu = {("a.B", "both"): ("passed", ""), ("a.B", "here"): ("passed", ""), ("a.B", "neither"): ("failed", "e"),
           ("a.B", "emu"): ("failed", "java.lang.Error: e"), ("a.B", "skipped"): ("passed", "")}
    conformance.write_xml(os.path.join(d, "here.xml"), here)
    conformance.write_xml(os.path.join(d, "emu.xml"), emu)
    out = subprocess.run([sys.executable, os.path.join(HERE, "conformance.py"), "x-androidTest.apk", "--out", d,
                          "--here-xml", os.path.join(d, "here.xml"), "--emulator-xml", os.path.join(d, "emu.xml")],
                         capture_output=True, text=True, check=True).stdout
    assert "| 1 | 1 | 1 | 1 | 1 | 1 |" in out, out
    assert "- 1: `java.lang.IllegalStateException: x`" in out, out
    assert os.path.exists(os.path.join(d, "table.md"))
print("conformance-check: ok")
