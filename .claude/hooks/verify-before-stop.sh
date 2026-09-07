#!/bin/bash
# Stop / SubagentStop — 검증 없이 턴을 끝내는 것을 막는다.
#
# "다 됐다"고 판단해 멈추는 것과 실제로 다 된 것은 다르다. 변경된 모듈의 단위 테스트가
# 통과해야 턴이 끝나게 해서, CLAUDE.md 의 "매 태스크 완료 후 회귀 확인" 을 문장이 아니라
# 조건으로 만든다.
#
# SubagentStop 에도 같은 스크립트를 건다. 워크플로우 execute 단계에서는 코드를 메인이 아니라
# implementer 서브에이전트가 쓰고 커밋까지 하므로, Stop 에만 걸면 정작 코드가 생산되는 자리를
# 비워 두게 된다.
#
# 검증 기준선은 워킹 트리가 아니라 main 과의 분기점이다. 커밋을 기준으로 삼으면 커밋한 순간
# 트리가 깨끗해져 검증이 통째로 건너뛰어진다 — 서브에이전트는 항상 커밋하고 끝나므로 특히 그렇다.
#
# 루프 방지: Stop 훅에는 재진입 플래그가 제공되지 않으므로 직접 만든다. 같은 변경 상태
# (fingerprint)로는 한 번만 막고, 세션당 차단 횟수도 제한한다. 그래서 무언가를 고쳐야만 다시
# 막히고, 고치지 못하는 상황에서 무한히 갇히지 않는다.

set -uo pipefail

MAX_BLOCKS_PER_SESSION=3
ALL_MODULES="payment-service pg-service product-service user-service gateway eureka-server"

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -n "$PROJECT_DIR" ] || exit 0
cd "$PROJECT_DIR" || exit 0

INPUT=$(cat)
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // "unknown"' | tr -d '\n' | tr -c 'A-Za-z0-9_.-' '_')

# 파일을 고칠 수 없는 서브에이전트에는 걸지 않는다. reviewer / domain-expert 는 Edit·Write 권한이
# 없어서, 검증 실패로 막아 세워도 요구받은 수정을 구조적으로 이행할 수 없다 — 차단 한도를 소진할
# 때까지 반복해서 막히기만 한다. 탐색 전용 에이전트도 같다.
AGENT_TYPE=$(echo "$INPUT" | jq -r '.agent_type // empty')
EVENT=$(echo "$INPUT" | jq -r '.hook_event_name // "?"')

# 훅이 언제 무엇 때문에 돌았고 어떻게 판정했는지 남긴다. 남기지 않으면 "훅이 안 떴다" 와
# "떴는데 통과했다" 를 구분할 방법이 없어, 검증 장치가 꺼져도 아무도 모른다.
LOG_DIR=".git/claude-stop-verify"
mkdir -p "$LOG_DIR"
trace() {
    printf '%s %s agent=%s %s\n' "$(date +%H:%M:%S)" "$EVENT" "${AGENT_TYPE:-main}" "$1" \
        >> "$LOG_DIR/trace.log"
    # 무한히 자라지 않게 최근 것만 남긴다.
    if [ "$(wc -l < "$LOG_DIR/trace.log")" -gt 400 ]; then
        tail -200 "$LOG_DIR/trace.log" > "$LOG_DIR/trace.log.tmp" \
            && mv "$LOG_DIR/trace.log.tmp" "$LOG_DIR/trace.log"
    fi
}

case " reviewer domain-expert Explore Plan " in
    *" $AGENT_TYPE "*)
        if [ -n "$AGENT_TYPE" ]; then
            trace "skip(read-only)"
            exit 0
        fi
        ;;
esac

# 기준선 — main 과의 분기점. main 이 없으면(분리된 체크아웃 등) HEAD 로 물러서서
# 워킹 트리 변경만 본다.
BASE=$(git merge-base HEAD main 2>/dev/null || git rev-parse HEAD 2>/dev/null)
[ -n "$BASE" ] || exit 0

# 검증이 필요한 변경만 추린다. 문서·설명 페이지만 바뀐 턴은 테스트를 돌릴 이유가 없다.
RELEVANT='\.(java|sql|gradle|ya?ml|properties)$'
CHANGED=$( { git diff --name-only "$BASE" 2>/dev/null; \
             git ls-files --others --exclude-standard 2>/dev/null; } \
           | grep -E "$RELEVANT" | sort -u)
[ -n "$CHANGED" ] || { trace "skip(변경 없음)"; exit 0; }

# 모듈 안의 변경은 그 모듈만, 루트 빌드 설정·정적분석 설정 변경은 전 모듈을 검증 대상으로 본다.
MODULES=$(echo "$CHANGED" | cut -d/ -f1 | sort -u \
          | grep -E -- '-service$|^gateway$|^eureka-server$')
