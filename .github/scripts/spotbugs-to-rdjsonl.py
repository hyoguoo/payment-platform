#!/usr/bin/env python3
"""SpotBugs XML → reviewdog rdjsonl 변환기.

사용법:
  python3 spotbugs-to-rdjsonl.py <xml-path> [<module-path>]

multi-module 환경에서 두 번째 인자(module-path) 를 주면
sourcepath 앞에 그 모듈 prefix 를 붙여 reviewdog 가 PR diff 와 정확히 매칭한다.
"""
import json
import sys
import xml.etree.ElementTree as ET

xml_path = sys.argv[1]
module_path = sys.argv[2] if len(sys.argv) > 2 else None

tree = ET.parse(xml_path)
root = tree.getroot()

for bug in root.findall(".//BugInstance"):
    src = bug.find("SourceLine[@primary='true']") or bug.find("SourceLine")
    if src is None:
        continue
    sourcepath = src.get("sourcepath", "")
    if not sourcepath:
        continue

    line = int(src.get("start") or 1)
    bug_type = bug.get("type", "")
    long_msg = bug.find("LongMessage")
    message = long_msg.text if long_msg is not None else bug_type

    if module_path:
        full_path = f"{module_path}/src/main/java/{sourcepath}"
    else:
        full_path = f"src/main/java/{sourcepath}"

    print(json.dumps({
        "message": f"[SpotBugs/{bug_type}] {message}",
        "location": {
            "path": full_path,
            "range": {"start": {"line": line, "column": 1}},
        },
        "severity": "WARNING",
    }))
