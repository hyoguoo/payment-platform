#!/bin/bash
# check-file.sh — 코틀린 파일 하나를 ktlint 로 검사한다(편집 직후 hook 용, config/lint-file.sh 가 부른다).
#
# Gradle ktlintCheck 는 모듈 전체를 돌아 편집 직후 피드백으로 쓰기엔 느리므로 ktlint CLI 를 파일 하나에만 직접 물린다.
# CLI classpath 는 product-service 의 writeKtlintCliClasspath 태스크가 Gradle 이 해석한 그대로 파일로 떨군다
# (Checkstyle 의 writeCheckstyleCliClasspath 와 같은 방식). 규칙 설정은 저장소 루트 .editorconfig 를 ktlint 가 읽는다.
#
# 종료 코드: 0 위반 없음, 1 위반 있음, 2 검사기 실행 실패(hook 은 이 경우 막지 않는다).
set -uo pipefail

FILE="${1:?검사할 파일 경로가 필요하다}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_FILE="$ROOT/product-service/build.gradle.kts"
CLASSPATH_FILE="$ROOT/product-service/build/ktlint-cli-classpath.txt"

if [ "$BUILD_FILE" -nt "$CLASSPATH_FILE" ]; then
    rm -f "$CLASSPATH_FILE"
fi
if [ ! -s "$CLASSPATH_FILE" ]; then
    (cd "$ROOT" && ./gradlew -q :product-service:writeKtlintCliClasspath >/dev/null 2>&1) || {
        echo "ktlint CLI classpath를 만들지 못했다" >&2
        exit 2
    }
fi

OUTPUT=$(cd "$ROOT" && java -cp "$(cat "$CLASSPATH_FILE")" com.pinterest.ktlint.Main --relative "$FILE" 2>&1)
STATUS=$?

# 위반 줄은 "경로:줄:열: 메시지 (규칙)" 형식이다.
VIOLATIONS=$(printf '%s\n' "$OUTPUT" | grep -E '^[^[:space:]].*:[0-9]+:[0-9]+: ' | sed "s|$ROOT/||")
if [ -n "$VIOLATIONS" ]; then
    printf '%s\n' "$VIOLATIONS"
    exit 1
fi
if [ "$STATUS" -eq 0 ]; then
    exit 0
fi
printf '%s\n' "$OUTPUT" | tail -5 >&2
exit 2