ROOT_CHANGED=$(echo "$CHANGED" | grep -E '^(build\.gradle|settings\.gradle|gradle\.properties|config/)' || true)
ROOT_HASH=""
if [ -n "$ROOT_CHANGED" ]; then
    ROOT_HASH=$(echo "$ROOT_CHANGED" | while read -r f; do shasum -a 256 "$f" 2>/dev/null; done \
                | shasum -a 256 | cut -c1-16)
fi
[ -n "$MODULES" ] || [ -n "$ROOT_HASH" ] || exit 0

# 상태는 .git 아래 둔다 — 커밋 대상이 되지 않고 저장소를 떠나지 않는다.
STATE_DIR=".git/claude-stop-verify"
mkdir -p "$STATE_DIR"
STATE_FILE="$STATE_DIR/$SESSION_ID"
touch "$STATE_FILE"
# 오래된 세션 상태가 무한히 쌓이지 않게 최근 것만 남긴다.
ls -t "$STATE_DIR" 2>/dev/null | tail -n +21 | while read -r old; do
    rm -f "$STATE_DIR/$old"
done

# 추적 중인 변경은 diff 로, 아직 추적되지 않는 새 파일은 내용 해시로 지문에 넣는다.
# 새 파일 내용을 빼면 그 파일을 고쳐도 지문이 그대로라 재검증이 일어나지 않는다.
FINGERPRINT=$( {
    git diff "$BASE" 2>/dev/null
    git ls-files --others --exclude-standard 2>/dev/null | grep -E "$RELEVANT" | sort \
        | while read -r f; do shasum -a 256 "$f" 2>/dev/null; done
} | shasum -a 256 | cut -c1-16)

# 이 변경 상태는 이미 판정이 끝났다.
grep -q "^PASS $FINGERPRINT$" "$STATE_FILE" && { trace "skip(이미 통과 $FINGERPRINT)"; exit 0; }
grep -q "^BLOCK $FINGERPRINT$" "$STATE_FILE" && { trace "skip(이미 차단 $FINGERPRINT)"; exit 0; }

# 루트 빌드·정적분석 설정이 바뀌면 그 영향이 전 모듈에 걸리므로 한 번은 전부 검증한다. 다만 같은
# 설정 상태로 이미 통과했다면 이후 턴까지 매번 6모듈을 돌릴 이유는 없다 — 브랜치가 길어질수록
# 그 비용만 쌓인다.
if [ -n "$ROOT_HASH" ] && ! grep -q "^ROOTPASS $ROOT_HASH$" "$STATE_FILE"; then
    MODULES="$ALL_MODULES"
fi
[ -n "$MODULES" ] || exit 0

BLOCK_COUNT=$(grep -c '^BLOCK ' "$STATE_FILE")
if [ "$BLOCK_COUNT" -ge "$MAX_BLOCKS_PER_SESSION" ]; then
    echo "검증이 계속 실패하지만 세션 차단 한도에 도달해 더 막지 않는다." >&2
    exit 0
fi

TASKS=""
for m in $MODULES; do
    TASKS="$TASKS :$m:test"
done

BUILD_OUTPUT=$(./gradlew $TASKS 2>&1)
if [ $? -eq 0 ]; then
    echo "PASS $FINGERPRINT" >> "$STATE_FILE"
    [ -n "$ROOT_HASH" ] && echo "ROOTPASS $ROOT_HASH" >> "$STATE_FILE"
    trace "PASS $FINGERPRINT ($TASKS)"
    exit 0
fi

# 실패한 테스트 이름은 Gradle 로그 설정에 기대지 않고 결과 XML 에서 직접 뽑는다.
FAILED=$(for m in $MODULES; do
    grep -l "<failure\|<error" "$m"/build/test-results/test/*.xml 2>/dev/null | while read -r f; do
        python3 - "$f" <<'PYEOF'
import sys, xml.etree.ElementTree as ET
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
trace "BLOCK $FINGERPRINT ($TASKS)"
{
    echo "변경된 모듈의 검증이 실패해 턴을 끝낼 수 없다:$TASKS"
    echo
    if [ -n "$FAILED" ]; then
        echo "$FAILED"
    else
        # 테스트 결과가 없다 = 컴파일·설정 단계에서 끊겼다는 뜻이다. 그 진단을 그대로 넘긴다.
        echo "실패한 테스트가 없다 — 컴파일 또는 빌드 설정 단계에서 끊겼다:"
        echo "$BUILD_OUTPUT" | grep -vE '^\s*$' | tail -20
    fi
    echo
    echo "실패를 고치고 다시 검증한다. 원인이 이번 변경과 무관하다고 판단되면 그 근거를 밝힌다."
} >&2
exit 2
