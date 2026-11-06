#!/bin/bash
# .agents/hooks/의 검증 hook(종료 검증, 편집 린트, 일시정지)을 픽스처로 검증한다.
#
# hook은 에이전트 작업을 막을 수 있는 판정 로직이라, 판정 한 줄이 조용히 무력화되면 검증이 통과한 척
# 넘어간다. 종료 검증은 임시 git 저장소와 가짜 gradlew로, 편집 린트는 실제 저장소의 Checkstyle 설정으로
# 확인한다. 실제 빌드는 Checkstyle CLI classpath를 처음 만들 때만 돈다.
#
# 픽스처 저장소에는 실제 .agents/profiles/stack.md를 복사한다. 그래서 이 테스트는 hook 스크립트와 함께
# 이 프로젝트의 verify-config 값(모듈 목록, 명령, 대상 파일 패턴)도 검증한다.
#
# 검사기 준비가 안 됐을 때 hook이 조용히 통과하는 것은 의도된 동작이다. 다만 그 상태에서 위반 케이스를
# 건너뛰면 검사기가 꺼진 채로 이 스크립트가 통과할 수 있으므로, 준비되지 않았으면 스크립트를 실패로 끝낸다.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
HOOK_DIR="$PROJECT_DIR/.agents/hooks"
STOP_HOOK="$HOOK_DIR/verify-on-stop.sh"
LINT_HOOK="$HOOK_DIR/lint-file.sh"
PAUSE_SCRIPT="$HOOK_DIR/verify-pause.sh"
CHECK_FILE="$PROJECT_DIR/config/checkstyle/check-file.sh"
PROFILE_SRC="$PROJECT_DIR/.agents/profiles/stack.md"
SETTINGS="$PROJECT_DIR/.claude/settings.json"

for tool in jq python3 git shasum; do
    command -v "$tool" >/dev/null 2>&1 || { echo "필요한 도구가 없어 테스트를 실행할 수 없다: $tool" >&2; exit 1; }
done
for f in "$STOP_HOOK" "$LINT_HOOK" "$PAUSE_SCRIPT" "$HOOK_DIR/lib/common.sh" "$CHECK_FILE" "$PROFILE_SRC" "$SETTINGS"; do
    [ -f "$f" ] || { echo "파일을 찾을 수 없다: $f" >&2; exit 1; }
done

TOTAL=0
CASE_FAILED=0
RESULT_LINES=()

record() {
    local name="$1" expected="$2" actual="$3"
    TOTAL=$((TOTAL + 1))
    if [ "$expected" = "$actual" ]; then
        RESULT_LINES+=("OK   $name — 기대[$expected] 실제[$actual]")
    else
        CASE_FAILED=$((CASE_FAILED + 1))
        RESULT_LINES+=("FAIL $name — 기대[$expected] 실제[$actual]")
    fi
}

TREE_BEFORE=$(git -C "$PROJECT_DIR" status --porcelain)
TMPROOT=$(mktemp -d "${TMPDIR:-/tmp}/test-hooks.XXXXXX")
REAL_REPO_FIXTURES=()
BACKGROUND_PIDS=()

cleanup() {
    local rc=$? f p tree_after
    for p in "${BACKGROUND_PIDS[@]:-}"; do
        [ -n "$p" ] && kill "$p" 2>/dev/null
    done
    rm -rf "$TMPROOT"
    for f in "${REAL_REPO_FIXTURES[@]:-}"; do
        [ -n "$f" ] && rm -rf "$f"
    done
    tree_after=$(git -C "$PROJECT_DIR" status --porcelain)
    if [ "$tree_after" != "$TREE_BEFORE" ]; then
        echo "작업 트리가 스크립트 실행 전후로 달라졌다. 픽스처 정리가 빠졌을 수 있다:" >&2
        diff <(echo "$TREE_BEFORE") <(echo "$tree_after") >&2 || true
        exit 1
    fi
    exit "$rc"
}
trap cleanup EXIT

# ── 종료 검증 픽스처 ─────────────────────────────────────────────────────────

