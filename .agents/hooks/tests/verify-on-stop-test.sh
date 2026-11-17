#!/usr/bin/env bash
# 실제 hook을 로컬 Git 복제본에서 실행한다. 원본 저장소·커밋·검증 상태는 건드리지 않는다.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HOOK="$ROOT/.agents/hooks/verify-on-stop.sh"
TEST_DIR=$(mktemp -d "${TMPDIR:-/tmp}/agent-verify-test.XXXXXX")
trap 'rm -rf "$TEST_DIR"' EXIT
PASSED=0
FAILED=0

fail() { printf '  %s\n' "$1" >&2; exit 1; }
assert_equal() { [ "$1" = "$2" ] || fail "$3: expected=$1 actual=$2"; }

new_fixture() {
    FIXTURE=$(mktemp -d "$TEST_DIR/case.XXXXXX")
    git clone --quiet --no-hardlinks --branch main "$ROOT" "$FIXTURE"
    cat > "$FIXTURE/profile.md" <<'PROFILE'
```verify-config
BASE_BRANCH=main
MODULES=
VERIFY_PATTERN=\.java$
UNIT_TEST=bash fixture-command
TEST_REPORTS=
```
PROFILE
    cat > "$FIXTURE/fixture-command" <<'COMMAND'
printf 'run\n' >> "$CLAUDE_PROJECT_DIR/test-runs"
[ -f "$CLAUDE_PROJECT_DIR/pass-marker" ] && exit 0
echo FIXTURE_FAILURE >&2
exit 1
COMMAND
    printf 'class Fixture {}\n' > "$FIXTURE/Fixture.java"
    SESSION=test
    ACTOR=main
}

run_hook() {
    if jq -n --arg session "$SESSION" --arg actor "$ACTOR" \
        '{session_id:$session,hook_event_name:(if $actor == "main" then "Stop" else "SubagentStop" end),agent_type:$actor}' \
        | CLAUDE_PROJECT_DIR="$FIXTURE" AGENT_VERIFY_PROFILE="$FIXTURE/profile.md" \
          bash "$HOOK" > "$FIXTURE/stdout" 2> "$FIXTURE/stderr"; then
        STATUS=0
    else
        STATUS=$?
    fi
}

assert_runs() {
    local actual=0
    [ ! -f "$FIXTURE/test-runs" ] || actual=$(wc -l < "$FIXTURE/test-runs" | tr -d ' ')
    assert_equal "$1" "$actual" '검증 명령 실행 횟수'
}

assert_failure_warning() {
    jq -e '.systemMessage | type == "string" and length > 0' "$FIXTURE/stdout" >/dev/null \
        || fail '종료 허용 시 실패 경고가 전달되지 않았다'
    jq -r '.systemMessage' "$FIXTURE/stdout" | grep -q '실패' || fail '앞선 실패가 경고에 없다'
    jq -r '.systemMessage' "$FIXTURE/stdout" | grep -q '통과' || fail '통과 여부가 경고에 없다'
}

success_is_reused() {
    new_fixture
    touch "$FIXTURE/pass-marker"
    run_hook
    assert_equal 0 "$STATUS" '정상 통과'
    run_hook
    assert_equal 0 "$STATUS" '통과 결과 재사용'
    assert_runs 1
    [ ! -s "$FIXTURE/stdout" ] || fail '정상 통과를 실패로 경고했다'
}

first_failure_blocks() {
    new_fixture
    run_hook
    assert_equal 2 "$STATUS" '첫 실패의 종료 차단'
    assert_runs 1
    grep -q FIXTURE_FAILURE "$FIXTURE/stderr" || fail '이번 실행의 실패 원인이 전달되지 않았다'
    grep -q '^BLOCK main ' "$FIXTURE/.git/agent-verify"/session-* || fail '실패 기록이 없다'
}

repeated_failure_warns_without_rerun() {
    new_fixture
    run_hook
    assert_equal 2 "$STATUS" '첫 실패'
    run_hook
    assert_equal 0 "$STATUS" '같은 실패의 중복 차단 방지'
    assert_runs 1
    assert_failure_warning
    assert_equal 1 "$(grep -c '^BLOCK main ' "$FIXTURE/.git/agent-verify"/session-*)" '차단 기록 중복 방지'
}

changed_state_is_checked_again() {
    new_fixture
    run_hook
    printf 'class ChangedFixture {}\n' > "$FIXTURE/Fixture.java"
    run_hook
    assert_equal 2 "$STATUS" '변경 후 새 검증 실패'
    assert_runs 2
}

block_cap_still_allows_stop_with_warning() {
    new_fixture
    local expected_runs=0
    for ACTOR in main implementer; do
        for version in 1 2 3; do
            printf 'class Fixture_%s_%s {}\n' "$ACTOR" "$version" > "$FIXTURE/Fixture.java"
            run_hook
            assert_equal 2 "$STATUS" '주체별 한도 전 실패 차단'
        done
        expected_runs=$((expected_runs + 3))
        printf 'class FourthFixture_%s {}\n' "$ACTOR" > "$FIXTURE/Fixture.java"
        run_hook
        assert_equal 0 "$STATUS" '주체별 차단 한도 후 종료 허용'
        assert_runs "$expected_runs"
        jq -e '.systemMessage | type == "string" and length > 0' "$FIXTURE/stdout" >/dev/null \
            || fail '차단 한도 경고가 없다'
    done
}

actors_keep_separate_failure_records() {
    new_fixture
    run_hook
    assert_equal 2 "$STATUS" '메인 첫 실패'
    ACTOR=implementer
    run_hook
    assert_equal 2 "$STATUS" 'implementer 첫 실패'
    for ACTOR in main implementer; do
        run_hook
        assert_equal 0 "$STATUS" '주체별 동일 실패 종료 허용'
        assert_failure_warning
    done
    assert_runs 2
}

no_code_change_skips_tests() {
    new_fixture
    rm "$FIXTURE/Fixture.java"
    run_hook
    assert_equal 0 "$STATUS" '검증 대상 변경 없음'
    assert_runs 0
}

for test_case in success_is_reused first_failure_blocks repeated_failure_warns_without_rerun \
    changed_state_is_checked_again block_cap_still_allows_stop_with_warning \
    actors_keep_separate_failure_records no_code_change_skips_tests; do
    (set -e; "$test_case")
    result=$?
    if [ "$result" -eq 0 ]; then
        printf 'PASS %s\n' "$test_case"
        PASSED=$((PASSED + 1))
    else
        printf 'FAIL %s\n' "$test_case"
        FAILED=$((FAILED + 1))
    fi
done
printf '실행 %s · 통과 %s · 실패 %s\n' "$((PASSED + FAILED))" "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
