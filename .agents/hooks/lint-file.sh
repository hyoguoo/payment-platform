#!/usr/bin/env bash
# PostToolUse(Edit·Write) — 방금 편집한 파일 하나만 린트한다.
#
# 모듈 전체 정적 분석은 느려서 편집 직후 피드백으로 쓸 수 없다. stack.md 설정 블록의 LINT_FILE 명령을
# 파일 하나에 물려 몇 초 안에 끝낸다. 편집 자체는 이미 끝났으므로 되돌리지 못하지만, 에이전트가 그 파일의
# 맥락을 들고 있을 때 고칠 것을 전달한다.
#
# LINT_FILE 명령의 종료 코드 계약: 0 위반 없음, 1 위반 있음(stdout에 위반 줄), 그 밖은 도구 실패.
# 도구 실패는 작업을 막지 않는다. hook이 작업을 막는 원인이 되면 안 된다.

set -uo pipefail

HOOK_NAME=lint

# shellcheck source=lib/common.sh
. "$(dirname "$0")/lib/common.sh"

[ -n "$PROJECT_DIR" ] || exit 0

INPUT=$(cat)
SESSION_ID=$(printf '%s' "$INPUT" | jq -r '.session_id // empty')
ACTOR=$(printf '%s' "$INPUT" | jq -r '.agent_type // empty')
ACTOR="${ACTOR:-main}"
FILE_PATH=$(printf '%s' "$INPUT" | jq -r '.tool_input.file_path // empty')
[ -n "$FILE_PATH" ] || exit 0
case "$FILE_PATH" in
    /*) ;;
    *) FILE_PATH="$PROJECT_DIR/$FILE_PATH" ;;
esac
[ -f "$FILE_PATH" ] || exit 0
# 이 저장소의 규칙이므로 저장소 밖 파일에는 적용하지 않는다.
case "$FILE_PATH" in
    "$PROJECT_DIR"/*) ;;
    *) exit 0 ;;
esac
REL_PATH="${FILE_PATH#"$PROJECT_DIR"/}"

has_config_block || exit 0
PATTERN=$(config_value LINT_FILE_PATTERN)
COMMAND_TEMPLATE=$(config_value LINT_FILE)
[ -n "$PATTERN" ] && [ -n "$COMMAND_TEMPLATE" ] || exit 0
printf '%s\n' "$REL_PATH" | grep -qE -- "$PATTERN" || exit 0

QUOTED=$(printf '%q' "$FILE_PATH")
COMMAND=$(substitute "$COMMAND_TEMPLATE" file "$QUOTED")

cd "$PROJECT_DIR" || exit 0
OUTPUT=$(bash -c "$COMMAND" 2>&1)
STATUS=$?

case "$STATUS" in
    0)
        exit 0
        ;;
    1)
        trace "BLOCK $REL_PATH"
        {
            echo "린트 위반 — $REL_PATH"
            printf '%s\n' "$OUTPUT" | head -30
            echo
            echo "위반을 고친 뒤 다음 작업을 이어간다."
        } >&2
        exit 2
        ;;
    *)
        trace "skip(린트 도구 실패 status=$STATUS $REL_PATH): $(printf '%s' "$OUTPUT" | tail -1)"
        exit 0
        ;;
esac
