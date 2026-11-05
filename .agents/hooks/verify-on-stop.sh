#!/usr/bin/env bash
# Stop(메인) / SubagentStop(implementer) — 검증 없이 턴을 끝내지 못하게 한다.
#
# "다 됐다"고 판단해 멈추는 것과 실제로 다 된 것은 다르다. 바뀐 모듈의 단위 테스트가 통과해야 턴이
# 끝난다. 정적 분석과 통합 테스트는 여기서 돌리지 않는다. 에이전트가 stack.md의 검증 순서로 직접
# 실행한다. 턴마다 모듈 전체 정적 분석을 돌리면 대기만 늘고, 스타일은 lint-file.sh가 편집 직후에 잡는다.
#
# 등록: .claude/settings.json의 Stop과 SubagentStop(matcher: implementer). 파일을 고치지 않는 검토
# 에이전트에는 걸지 않는다.
#
# 판정 순서 (hooks 설계는 .agents/profiles/stack.md의 "검증 hook" 절 참고)
#   1. 설정 블록이 없거나 검증 대상 변경이 없으면 통과
#   2. 일시정지 표식이 지금 지문과 같으면 통과, 다르면 표식을 지우고 계속
#   3. 같은 지문을 이미 판정했으면 통과 (같은 상태로 두 번 막지 않는다)
#   4. 세션 차단 한도에 도달했으면 경고와 함께 통과
#   5. 잠금을 잡고 대상 모듈의 단위 테스트 실행. 실패하면 exit 2로 종료를 막는다

set -uo pipefail

HOOK_NAME=verify
MAX_BLOCKS_PER_SESSION=3
LOCK_TIMEOUT_SECONDS="${AGENT_VERIFY_LOCK_TIMEOUT:-600}"

# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

[ -n "$PROJECT_DIR" ] || exit 0
cd "$PROJECT_DIR" || exit 0

INPUT=$(cat)
SESSION_ID=$(printf '%s' "$INPUT" | jq -r '.session_id // "unknown"' | tr -c 'A-Za-z0-9_.-' '_')
EVENT=$(printf '%s' "$INPUT" | jq -r '.hook_event_name // "?"')
ACTOR=$(printf '%s' "$INPUT" | jq -r '.agent_type // empty')
ACTOR="${ACTOR:-main}"

STATE=$(state_dir) || exit 0

if ! has_config_block; then
    trace "skip(설정 블록 없음 $PROFILE)"
    exit 0
fi

BASE=$(verify_base)
[ -n "$BASE" ] || { trace "skip(커밋 없음)"; exit 0; }

CHANGED=$(changed_relevant_files "$BASE")
[ -n "$CHANGED" ] || { trace "skip(검증 대상 변경 없음)"; exit 0; }

# MODULES가 비어 있으면 단일 모듈 프로젝트다. 검증 대상 변경이 있으면 항상 전체 단위 테스트를 돌린다.
ALL_MODULES=$(config_value MODULES)
ROOT_HASH=""
if [ -z "$ALL_MODULES" ]; then
    SINGLE_MODULE=1
    MODULES=""
else
    SINGLE_MODULE=0
    MODULES=$(modules_for_files "$CHANGED")
    ROOT_CHANGED=$(root_files_changed "$CHANGED")
    [ -n "$ROOT_CHANGED" ] && ROOT_HASH=$(content_hash "$ROOT_CHANGED")
    if [ -z "$MODULES" ] && [ -z "$ROOT_HASH" ]; then
        trace "skip(모듈 밖 변경만 있음)"
        exit 0
    fi
fi

FINGERPRINT=$(fingerprint "$BASE" "$CHANGED")

