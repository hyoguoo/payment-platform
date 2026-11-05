# 검증 hook 공통 함수. verify-on-stop.sh, verify-pause.sh, lint-file.sh가 source한다.
#
# 스택에 묶이는 값(모듈, 명령, 대상 확장자)은 여기 두지 않는다. 모두 .agents/profiles/stack.md의
# verify-config 블록에서 읽는다. 스택을 바꿀 때 이 디렉터리는 고치지 않는다.
#
# macOS 기본 bash(3.2)에서도 돌아야 하므로 연관 배열, mapfile 같은 bash 4 기능을 쓰지 않는다.

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
PROFILE="${AGENT_VERIFY_PROFILE:-$PROJECT_DIR/.agents/profiles/stack.md}"

# stack.md의 ```verify-config 블록에서 KEY=값 한 줄을 읽는다. 값은 가공하지 않고 그대로 돌려준다.
# 블록이나 키가 없으면 빈 문자열이다.
config_value() {
    [ -f "$PROFILE" ] || return 0
    awk -v key="$1" '
        /^```verify-config[[:space:]]*$/ { inside = 1; next }
        inside && /^```/ { exit }
        inside {
            i = index($0, "=")
            if (i > 0 && substr($0, 1, i - 1) == key) { print substr($0, i + 1); exit }
        }
    ' "$PROFILE"
}

has_config_block() {
    [ -f "$PROFILE" ] && grep -qE '^```verify-config[[:space:]]*$' "$PROFILE"
}

# 판정 상태, 일시정지 표식, 잠금, 실행 기록은 git 디렉터리 아래에 둔다. 커밋 대상이 되지 않고,
# worktree마다 따로 관리된다.
state_dir() {
    local git_dir
    git_dir=$(git -C "$PROJECT_DIR" rev-parse --absolute-git-dir 2>/dev/null) || return 1
    mkdir -p "$git_dir/agent-verify" && printf '%s\n' "$git_dir/agent-verify"
}

# 실행 기록. "hook이 안 돌았다"와 "돌았는데 통과했다"를 구분하려고 판정마다 한 줄을 남긴다.
# 여러 세션의 기록이 한 파일에 섞이므로 세션 ID 앞 8자리를 함께 적는다. hook은 입력의 session_id를
# SESSION_ID에 넣고, 직접 실행하는 스크립트(verify-pause.sh)는 Claude Code가 주는 환경 변수를 쓴다.
trace() {
    local dir session
    dir=$(state_dir) || return 0
    session="${SESSION_ID:-${CLAUDE_CODE_SESSION_ID:-}}"
    session="${session:0:8}"
    printf '%s %s %s %s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "${session:--}" "${HOOK_NAME:-?}" \
        "${ACTOR:-main}" "$1" >> "$dir/trace.log"
    # 무한히 자라지 않게 최근 기록만 남긴다. 회전 중 다른 프로세스의 한 줄이 유실될 수 있지만
    # 판정에는 영향이 없다.
    if [ "$(wc -l < "$dir/trace.log")" -gt 400 ]; then
        tail -200 "$dir/trace.log" > "$dir/trace.log.tmp" && mv "$dir/trace.log.tmp" "$dir/trace.log"
    fi
}

# 검증 기준선: 기준 브랜치와의 분기점. 커밋하는 순간 작업 트리가 깨끗해져 검증이 통째로 건너뛰어지지
# 않게, 작업 트리가 아니라 분기점을 기준으로 삼는다. 기준 브랜치가 없으면 HEAD로 물러선다.
verify_base() {
    local branch
    branch=$(config_value BASE_BRANCH)
    branch="${branch:-main}"
    git -C "$PROJECT_DIR" merge-base HEAD "$branch" 2>/dev/null \
        || git -C "$PROJECT_DIR" rev-parse HEAD 2>/dev/null
}

# 기준선 이후 바뀐 파일(추적 변경 + 미추적 새 파일) 중 VERIFY_PATTERN에 맞는 것. 한 줄에 하나.
changed_relevant_files() {
    local base="$1" pattern
    pattern=$(config_value VERIFY_PATTERN)
    [ -n "$pattern" ] || return 0
    {
        git -C "$PROJECT_DIR" diff --name-only "$base" 2>/dev/null
        git -C "$PROJECT_DIR" ls-files --others --exclude-standard 2>/dev/null
    } | grep -E -- "$pattern" | sort -u
}

# 변경 지문. 검증 대상 파일의 diff와 미추적 파일의 내용 해시로 만든다. 문서만 고친 턴에는 지문이
# 바뀌지 않으므로 같은 코드 상태를 다시 검증하지 않는다.
fingerprint() {
    local base="$1" files="$2"
    {
        printf '%s\n' "$files" | tr '\n' '\0' | (cd "$PROJECT_DIR" && xargs -0 git diff "$base" -- 2>/dev/null)
        git -C "$PROJECT_DIR" ls-files --others --exclude-standard 2>/dev/null | while IFS= read -r f; do
            if printf '%s\n' "$files" | grep -qxF -- "$f"; then
                (cd "$PROJECT_DIR" && shasum -a 256 -- "$f" 2>/dev/null)
            fi
        done
    } | shasum -a 256 | cut -c1-16
}

# 바뀐 파일을 MODULES의 경로와 대조해 대상 모듈을 고른다. 공백 구분 목록으로 돌려준다.
modules_for_files() {
    local files="$1" modules m
    modules=$(config_value MODULES)
    for m in $modules; do
        if printf '%s\n' "$files" | grep -q -- "^$m/"; then
            printf '%s ' "$m"
        fi
    done
}

# 루트 빌드 설정(ROOT_FILES 접두사)에 해당하는 변경 파일 목록.
root_files_changed() {
    local files="$1" roots r f
    roots=$(config_value ROOT_FILES)
    printf '%s\n' "$files" | while IFS= read -r f; do
        for r in $roots; do
            case "$f" in
                "$r"*) printf '%s\n' "$f"; break ;;
            esac
        done
    done | sort -u
}

# 파일 목록의 내용 해시. 삭제된 파일은 경로를 대신 넣어 서로 다른 삭제 상태가 같은 지문으로 모이지 않게 한다.
content_hash() {
    printf '%s\n' "$1" | while IFS= read -r f; do
        [ -n "$f" ] || continue
        if [ -f "$PROJECT_DIR/$f" ]; then
            (cd "$PROJECT_DIR" && shasum -a 256 -- "$f")
        else
            printf 'DELETED %s\n' "$f"
        fi
    done | shasum -a 256 | cut -c1-16
}

# 문자열 안의 {key}를 값으로 바꾼다. 값에 sed 특수문자가 있어도 깨지지 않게 awk로 처리한다.
substitute() {
    awk -v tmpl="$1" -v key="{$2}" -v val="$3" 'BEGIN {
        out = ""
        while ((i = index(tmpl, key)) > 0) {
            out = out substr(tmpl, 1, i - 1) val
            tmpl = substr(tmpl, i + length(key))
        }
        print out tmpl
    }'
}

# Claude Code에 경고를 보여 준다. exit 0과 함께 쓰며 작업을 막지 않는다.
warn_user() {
    jq -n --arg msg "$1" '{systemMessage: $msg}'
}