# 가짜 gradlew: 받은 인자를 기록한다. .gradlew-should-fail이 있으면 인자로 받은 모듈마다 실패 결과
# XML을 쓰고 실패한다. .gradlew-compile-fail이 있으면 XML 없이 실패한다(컴파일 실패 흉내).
FAKE_GRADLEW="$TMPROOT/fake-gradlew.sh"
cat > "$FAKE_GRADLEW" <<'EOF'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
echo "$*" >> "$DIR/.gradlew-calls"
if [ -f "$DIR/.gradlew-compile-fail" ]; then
    echo "error: cannot find symbol"
    exit 1
fi
if [ -f "$DIR/.gradlew-should-fail" ]; then
    # hook은 실행 시작 표식보다 새로 쓰인 결과만 읽는다. 파일 시각 비교가 초 단위라, 실제 Gradle처럼
    # 1초 뒤에 결과를 쓴다.
    sleep 1
    for arg in "$@"; do
        case "$arg" in
            :*:test)
                m="${arg#:}"; m="${m%:test}"
                mkdir -p "$DIR/$m/build/test-results/test"
                cat > "$DIR/$m/build/test-results/test/TEST-Fixture.xml" <<XML
<testsuite name="com.example.FixtureTest" tests="1" failures="1" errors="0">
  <testcase classname="com.example.FixtureTest" name="실패하는_케이스">
    <failure message="기대 1 실제 2">trace</failure>
  </testcase>
</testsuite>
XML
                ;;
        esac
    done
    exit 1
fi
exit 0
EOF
chmod +x "$FAKE_GRADLEW"

new_repo() {
    local repo
    repo=$(mktemp -d "$TMPROOT/repo.XXXXXX")
    git -C "$repo" init -q -b main
    git -C "$repo" config user.email "test-hooks@local"
    git -C "$repo" config user.name "test-hooks"
    mkdir -p "$repo/.agents/profiles"
    cp "$PROFILE_SRC" "$repo/.agents/profiles/stack.md"
    printf '# fixture\n' > "$repo/README.md"
    cp "$FAKE_GRADLEW" "$repo/gradlew"
    printf '.gradlew-*\n*/build/\n' > "$repo/.gitignore"
    git -C "$repo" add .
    git -C "$repo" commit -q -m init
    echo "$repo"
}

calls() {
    if [ -f "$1/.gradlew-calls" ]; then wc -l < "$1/.gradlew-calls" | tr -d ' '; else echo 0; fi
}

last_call() {
    if [ -f "$1/.gradlew-calls" ]; then tail -1 "$1/.gradlew-calls"; else echo "-"; fi
}

# $1 repo $2 agent_type(빈 값이면 메인) $3 event $4 session. 종료 코드를 stdout에 낸다.
run_stop() {
    local repo="$1" agent="$2" event="$3" session="$4"
    jq -n --arg s "$session" --arg a "$agent" --arg e "$event" \
        '{session_id: $s, hook_event_name: $e} + (if $a == "" then {} else {agent_type: $a} end)' \
        | CLAUDE_PROJECT_DIR="$repo" AGENT_VERIFY_LOCK_TIMEOUT="${LOCK_TIMEOUT:-600}" bash "$STOP_HOOK" \
        > "$TMPROOT/stop-stdout.txt" 2> "$TMPROOT/stop-stderr.txt"
    echo $?
}

stop_case() {
    local name="$1" repo="$2" agent="$3" session="$4" want_exit="$5" want_calls="$6" got_exit
    got_exit=$(run_stop "$repo" "$agent" "Stop" "$session")
    record "$name" "exit=$want_exit calls=$want_calls" "exit=$got_exit calls=$(calls "$repo")"
}

# FS-1: 변경 없음, 문서만 바뀜, 모듈 밖 Java만 바뀜은 테스트를 돌리지 않는다
REPO=$(new_repo)
touch "$REPO/.gradlew-should-fail"
stop_case "종료검증: 변경이 없으면 건너뛴다" "$REPO" "" s-none 0 0
echo 'more' >> "$REPO/README.md"
stop_case "종료검증: 문서만 바뀌면 건너뛴다 (FS-1)" "$REPO" "" s-none 0 0
mkdir -p "$REPO/scripts"
echo 'class Tool { }' > "$REPO/scripts/Tool.java"
stop_case "종료검증: 모듈 밖 Java만 바뀌면 건너뛴다" "$REPO" "" s-none 0 0