# 일시정지: 개발자가 중단을 요청한 그 상태에만 적용한다. 코드가 바뀌면 자동으로 풀린다.
PAUSE_FILE="$STATE/pause"
if [ -f "$PAUSE_FILE" ]; then
    PAUSED_FP=$(head -1 "$PAUSE_FILE" | cut -d' ' -f1)
    if [ "$PAUSED_FP" = "$FINGERPRINT" ]; then
        trace "skip(일시정지 $FINGERPRINT: $(head -1 "$PAUSE_FILE" | cut -d' ' -f2-))"
        exit 0
    fi
    rm -f "$PAUSE_FILE"
    trace "일시정지 해제(지문 변경 $PAUSED_FP -> $FINGERPRINT)"
fi

SESSION_FILE="$STATE/session-$SESSION_ID"
touch "$SESSION_FILE"
# 오래된 세션 상태가 쌓이지 않게 최근 20개만 남긴다.
ls -t "$STATE" 2>/dev/null | grep '^session-' | tail -n +21 | while IFS= read -r old; do
    rm -f "$STATE/$old"
done

# 통과는 누가 했든 통과다. 차단은 주체별로 따로 센다. implementer가 막혔다가 고치지 못하고 끝낸 상태라도
# 메인은 그 상태로 한 번은 막혀야 실패를 알 수 있다.
if grep -qx "PASS $FINGERPRINT" "$SESSION_FILE"; then
    trace "skip(이미 통과 $FINGERPRINT)"
    exit 0
fi
if grep -qx "BLOCK $ACTOR $FINGERPRINT" "$SESSION_FILE"; then
    trace "skip(이미 차단 $FINGERPRINT)"
    exit 0
fi

BLOCK_COUNT=$(grep -c "^BLOCK $ACTOR " "$SESSION_FILE")
if [ "$BLOCK_COUNT" -ge "$MAX_BLOCKS_PER_SESSION" ]; then
    trace "skip(차단 한도 $MAX_BLOCKS_PER_SESSION 도달)"
    warn_user "검증이 계속 실패하지만 세션 차단 한도(${MAX_BLOCKS_PER_SESSION}회)에 도달해 더 막지 않는다. 남은 실패를 확인해야 한다."
    exit 0
fi

# 루트 빌드 설정이 바뀌면 전 모듈을 한 번 검증한다. 같은 설정 상태로 이미 통과했으면 이후에는 바뀐
# 모듈만 본다. 브랜치가 길어질수록 매 턴 전 모듈을 돌리는 비용만 쌓이기 때문이다.
if [ -n "$ROOT_HASH" ] && ! grep -qx "ROOTPASS $ROOT_HASH" "$SESSION_FILE"; then
    MODULES="$ALL_MODULES"
fi
if [ "$SINGLE_MODULE" -eq 0 ] && [ -z "${MODULES// /}" ]; then
    trace "skip(대상 모듈 없음)"
    exit 0
fi

UNIT_TEST=$(config_value UNIT_TEST)
TASK_TEMPLATE=$(config_value UNIT_TEST_TASK)
if [ -z "$UNIT_TEST" ]; then
    trace "skip(UNIT_TEST 미설정)"
    exit 0
fi
# 단일 모듈이면 {tasks}는 비고, UNIT_TEST 명령을 그대로 쓴다.
TASKS=""
for m in $MODULES; do
    if [ -n "$TASK_TEMPLATE" ]; then
        TASKS="$TASKS $(substitute "$TASK_TEMPLATE" m "$m")"
    else
        TASKS="$TASKS $m"
    fi
done
TASKS="${TASKS# }"
COMMAND=$(substitute "$UNIT_TEST" tasks "$TASKS")

# 병렬 implementer나 메인과 implementer가 같은 작업 트리에서 빌드를 동시에 돌리지 않게 직렬화한다.
# mkdir는 원자적이라 잠금으로 쓸 수 있다(macOS에는 flock이 없다). 잠금을 잡은 프로세스가 죽었으면 넘겨받는다.
LOCK_DIR="$STATE/lock"
WAITED=0
while ! mkdir "$LOCK_DIR" 2>/dev/null; do
    HOLDER=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
    if [ -n "$HOLDER" ] && ! kill -0 "$HOLDER" 2>/dev/null; then
        rm -rf "$LOCK_DIR"
        continue
    fi
    if [ "$WAITED" -ge "$LOCK_TIMEOUT_SECONDS" ]; then
        trace "skip(잠금 대기 ${LOCK_TIMEOUT_SECONDS}초 초과, 보유 pid ${HOLDER:-?})"
        warn_user "다른 검증이 ${LOCK_TIMEOUT_SECONDS}초 넘게 잠금을 잡고 있어 이번 종료 검증을 건너뛰었다. 단위 테스트를 직접 확인해야 한다."
        exit 0
    fi
    sleep 2
    WAITED=$((WAITED + 2))
