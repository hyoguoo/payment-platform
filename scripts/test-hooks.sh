#!/bin/bash
# .claude/hooks/*.sh (턴 종료 검증 훅 / 편집 시점 checkstyle 훅) 자체를 픽스처로 검증한다.
#
# 훅은 에이전트 작업을 막을 수도 있는 판정 로직이라, 그 판정 한 줄이 조용히 무력화되면
# 아무도 알아채지 못한 채 검증이 통과한 척 넘어간다. 이 스크립트는 격리된 픽스처(임시 git
# 저장소 + 가짜 gradlew, 실제 저장소 안의 임시 .java 파일)로 훅을 직접 호출해 종료 코드를
# 확인한다 — 실제 코드 변경이나 실제 빌드는 필요하지 않다.
#
# 주의: 훅이 "검사기 준비가 안 됐으면 조용히 넘어간다(종료 0)"는 동작은 의도된 것이라
# 이 스크립트가 바꾸지 않는다. 다만 그 상태에서 위반 케이스를 그냥 건너뛰면 검사기가
# 꺼진 채로도 이 스크립트가 초록불을 낼 수 있으므로, 위반 케이스 실행 전 검사기 준비
# 상태를 먼저 확인하고 준비되지 않았으면 건너뛰지 않고 이 스크립트를 실패로 끝낸다.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TURN_HOOK="$PROJECT_DIR/.claude/hooks/verify-before-stop.sh"
EDIT_HOOK="$PROJECT_DIR/.claude/hooks/checkstyle-file.sh"

for tool in jq python3 git; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "필요한 도구가 없어 테스트를 실행할 수 없다: $tool" >&2
        exit 1
    fi
done

[ -f "$TURN_HOOK" ] || { echo "훅을 찾을 수 없다: $TURN_HOOK" >&2; exit 1; }
[ -f "$EDIT_HOOK" ] || { echo "훅을 찾을 수 없다: $EDIT_HOOK" >&2; exit 1; }

TOTAL=0
CASE_FAILED=0
declare -a RESULT_LINES=()

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

fail_hard() {
    echo "$1" >&2
    exit 1
}

# ── 작업 트리 원상복구 확인 준비 ────────────────────────────────────────────
TREE_BEFORE=$(git -C "$PROJECT_DIR" status --porcelain)

TMPROOT=$(mktemp -d "${TMPDIR:-/tmp}/test-hooks.XXXXXX")
declare -a REAL_REPO_FIXTURES=()

cleanup() {
    local rc=$?
    rm -rf "$TMPROOT"
    local f
    for f in "${REAL_REPO_FIXTURES[@]:-}"; do
        [ -n "$f" ] && rm -rf "$f"
    done
    local tree_after
    tree_after=$(git -C "$PROJECT_DIR" status --porcelain)
    if [ "$tree_after" != "$TREE_BEFORE" ]; then
        echo "작업 트리가 스크립트 실행 전후로 달라졌다 — 픽스처 정리가 빠졌을 수 있다:" >&2
        diff <(echo "$TREE_BEFORE") <(echo "$tree_after") >&2 || true
        exit 1
    fi
    exit "$rc"
}
trap cleanup EXIT

# ── 턴 종료 훅(verify-before-stop.sh) 픽스처 ────────────────────────────────

FAKE_GRADLEW="$TMPROOT/fake-gradlew.sh"
cat > "$FAKE_GRADLEW" <<'EOF'
#!/bin/bash
# 임시 저장소 안에서만 쓰는 가짜 gradlew — 실제 빌드 없이 통과/실패를 지시받는다.
DIR="$(cd "$(dirname "$0")" && pwd)"
echo call >> "$DIR/.gradlew-calls"
if [ -f "$DIR/.gradlew-should-fail" ]; then
    exit 1
fi
exit 0
EOF
chmod +x "$FAKE_GRADLEW"

new_turn_repo() {
    local repo
    repo=$(mktemp -d "$TMPROOT/repo.XXXXXX")
    git -C "$repo" init -q
    git -C "$repo" config user.email "test-hooks@local"
    git -C "$repo" config user.name "test-hooks"
    printf '# fixture\n' > "$repo/README.md"
    cp "$FAKE_GRADLEW" "$repo/gradlew"
    chmod +x "$repo/gradlew"
    git -C "$repo" add README.md gradlew
    git -C "$repo" commit -q -m init
    echo "$repo"
}

gradlew_calls() {
    local repo="$1"
    if [ -f "$repo/.gradlew-calls" ]; then
        wc -l < "$repo/.gradlew-calls" | tr -d ' '
    else
        echo 0
    fi
}