# FS-2: payment-service만 바뀌면 그 모듈의 test만 돈다. 통과한 상태는 다시 돌리지 않고, 문서를 더 고쳐도 같다
REPO=$(new_repo)
mkdir -p "$REPO/payment-service/src"
echo 'class Foo { }' > "$REPO/payment-service/src/Foo.java"
stop_case "종료검증: 바뀐 모듈만 테스트하고 통과하면 끝낸다 (FS-2)" "$REPO" "" s-pass 0 1
record "종료검증: 실행한 태스크는 :payment-service:test 하나다 (FS-2)" "--console=plain :payment-service:test" "$(last_call "$REPO")"
stop_case "종료검증: 이미 통과한 상태는 다시 돌리지 않는다" "$REPO" "" s-pass 0 1
echo 'doc' >> "$REPO/README.md"
stop_case "종료검증: 통과 뒤 문서만 고치면 다시 돌리지 않는다 (FS-1)" "$REPO" "" s-pass 0 1

# FS-3: 루트 빌드 설정이 바뀌면 전 모듈을 한 번 돌리고, 통과한 뒤에는 바뀐 모듈만 돈다
REPO=$(new_repo)
echo '// root' > "$REPO/build.gradle"
stop_case "종료검증: 루트 빌드 설정 변경은 전 모듈을 돌린다 (FS-3)" "$REPO" "" s-root 0 1
record "종료검증: 루트 변경 시 6개 모듈 태스크 (FS-3)" \
    "--console=plain :payment-service:test :pg-service:test :product-service:test :user-service:test :gateway:test :eureka-server:test" \
    "$(last_call "$REPO")"
mkdir -p "$REPO/pg-service/src"
echo 'class Bar { }' > "$REPO/pg-service/src/Bar.java"
stop_case "종료검증: 같은 루트 설정으로 통과한 뒤에는 바뀐 모듈만 (FS-3)" "$REPO" "" s-root 0 2
record "종료검증: 루트 통과 뒤 태스크는 pg-service만 (FS-3)" "--console=plain :pg-service:test" "$(last_call "$REPO")"

# FS-4: 실패하면 막고 실패 테스트 이름을 전달한다. 같은 상태는 다시 막지 않고, 세션당 주체별 3회까지
REPO=$(new_repo)
mkdir -p "$REPO/payment-service/src"
touch "$REPO/.gradlew-should-fail"
echo 'class Foo { int v = 1; }' > "$REPO/payment-service/src/Foo.java"
stop_case "종료검증: 테스트가 실패하면 막는다 1/3 (FS-4)" "$REPO" "" s-block 2 1
if grep -q "com.example.FixtureTest > 실패하는_케이스" "$TMPROOT/stop-stderr.txt"; then MSG=있음; else MSG=없음; fi
record "종료검증: 차단 메시지에 실패 테스트 이름이 있다 (FS-4)" "있음" "$MSG"
stop_case "종료검증: 같은 상태로는 두 번 막지 않는다 (FS-4)" "$REPO" "" s-block 0 1
stop_case "종료검증: 차단은 주체별로 센다. implementer는 같은 상태에서도 한 번 막힌다" "$REPO" "implementer" s-block 2 2
echo 'class Foo { int v = 2; }' > "$REPO/payment-service/src/Foo.java"
stop_case "종료검증: 테스트가 실패하면 막는다 2/3" "$REPO" "" s-block 2 3
echo 'class Foo { int v = 3; }' > "$REPO/payment-service/src/Foo.java"
stop_case "종료검증: 테스트가 실패하면 막는다 3/3" "$REPO" "" s-block 2 4
echo 'class Foo { int v = 4; }' > "$REPO/payment-service/src/Foo.java"
stop_case "종료검증: 세션 차단 한도를 넘으면 경고만 하고 통과한다 (FS-4)" "$REPO" "" s-block 0 4
if jq -e '.systemMessage | test("차단 한도")' "$TMPROOT/stop-stdout.txt" >/dev/null 2>&1; then MSG=있음; else MSG=없음; fi
record "종료검증: 차단 한도 도달 경고를 보여 준다" "있음" "$MSG"

