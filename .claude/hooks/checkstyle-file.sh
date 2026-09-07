#!/bin/bash
# PostToolUse — 방금 편집한 .java 파일 하나만 Checkstyle 로 검사한다.
#
# Gradle 태스크(`checkstyleMain`)는 모듈 전체를 돌아 편집 직후 피드백으로 쓰기에 느리다.
# Checkstyle CLI 를 파일 하나에만 직접 물려 1~2초 안에 끝낸다.
#
# 검사 결과는 stderr 로 내보내고 exit 2 로 종료한다 — 편집 자체는 이미 끝났으므로 되돌리지는
# 못하지만, 에이전트가 그 파일의 맥락을 아직 들고 있는 시점에 무엇을 고쳐야 하는지 전달된다.

set -uo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -n "$PROJECT_DIR" ] || exit 0

FILE_PATH=$(jq -r '.tool_input.file_path // empty')
[ -n "$FILE_PATH" ] || exit 0
[[ "$FILE_PATH" == *.java ]] || exit 0
# 상대 경로로 올 수 있으므로 절대 경로로 맞춘다.
[[ "$FILE_PATH" == /* ]] || FILE_PATH="$PROJECT_DIR/$FILE_PATH"
[ -f "$FILE_PATH" ] || exit 0
# 이 저장소의 규칙이므로 저장소 밖 파일에는 적용하지 않는다.
[[ "$FILE_PATH" == "$PROJECT_DIR"/* ]] || exit 0

CONFIG="$PROJECT_DIR/config/checkstyle/checkstyle.xml"
[ -f "$CONFIG" ] || exit 0

# Checkstyle 본체 jar 만으로는 CLI 가 뜨지 않고(picocli 등 전이 의존 필요) 그 목록을 여기서
# 조립하면 버전이 바뀔 때마다 깨진다. Gradle 이 해석해 둔 classpath 를 그대로 읽는다.
CLASSPATH_FILE="$PROJECT_DIR/build/checkstyle-cli-classpath.txt"
PROPERTIES_FILE="$PROJECT_DIR/build/checkstyle-cli.properties"
# build.gradle 이 더 최신이면 checkstyle 버전이 바뀌었을 수 있다 — 캐시를 버리고 다시 만든다.
if [ "$PROJECT_DIR/build.gradle" -nt "$CLASSPATH_FILE" ]; then
    rm -f "$CLASSPATH_FILE" "$PROPERTIES_FILE"
fi
if [ ! -s "$CLASSPATH_FILE" ] || [ ! -s "$PROPERTIES_FILE" ]; then
    # 최초 1회만 생성한다. 실패하면 검사를 건너뛴다 — 훅이 작업을 막는 원인이 되면 안 된다.
    (cd "$PROJECT_DIR" && ./gradlew -q writeCheckstyleCliClasspath >/dev/null 2>&1) || exit 0
    [ -s "$CLASSPATH_FILE" ] && [ -s "$PROPERTIES_FILE" ] || exit 0
fi

OUTPUT=$(java -cp "$(cat "$CLASSPATH_FILE")" com.puppycrawl.tools.checkstyle.Main \
    -c "$CONFIG" -p "$PROPERTIES_FILE" "$FILE_PATH" 2>&1)
STATUS=$?

# CLI 는 위반이 있을 때만이 아니라 설정 로드 실패로도 0 이 아닌 값을 낸다. 위반 줄이
# 실제로 잡혔을 때만 막고, 그 밖의 실패는 조용히 넘긴다.
VIOLATIONS=$(echo "$OUTPUT" | grep -E "^\[(ERROR|WARN)\]" | sed "s|$PROJECT_DIR/||")

if [ $STATUS -ne 0 ] && [ -n "$VIOLATIONS" ]; then
    {
        echo "Checkstyle 위반 — ${FILE_PATH#"$PROJECT_DIR"/}"
        echo "$VIOLATIONS"
        echo
        echo "위 위반을 고친 뒤 다음 작업을 이어간다."
    } >&2
    exit 2
fi

exit 0