# $1 repo $2 agent_type $3 event $4 session -> 종료 코드를 stdout 에 낸다
run_turn_hook() {
    local repo="$1" agent_type="$2" event="$3" session="$4"
    local payload rc
    payload=$(jq -n --arg s "$session" --arg a "$agent_type" --arg e "$event" \
        '{session_id: $s, agent_type: $a, hook_event_name: $e}')
    printf '%s' "$payload" \
        | CLAUDE_PROJECT_DIR="$repo" bash "$TURN_HOOK" \
        > "$TMPROOT/last-turn-stdout.txt" 2> "$TMPROOT/last-turn-stderr.txt"
    rc=$?
    echo "$rc"
}

check_turn_case() {
    local name="$1" repo="$2" agent="$3" event="$4" session="$5" want_exit="$6" want_calls="$7"
    local got_exit got_calls
    got_exit=$(run_turn_hook "$repo" "$agent" "$event" "$session")
    got_calls=$(gradlew_calls "$repo")
    record "$name" "exit=$want_exit calls=$want_calls" "exit=$got_exit calls=$got_calls"
}

# 케이스 1: 읽기 전용 에이전트 종류면 건너뛴다 — 가짜 빌드는 실패로 지시해 두어,
# 이 skip 이 무력화되면 exit 이 2로 바뀌는 것으로 잡히게 한다.
REPO=$(new_turn_repo)
mkdir -p "$REPO/payment-service"
echo 'class Foo { }' > "$REPO/payment-service/Foo.java"
touch "$REPO/.gradlew-should-fail"
check_turn_case "턴종료훅: 읽기 전용 에이전트는 건너뛴다" "$REPO" "reviewer" "SubagentStop" "sess-readonly" 0 0

# 케이스 2: 종류가 비어 있는 SubagentStop 은 건너뛴다 (중복 이벤트)
REPO=$(new_turn_repo)
mkdir -p "$REPO/payment-service"
echo 'class Foo { }' > "$REPO/payment-service/Foo.java"
touch "$REPO/.gradlew-should-fail"
check_turn_case "턴종료훅: 종류 미상 SubagentStop 은 건너뛴다" "$REPO" "" "SubagentStop" "sess-emptytype" 0 0

# 케이스 3: 변경이 없으면 건너뛴다
REPO=$(new_turn_repo)
touch "$REPO/.gradlew-should-fail"
check_turn_case "턴종료훅: 변경 없으면 건너뛴다" "$REPO" "" "Stop" "sess-nochange" 0 0

# 케이스 4: 문서만 바뀌면 건너뛴다 (검증 대상 확장자 필터 밖)
REPO=$(new_turn_repo)
echo 'extra line' >> "$REPO/README.md"
touch "$REPO/.gradlew-should-fail"
check_turn_case "턴종료훅: 문서만 바뀌면 건너뛴다" "$REPO" "" "Stop" "sess-doconly" 0 0

# 케이스 5~9: 코드 변경 -> 차단 -> 같은 상태 재판정 skip -> 세션 차단 한도까지 누적한
# 흐름을 한 저장소·한 세션으로 이어서 검증한다 (MAX_BLOCKS_PER_SESSION=3).
REPO=$(new_turn_repo)
mkdir -p "$REPO/payment-service"
touch "$REPO/.gradlew-should-fail"
SESSION="sess-blockflow"

echo 'class Foo { int v = 1; }' > "$REPO/payment-service/Foo.java"
check_turn_case "턴종료훅: 코드 변경 + 빌드 실패면 막는다 (1/3)" \
    "$REPO" "" "Stop" "$SESSION" 2 1

check_turn_case "턴종료훅: 같은 변경 상태는 재판정하지 않는다" \
    "$REPO" "" "Stop" "$SESSION" 0 1

echo 'class Foo { int v = 2; }' > "$REPO/payment-service/Foo.java"
check_turn_case "턴종료훅: 코드 변경 + 빌드 실패면 막는다 (2/3)" \
    "$REPO" "" "Stop" "$SESSION" 2 2

echo 'class Foo { int v = 3; }' > "$REPO/payment-service/Foo.java"
check_turn_case "턴종료훅: 코드 변경 + 빌드 실패면 막는다 (3/3)" \
    "$REPO" "" "Stop" "$SESSION" 2 3

echo 'class Foo { int v = 4; }' > "$REPO/payment-service/Foo.java"
check_turn_case "턴종료훅: 세션 차단 한도를 넘으면 더 막지 않는다" \
    "$REPO" "" "Stop" "$SESSION" 0 3

