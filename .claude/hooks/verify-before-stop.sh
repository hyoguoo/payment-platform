#!/bin/bash
# Stop — 검증 없이 턴을 끝내는 것을 막는다.
#
# "다 됐다"고 판단해 멈추는 것과 실제로 다 된 것은 다르다. 변경된 모듈의 단위 테스트가
# 통과해야 턴이 끝나게 해서, CLAUDE.md 의 "매 태스크 완료 후 회귀 확인" 을 문장이 아니라
# 조건으로 만든다.
#
# 루프 방지: Stop 훅에는 재진입 플래그가 제공되지 않으므로 직접 만든다. 같은 변경 상태
# (fingerprint)로는 한 번만 막고, 세션당 차단 횟수도 제한한다. 그래서 에이전트가 무언가를
# 고쳐야만 다시 막히고, 고치지 못하는 상황에서 무한히 갇히지 않는다.

set -uo pipefail

MAX_BLOCKS_PER_SESSION=3

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -n "$PROJECT_DIR" ] || exit 0
cd "$PROJECT_DIR" || exit 0

INPUT=$(cat)
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // "unknown"')

# 변경된 .java 파일이 없으면 검증할 것이 없다 (대화만 오간 턴).
CHANGED=$( { git diff HEAD --name-only 2>/dev/null; \
             git ls-files --others --exclude-standard 2>/dev/null; } | grep '\.java$' | sort -u)
[ -n "$CHANGED" ] || exit 0

# 변경된 파일이 속한 모듈만 검증 대상으로 삼는다.
MODULES=$(echo "$CHANGED" | cut -d/ -f1 | sort -u | grep -E -- '-service$|^gateway$|^eureka-server$')
[ -n "$MODULES" ] || exit 0

# 상태는 .git 아래 둔다 — 커밋 대상이 되지 않고 저장소를 떠나지 않는다.
STATE_DIR=".git/claude-stop-verify"
mkdir -p "$STATE_DIR"
STATE_FILE="$STATE_DIR/$SESSION_ID"
touch "$STATE_FILE"

# 추적 중인 파일은 diff 로, 아직 추적되지 않는 새 파일은 내용 해시로 지문에 넣는다.
# 새 파일 내용을 빼면 그 파일을 고쳐도 지문이 그대로라 재검증이 일어나지 않는다.
FINGERPRINT=$( {
    git diff HEAD 2>/dev/null
    git ls-files --others --exclude-standard 2>/dev/null | grep '\.java$' | sort \
        | while read -r f; do shasum -a 256 "$f" 2>/dev/null; done
} | shasum -a 256 | cut -c1-16)

# 이 변경 상태는 이미 판정이 끝났다.
grep -q "^PASS $FINGERPRINT$" "$STATE_FILE" && exit 0
grep -q "^BLOCK $FINGERPRINT$" "$STATE_FILE" && exit 0

BLOCK_COUNT=$(grep -c '^BLOCK ' "$STATE_FILE")
if [ "$BLOCK_COUNT" -ge "$MAX_BLOCKS_PER_SESSION" ]; then
    echo "검증이 계속 실패하지만 세션 차단 한도에 도달해 더 막지 않는다." >&2
    exit 0
fi

TASKS=""
for m in $MODULES; do
    TASKS="$TASKS :$m:test"
done

if ./gradlew $TASKS -q >/dev/null 2>&1; then
    echo "PASS $FINGERPRINT" >> "$STATE_FILE"
    exit 0
fi

# 실패한 테스트 이름은 Gradle 로그 설정에 기대지 않고 결과 XML 에서 직접 뽑는다.
FAILED=$(for m in $MODULES; do
    grep -l "<failure\|<error" "$m"/build/test-results/test/*.xml 2>/dev/null | while read -r f; do
        python3 - "$f" <<'PYEOF'
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(0)
for case in root.iter('testcase'):
    if case.find('failure') is not None or case.find('error') is not None:
        print(f"  {case.get('classname')} > {case.get('name')}")
PYEOF
    done
done | head -20)

echo "BLOCK $FINGERPRINT" >> "$STATE_FILE"
{
    echo "변경된 모듈의 테스트가 실패해 턴을 끝낼 수 없다:$TASKS"
    echo
    [ -n "$FAILED" ] && echo "$FAILED"
    echo
    echo "실패를 고치고 다시 검증한다. 원인이 이번 변경과 무관하다고 판단되면 그 근거를 밝힌다."
} >&2
exit 2