# FS-5: 컴파일 실패로 결과 XML이 새로 쓰이지 않으면 지난 XML을 읽지 않고 빌드 출력을 전달한다
REPO=$(new_repo)
mkdir -p "$REPO/payment-service/src" "$REPO/payment-service/build/test-results/test"
cat > "$REPO/payment-service/build/test-results/test/TEST-Old.xml" <<'XML'
<testsuite name="Old" tests="1" failures="1"><testcase classname="com.example.OldTest" name="지난_실패"><failure message="old"/></testcase></testsuite>
XML
touch -t 202001010000 "$REPO/payment-service/build/test-results/test/TEST-Old.xml"
touch "$REPO/.gradlew-compile-fail"
echo 'class Foo { }' > "$REPO/payment-service/src/Foo.java"
stop_case "종료검증: 컴파일 실패도 막는다 (FS-5)" "$REPO" "" s-compile 2 1
if grep -q "OldTest" "$TMPROOT/stop-stderr.txt"; then OLD=있음; else OLD=없음; fi
if grep -q "cannot find symbol" "$TMPROOT/stop-stderr.txt"; then OUT=있음; else OUT=없음; fi
record "종료검증: 지난 실행의 결과 XML을 읽지 않는다 (FS-5)" "없음" "$OLD"
record "종료검증: 빌드 출력 끝부분을 전달한다 (FS-5)" "있음" "$OUT"

# FS-7: 다른 검증이 잠금을 잡고 있으면 기다리다 시간이 지나면 경고하고 건너뛴다. 죽은 프로세스의 잠금은 넘겨받는다
REPO=$(new_repo)
mkdir -p "$REPO/payment-service/src"
echo 'class Foo { }' > "$REPO/payment-service/src/Foo.java"
STATE_DIR="$(git -C "$REPO" rev-parse --absolute-git-dir)/agent-verify"
mkdir -p "$STATE_DIR/lock"
sleep 60 &
HOLDER=$!
BACKGROUND_PIDS+=("$HOLDER")
echo "$HOLDER" > "$STATE_DIR/lock/pid"
LOCK_TIMEOUT=2 stop_case "종료검증: 살아 있는 잠금이 오래가면 건너뛴다 (FS-7)" "$REPO" "" s-lock 0 0
kill "$HOLDER" 2>/dev/null
wait "$HOLDER" 2>/dev/null
stop_case "종료검증: 죽은 프로세스의 잠금은 넘겨받아 실행한다 (FS-7)" "$REPO" "" s-lock 0 1

# FS-10: 일시정지는 지금 상태에만 적용되고 코드가 바뀌면 풀린다
REPO=$(new_repo)
mkdir -p "$REPO/payment-service/src"
touch "$REPO/.gradlew-should-fail"
echo 'class Foo { int v = 1; }' > "$REPO/payment-service/src/Foo.java"
(cd "$REPO" && CLAUDE_PROJECT_DIR="$REPO" bash "$PAUSE_SCRIPT" "실패 테스트를 먼저 쓴 상태" >/dev/null)
stop_case "종료검증: 일시정지한 상태는 검증하지 않는다 (FS-10)" "$REPO" "" s-pause 0 0
echo 'class Foo { int v = 2; }' > "$REPO/payment-service/src/Foo.java"
stop_case "종료검증: 코드가 바뀌면 일시정지가 풀려 다시 막는다 (FS-10)" "$REPO" "" s-pause 2 1

# ── 등록 설정 ────────────────────────────────────────────────────────────────

# FS-6: 종료 검증은 메인(Stop)과 implementer(SubagentStop)에만 걸린다
SUBAGENT_MATCHERS=$(jq -r '[.hooks.SubagentStop[].matcher] | join(",")' "$SETTINGS")
record "설정: SubagentStop은 implementer에만 건다 (FS-6)" "implementer" "$SUBAGENT_MATCHERS"
HOOK_COMMANDS=$(jq -r '[.hooks[][] | .hooks[].command] | unique | join(",")' "$SETTINGS")
record "설정: 등록된 hook 명령" \
    '"$CLAUDE_PROJECT_DIR"/.agents/hooks/lint-file.sh,"$CLAUDE_PROJECT_DIR"/.agents/hooks/verify-on-stop.sh' \
    "$HOOK_COMMANDS"

# ── 편집 린트 ────────────────────────────────────────────────────────────────

run_lint() {
    local project="$1" file="$2"
    jq -n --arg f "$file" '{session_id: "s-lint", tool_input: {file_path: $f}}' \
        | CLAUDE_PROJECT_DIR="$project" bash "$LINT_HOOK" \
        > "$TMPROOT/lint-stdout.txt" 2> "$TMPROOT/lint-stderr.txt"
    echo $?
}

