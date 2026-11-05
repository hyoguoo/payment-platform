#!/usr/bin/env bash
# 지금의 변경 상태에 한해 종료 검증(verify-on-stop.sh)을 건너뛰게 한다.
#
# 쓰는 때: 개발자가 중단·일시정지를 요청했거나, 개발자 확인을 기다리느라 의도적으로 미완성 코드
# (실패하는 테스트를 먼저 쓴 상태 등)를 남겨 둘 때. 검증 실패를 피하려는 용도로 쓰지 않는다.
#
# 표식에는 지금의 변경 지문이 들어간다. 코드가 한 줄이라도 바뀌면 지문이 달라져 표식은 자동으로 풀린다.
# 사유는 실행 기록(.git/agent-verify/trace.log)에 남고, 에이전트는 완료 보고에 일시정지 사실과 사유를 적는다.
#
# 사용: .agents/hooks/verify-pause.sh "<사유>"
#       .agents/hooks/verify-pause.sh --clear   표식을 지운다
#       .agents/hooks/verify-pause.sh --status  표식과 현재 지문을 보여 준다

set -uo pipefail

HOOK_NAME=pause
ACTOR="${AGENT_VERIFY_ACTOR:-main}"

# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

[ -n "$PROJECT_DIR" ] || { echo "git 저장소가 아니다" >&2; exit 1; }
cd "$PROJECT_DIR" || exit 1
STATE=$(state_dir) || { echo "git 디렉터리를 찾지 못했다" >&2; exit 1; }
PAUSE_FILE="$STATE/pause"

current_fingerprint() {
    local base changed
    base=$(verify_base)
    [ -n "$base" ] || return 1
    changed=$(changed_relevant_files "$base")
    [ -n "$changed" ] || return 1
    fingerprint "$base" "$changed"
}

case "${1:-}" in
    "")
        echo "사유가 필요하다: verify-pause.sh \"<사유>\"" >&2
        exit 1
        ;;
    --clear)
        rm -f "$PAUSE_FILE"
        trace "일시정지 수동 해제"
        echo "일시정지 표식을 지웠다."
        exit 0
        ;;
    --status)
        if [ -f "$PAUSE_FILE" ]; then
            echo "표식: $(cat "$PAUSE_FILE")"
        else
            echo "표식: 없음"
        fi
        echo "현재 지문: $(current_fingerprint || echo '검증 대상 변경 없음')"
        exit 0
        ;;
esac

REASON="$*"
FP=$(current_fingerprint) || {
    echo "검증 대상 변경이 없어 일시정지할 것이 없다."
    exit 0
}
printf '%s %s\n' "$FP" "$REASON" > "$PAUSE_FILE"
trace "일시정지 설정 $FP: $REASON"
echo "현재 변경 상태($FP)의 종료 검증을 건너뛴다. 코드가 바뀌면 자동으로 풀린다."
echo "사유: $REASON"
