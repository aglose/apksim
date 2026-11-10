#!/usr/bin/env python3
"""ci-no-skips.py JUNIT_XML COUNT: fail unless the file has exactly COUNT test cases and none was skipped."""
import sys
import xml.etree.ElementTree as ET

cases = list(ET.parse(sys.argv[1]).iter("testcase"))
skipped = [c.get("name") for c in cases if c.find("skipped") is not None]
want = int(sys.argv[2])
assert len(cases) == want and not skipped, f"tests: {[c.get('name') for c in cases]}, skipped {skipped}"
print(f"{len(cases)} tests, none skipped")