# ── 편집 시점 훅(checkstyle-file.sh) 픽스처 ─────────────────────────────────
# 실제 저장소의 checkstyle 설정을 그대로 써야 하므로 임시 git 저장소가 아니라
# 실제 PROJECT_DIR 위에서 확인한다. 생성한 픽스처 파일은 스크립트 종료 시 지운다.

run_edit_hook() {
    local file_path="$1"
    local payload rc
    payload=$(jq -n --arg f "$file_path" '{tool_input: {file_path: $f}}')
    printf '%s' "$payload" \
        | CLAUDE_PROJECT_DIR="$PROJECT_DIR" bash "$EDIT_HOOK" \
        > "$TMPROOT/last-edit-stdout.txt" 2> "$TMPROOT/last-edit-stderr.txt"
    rc=$?
    echo "$rc"
}

# 케이스: 대상이 아닌 파일(비 .java)은 건너뛴다
NON_JAVA_FIXTURE="$PROJECT_DIR/.test-hooks-fixture.txt"
REAL_REPO_FIXTURES+=("$NON_JAVA_FIXTURE")
echo 'not java' > "$NON_JAVA_FIXTURE"
EXIT_NONJAVA=$(run_edit_hook "$NON_JAVA_FIXTURE")
record "편집훅: 비-java 파일은 건너뛴다" "0" "$EXIT_NONJAVA"
rm -f "$NON_JAVA_FIXTURE"

# 케이스: 저장소 밖의 .java 파일은 건너뛴다
OUTSIDE_FIXTURE="$TMPROOT/OutsideFixture.java"
cat > "$OUTSIDE_FIXTURE" <<'EOF'
public class OutsideFixture {
}
EOF
EXIT_OUTSIDE=$(run_edit_hook "$OUTSIDE_FIXTURE")
record "편집훅: 저장소 밖 java 파일은 건너뛴다" "0" "$EXIT_OUTSIDE"

# 케이스: 위반이 있는 .java 픽스처 -> 검사기 준비 상태를 먼저 확인한다.
# 준비되지 않았으면 이 케이스를 건너뛰지 않고 스크립트 전체를 실패로 끝낸다 —
# 그렇지 않으면 검사기가 꺼진 채로도 이 스크립트가 초록불을 낼 수 있다.
ensure_checkstyle_ready() {
    local cp_file="$PROJECT_DIR/build/checkstyle-cli-classpath.txt"
    local props_file="$PROJECT_DIR/build/checkstyle-cli.properties"
    if [ "$PROJECT_DIR/build.gradle" -nt "$cp_file" ] || [ ! -s "$cp_file" ] || [ ! -s "$props_file" ]; then
        echo "체크스타일 CLI classpath 준비 중 (writeCheckstyleCliClasspath)..." >&2
        (cd "$PROJECT_DIR" && ./gradlew -q writeCheckstyleCliClasspath) \
            > "$TMPROOT/gradle-prep.log" 2>&1
    fi
    if [ ! -s "$cp_file" ] || [ ! -s "$props_file" ]; then
        return 1
    fi
    return 0
}

if ! ensure_checkstyle_ready; then
    cat "$TMPROOT/gradle-prep.log" >&2 2>/dev/null || true
    fail_hard "체크스타일 검사기가 준비되지 않았다 — 위반 판정 케이스를 건너뛰지 않고 테스트를 실패로 끝낸다."
fi

VIOLATION_DIR="$PROJECT_DIR/.test-hooks-fixture"
VIOLATION_FIXTURE="$VIOLATION_DIR/CheckstyleGuardrailFixture.java"
REAL_REPO_FIXTURES+=("$VIOLATION_DIR")
mkdir -p "$VIOLATION_DIR"
cat > "$VIOLATION_FIXTURE" <<'EOF'
public class CheckstyleGuardrailFixture {

    void probe() {
        var value = 1;
        System.out.println(value);
    }
}
EOF
EXIT_VIOLATION=$(run_edit_hook "$VIOLATION_FIXTURE")
record "편집훅: 위반 있는 .java 는 종료 2 로 막는다" "2" "$EXIT_VIOLATION"
rm -rf "$VIOLATION_DIR"
REAL_REPO_FIXTURES=()

# ── 결과 출력 ────────────────────────────────────────────────────────────
echo
echo "===== 훅 가드레일 검증 결과 ====="
for line in "${RESULT_LINES[@]}"; do
    echo "$line"
done
echo "---------------------------------"
echo "총 $TOTAL 케이스, 실패 $CASE_FAILED 건"

[ "$CASE_FAILED" -eq 0 ]