done
printf '%s\n' "$$" > "$LOCK_DIR/pid"
trap 'rm -rf "$LOCK_DIR"' EXIT

# 기다리는 동안 다른 검증이 같은 상태를 이미 통과시켰을 수 있다.
if grep -qx "PASS $FINGERPRINT" "$SESSION_FILE"; then
    trace "skip(대기 중 다른 검증이 통과 $FINGERPRINT)"
    exit 0
fi

# 실행 시작 시각 표식. 컴파일 실패로 테스트가 돌지 않으면 지난 실행의 결과 파일이 그대로 남아 있으므로,
# 이 표식보다 새로 쓰인 결과 파일만 읽는다.
RUN_MARKER="$STATE/run-started.$$"
touch "$RUN_MARKER"
trap 'rm -rf "$LOCK_DIR" "$RUN_MARKER"' EXIT

trace "run($EVENT $FINGERPRINT): $COMMAND"
OUTPUT=$(bash -c "$COMMAND" 2>&1)
STATUS=$?

if [ "$STATUS" -eq 0 ]; then
    printf 'PASS %s\n' "$FINGERPRINT" >> "$SESSION_FILE"
    [ -n "$ROOT_HASH" ] && printf 'ROOTPASS %s\n' "$ROOT_HASH" >> "$SESSION_FILE"
    trace "PASS $FINGERPRINT (${TASKS:-전체})"
    exit 0
fi

# 실패한 테스트 이름은 로그 형식에 기대지 않고 JUnit XML 결과에서 뽑는다.
REPORT_TEMPLATE=$(config_value TEST_REPORTS)
FAILED=""
if [ -n "$REPORT_TEMPLATE" ] && command -v python3 >/dev/null 2>&1; then
    FAILED=$(for m in ${MODULES:-.}; do
        for f in $(substitute "$REPORT_TEMPLATE" m "$m"); do
            [ -f "$f" ] && [ "$f" -nt "$RUN_MARKER" ] || continue
            python3 - "$f" <<'PYEOF'
import sys
import xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(0)
for case in root.iter('testcase'):
    problem = case.find('failure')
    if problem is None:
        problem = case.find('error')
    if problem is not None:
        message = (problem.get('message') or '').strip().splitlines()
        first = message[0][:200] if message else ''
        print(f"  {case.get('classname')} > {case.get('name')}")
        if first:
            print(f"      {first}")
PYEOF
        done
    done | head -40)
fi

printf 'BLOCK %s %s\n' "$ACTOR" "$FINGERPRINT" >> "$SESSION_FILE"
trace "BLOCK $FINGERPRINT (${TASKS:-전체})"
{
    echo "변경된 모듈의 단위 테스트가 실패해 턴을 끝낼 수 없다: $COMMAND"
    echo
    if [ -n "$FAILED" ]; then
        echo "실패한 테스트:"
        echo "$FAILED"
    else
        echo "실패한 테스트 결과가 없다. 컴파일이나 빌드 설정 단계에서 끊겼다:"
        printf '%s\n' "$OUTPUT" | grep -vE '^[[:space:]]*$' | tail -25
    fi
    echo
    echo "실패를 고치고 다시 검증한다. 원인이 이번 변경과 무관하다고 판단되면 근거를 밝힌다."
    echo "개발자가 중단이나 일시정지를 요청한 상태라면 .agents/hooks/verify-pause.sh \"<사유>\"를 실행하고 보고에 적는다."
} >&2
exit 2
