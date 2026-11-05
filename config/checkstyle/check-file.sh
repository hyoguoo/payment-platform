#!/usr/bin/env bash
# Java 파일 하나를 Checkstyle CLI로 검사한다. stack.md 설정 블록의 LINT_FILE이 이 스크립트를 가리킨다.
#
# 종료 코드 계약(.agents/hooks/lint-file.sh가 기대하는 형식):
#   0  위반 없음
#   1  위반 있음. stdout에 위반 줄을 낸다.
#   2  도구 자체 실패(설정 로드 실패, java 없음 등). hook은 이 경우 작업을 막지 않는다.
#
# Gradle checkstyleMain은 모듈 전체를 돌아 편집 직후 피드백으로 쓰기에 느리다. CLI를 파일 하나에만 물린다.
# CLI에 필요한 전이 의존(picocli 등)을 여기서 조립하면 버전이 바뀔 때마다 깨지므로 Gradle이 해석한
# classpath를 그대로 쓴다.

set -uo pipefail

FILE="${1:?검사할 파일 경로가 필요하다}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CONFIG="$ROOT/config/checkstyle/checkstyle.xml"
CLASSPATH_FILE="$ROOT/build/checkstyle-cli-classpath.txt"
# checkstyle.xml이 억제 목록 경로 같은 속성을 읽는다. 같은 Gradle 태스크가 classpath와 함께 만든다.
PROPERTIES_FILE="$ROOT/build/checkstyle-cli.properties"

# build.gradle이 더 최신이면 Checkstyle 버전이 바뀌었을 수 있다. classpath를 다시 만든다.
if [ "$ROOT/build.gradle" -nt "$CLASSPATH_FILE" ]; then
    rm -f "$CLASSPATH_FILE" "$PROPERTIES_FILE"
fi
if [ ! -s "$CLASSPATH_FILE" ] || [ ! -s "$PROPERTIES_FILE" ]; then
    (cd "$ROOT" && ./gradlew -q writeCheckstyleCliClasspath >/dev/null 2>&1) || {
        echo "Checkstyle CLI classpath를 만들지 못했다" >&2
        exit 2
    }
fi

OUTPUT=$(java -cp "$(cat "$CLASSPATH_FILE")" com.puppycrawl.tools.checkstyle.Main -c "$CONFIG" -p "$PROPERTIES_FILE" "$FILE" 2>&1)
STATUS=$?

# CLI는 위반 수를 종료 코드로 내지만 설정 로드 실패에도 0이 아닌 값을 낸다. 위반 줄로 구분한다.
VIOLATIONS=$(printf '%s\n' "$OUTPUT" | grep -E '^\[(ERROR|WARN)\]' | sed "s|$ROOT/||")
if [ -n "$VIOLATIONS" ]; then
    printf '%s\n' "$VIOLATIONS"
    exit 1
fi
if [ "$STATUS" -eq 0 ]; then
    exit 0
fi
printf '%s\n' "$OUTPUT" | tail -5 >&2
exit 2
