#!/usr/bin/env python3
"""SpotBugs XML → reviewdog rdjsonl 변환기"""
import json
import sys
import xml.etree.ElementTree as ET

tree = ET.parse(sys.argv[1])
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

    print(json.dumps({
        "message": f"[SpotBugs/{bug_type}] {message}",
        "location": {
            "path": f"src/main/java/{sourcepath}",
            "range": {"start": {"line": line, "column": 1}},
        },
        "severity": "WARNING",
    }))