NON_JAVA="$PROJECT_DIR/.test-hooks-fixture.txt"
REAL_REPO_FIXTURES+=("$NON_JAVA")
echo 'not java' > "$NON_JAVA"
record "편집린트: Java가 아닌 파일은 건너뛴다" "0" "$(run_lint "$PROJECT_DIR" "$NON_JAVA")"

OUTSIDE="$TMPROOT/OutsideFixture.java"
printf 'public class OutsideFixture {\n    void probe() {\n        var v = 1;\n    }\n}\n' > "$OUTSIDE"
record "편집린트: 저장소 밖 Java 파일은 건너뛴다" "0" "$(run_lint "$PROJECT_DIR" "$OUTSIDE")"

# FS-8: 린트 도구가 실패하면(종료 코드 2) 막지 않는다
REPO=$(new_repo)
mkdir -p "$REPO/config/checkstyle" "$REPO/payment-service/src"
printf '#!/bin/bash\necho "java 없음" >&2\nexit 2\n' > "$REPO/config/checkstyle/check-file.sh"
chmod +x "$REPO/config/checkstyle/check-file.sh"
echo 'class Foo { }' > "$REPO/payment-service/src/Foo.java"
record "편집린트: 린트 도구가 실패하면 막지 않는다 (FS-8)" "0" "$(run_lint "$REPO" "$REPO/payment-service/src/Foo.java")"

ensure_checkstyle_ready() {
    local cp_file="$PROJECT_DIR/build/checkstyle-cli-classpath.txt"
    local props_file="$PROJECT_DIR/build/checkstyle-cli.properties"
    if [ "$PROJECT_DIR/build.gradle" -nt "$cp_file" ] || [ ! -s "$cp_file" ] || [ ! -s "$props_file" ]; then
        echo "Checkstyle CLI classpath 준비 중 (writeCheckstyleCliClasspath)..." >&2
        (cd "$PROJECT_DIR" && ./gradlew -q writeCheckstyleCliClasspath) > "$TMPROOT/gradle-prep.log" 2>&1
    fi
    [ -s "$cp_file" ] && [ -s "$props_file" ]
}
if ! ensure_checkstyle_ready; then
    cat "$TMPROOT/gradle-prep.log" >&2 2>/dev/null || true
    echo "Checkstyle 검사기가 준비되지 않았다. 위반 판정 케이스를 건너뛰지 않고 테스트를 실패로 끝낸다." >&2
    exit 1
fi

# FS-9: 실제 Checkstyle 설정으로 위반은 막고 깨끗한 파일은 통과시킨다
FIXTURE_DIR="$PROJECT_DIR/.test-hooks-fixture"
REAL_REPO_FIXTURES+=("$FIXTURE_DIR")
mkdir -p "$FIXTURE_DIR"
cat > "$FIXTURE_DIR/CheckstyleGuardrailFixture.java" <<'EOF'
public class CheckstyleGuardrailFixture {

    void probe() {
        var value = 1;
        System.out.println(value);
    }
}
EOF
record "편집린트: 위반이 있는 Java 파일은 막는다 (FS-9)" "2" "$(run_lint "$PROJECT_DIR" "$FIXTURE_DIR/CheckstyleGuardrailFixture.java")"
if grep -q "VarKeywordUsage\|var" "$TMPROOT/lint-stderr.txt"; then MSG=있음; else MSG=없음; fi
record "편집린트: 차단 메시지에 위반 내용이 있다 (FS-9)" "있음" "$MSG"
cat > "$FIXTURE_DIR/CheckstyleCleanFixture.java" <<'EOF'
public class CheckstyleCleanFixture {

    int probe() {
        int value = 1;
        return value;
    }
}
EOF
record "편집린트: 위반이 없는 Java 파일은 통과한다 (FS-9)" "0" "$(run_lint "$PROJECT_DIR" "$FIXTURE_DIR/CheckstyleCleanFixture.java")"

# ── 결과 ─────────────────────────────────────────────────────────────────────
echo
echo "===== hook 검증 결과 ====="
for line in "${RESULT_LINES[@]}"; do
    echo "$line"
done
echo "-------------------------"
echo "총 $TOTAL 케이스, 실패 $CASE_FAILED 건"

[ "$CASE_FAILED" -eq 0 ]
