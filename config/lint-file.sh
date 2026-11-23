#!/bin/bash
# lint-file.sh — 편집 직후 hook(.agents/hooks/lint-file.sh)이 파일 하나를 린트할 때 쓰는 분배 스크립트.
#
# hook 은 스택과 무관하게 stack.md 의 LINT_FILE 명령 하나만 실행하므로, 언어별 검사기 선택은 여기서 한다.
#   .java      → config/checkstyle/check-file.sh (Checkstyle)
#   .kt, .kts  → config/ktlint/check-file.sh (ktlint)
#   그 밖      → 검사하지 않는다
#
# 종료 코드는 각 검사기와 같다: 0 위반 없음, 1 위반 있음, 2 검사기 실행 실패.
set -uo pipefail

FILE="${1:?검사할 파일 경로가 필요하다}"
DIR="$(cd "$(dirname "$0")" && pwd)"

case "$FILE" in
    *.java) exec bash "$DIR/checkstyle/check-file.sh" "$FILE" ;;
    *.kt | *.kts) exec bash "$DIR/ktlint/check-file.sh" "$FILE" ;;
    *) exit 0 ;;
esac
