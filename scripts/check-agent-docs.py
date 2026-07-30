#!/usr/bin/env python3
"""check-agent-docs.py — 에이전트 지침 문서(CLAUDE.md / .claude / docs/context) 정합성 점검.

판정 3종:
  1. 참조 무결성 — 마크다운 링크 `[text](path)` 와 파일 경로 형태의 백틱 스팬(`a/b.md`)이
     실제 파일/디렉터리로 해석되는지 확인한다. 펜스(```) 코드 블록 내부, 공백 포함 문자열,
     URL, placeholder(`<...>`/`{...}`/`*`/`$`)는 검사 대상에서 제외한다.
  2. frontmatter 필수 필드 — `.claude/skills/*/SKILL.md` 는 name/description,
     `.claude/agents/*.md` 는 name/description/model/tools 가 있는지 확인한다.
  3. 체크리스트 참조 — 문서가 지정한 체크리스트 파일이 실재하고, 함께 언급한 섹션 제목이
     그 파일의 헤딩에 존재하는지 확인한다(도메인 검토자 stage 매핑 표의 3열 형식과
     "<구문> 섹션/항목" 프로즈 언급 두 가지 형태를 인식한다).

이 스크립트는 정보 제공용이다 — 판정 결과와 무관하게 항상 종료 코드 0으로 끝난다.

실행:  python3 scripts/check-agent-docs.py
"""
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

SCAN_ROOTS = [
    REPO_ROOT / "CLAUDE.md",
    REPO_ROOT / ".claude",
    REPO_ROOT / "docs" / "context",
]

# 백틱 단독 스팬(마크다운 링크가 아닌 `path` 형태)에서 경로로 인정하는 확장자.
# 웹 자산(.html/.css/.js/.ts)은 별도 blog 저장소·브라우저 URL 예시에서 반복 노출되는
# 오탐 원인이라 제외한다 — 그 경로들은 마크다운 링크([text](path)) 형태로 쓰이지 않는다.
BACKTICK_PATH_EXTENSIONS = {
    ".md", ".sh", ".py", ".java", ".yml", ".yaml", ".json", ".properties",
    ".gradle", ".toml", ".conf", ".sql", ".txt",
}

CHECKLIST_DIR = REPO_ROOT / ".claude" / "skills" / "_shared" / "checklists"
CHECKLIST_NAMES = ("discuss-ready.md", "plan-ready.md", "code-ready.md", "ship-ready.md")
SECTION_KEYWORDS = ("섹션", "항목")

# 여러 스킬 문서가 `.claude/skills/_shared/` 하위를 "_shared/..." 또는 접두사 없이
# "conventions/..."/"checklists/..." 로 줄여 쓴다 — 참조 파일 기준 상대경로로는 안 풀리는
# 통용 축약이라 별도 앵커로 추가 시도한다.
SHARED_DIR = REPO_ROOT / ".claude" / "skills" / "_shared"
SPECIAL_ANCHOR_SEGMENTS = {"conventions", "checklists", "_shared"}
TOP_LEVEL_ENTRIES = {p.name for p in REPO_ROOT.iterdir()}

LINK_RE = re.compile(r'\[([^\]]*)\]\(([^)]+)\)')
BACKTICK_RE = re.compile(r'`([^`\n]+)`')
TABLE_ROW_RE = re.compile(r'\|[^|]*\|\s*`([^`]+-ready\.md)`\s*\|\s*([^|]+?)\s*\|')
PLACEHOLDER_CHARS = set("<>{}*$:")
PHRASE_CHARS = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789- ")


def rel(path):
    return path.relative_to(REPO_ROOT).as_posix()


def iter_markdown_files():
    files = []
    for root in SCAN_ROOTS:
        if root.is_file() and root.suffix == ".md":
            files.append(root)
        elif root.is_dir():
            files.extend(sorted(root.rglob("*.md")))
    return files


def strip_fenced_code(lines):
    """펜스(```) 코드 블록 안 라인은 빈 문자열로 치환한다 — 코드 예시·커맨드는 검사 제외."""
    out = []
    in_fence = False
    for line in lines:
        if line.strip().startswith("```"):
            in_fence = not in_fence
            out.append("")
            continue
        out.append("" if in_fence else line)
    return out


def is_path_shaped(candidate):
    """백틱 스팬이 '파일 경로 형태'인지 판정한다 — 코드 인자/커맨드/URL/약어(...)/placeholder 는 제외.

    `./`, `../` 로 시작하는 스팬은 셸 커맨드 예시(`./gradlew test`, `./scripts/x.sh`)와 구분이
    안 돼 제외한다 — 이 저장소의 실제 문서 간 상대경로 참조는 전부 마크다운 링크 문법
    ([text](path))을 쓰고, 그쪽은 별도로 검사한다.
    """
    if not candidate or any(ch.isspace() for ch in candidate):
        return False
    if any(ch in PLACEHOLDER_CHARS for ch in candidate):
        return False
    if "..." in candidate:
        return False
    if candidate.startswith(("http://", "https://", "mailto:", "./", "../")):
        return False
    if "/" not in candidate:
        return False
    path_part = candidate.split("#", 1)[0]
    if not path_part:
        return False
    if path_part.endswith("/"):
        return True
    return Path(path_part).suffix in BACKTICK_PATH_EXTENSIONS


def path_candidates(path_part, base_dir):
    """경로 후보를 우선순위대로 나열한다: 레포 루트 → 참조 파일 기준 → 그 상위 →
    `.claude/skills/_shared/` 앵커(두 경로가 그 하위를 접두사 유무 섞어 축약 참조한다)."""
    if path_part.startswith("/"):
        path_part = path_part.lstrip("/")
    return [
        REPO_ROOT / path_part,
        base_dir / path_part,
        base_dir.parent / path_part,
        SHARED_DIR / path_part,
        SHARED_DIR.parent / path_part,
    ]


def resolve(path_part, base_dir):
    """마크다운 링크 대상이 실제로 존재하는지: 상대경로(./, ../)는 참조 파일 기준으로만,
    그 외는 path_candidates() 순서로 시도한다."""
    if path_part.startswith(("./", "../")):
        return (base_dir / path_part).resolve().exists()
    return any(candidate.resolve().exists() for candidate in path_candidates(path_part, base_dir))


def top_level_segment(path_part):
    stripped = path_part.lstrip("/")
    return stripped.split("/", 1)[0]


def resolve_backtick_path(path_part, base_dir):
    """백틱 전용 경로 판정: 후보 경로 중 하나라도 존재하면 통과.
    전부 실패하면 최상위 세그먼트가 실제 레포 최상위 항목/공유 앵커일 때만 '깨짐'으로
    보고한다 — 그 외(db/migration, application/port/in 같은 계층 축약 표기)는 검사
    신뢰 범위 밖이라 조용히 건너뛴다."""
    if any(candidate.resolve().exists() for candidate in path_candidates(path_part, base_dir)):
        return True, False  # (resolved, should_report_if_not_resolved)
    top = top_level_segment(path_part)
    plausible = top in TOP_LEVEL_ENTRIES or top in SPECIAL_ANCHOR_SEGMENTS
    return False, plausible


def check_references(files):
    checked = 0
    broken = []
    for path in files:
        raw_lines = path.read_text(encoding="utf-8").splitlines()
        scan_lines = strip_fenced_code(raw_lines)
        for lineno, line in enumerate(scan_lines, start=1):
            if not line:
                continue
            for _text, target in LINK_RE.findall(line):
                target = target.strip()
                if target.startswith(("#", "http://", "https://", "mailto:")):
                    continue
                path_part = target.split("#", 1)[0].strip()
                if not path_part:
                    continue
                checked += 1
                if not resolve(path_part, path.parent):
                    broken.append(f"{rel(path)}:{lineno}: 링크 대상 없음 — {target}")

            # 링크로 이미 처리한 부분(대괄호+백틱 텍스트 포함)은 백틱 재검사에서 제외한다.
            line_without_links = LINK_RE.sub(lambda m: " " * len(m.group(0)), line)
            for span in BACKTICK_RE.findall(line_without_links):
                span = span.strip()
                if not is_path_shaped(span):
                    continue
                path_part = span.split("#", 1)[0]
                resolved, plausible = resolve_backtick_path(path_part, path.parent)
                if resolved:
                    checked += 1
                elif plausible:
                    checked += 1
                    broken.append(f"{rel(path)}:{lineno}: 경로 미해석 — `{span}`")
                # else: 최상위 세그먼트가 레포/공유 앵커 어디에도 없음 — 계층 축약 표기로
                # 보고 검사 대상에서 조용히 제외한다(카운트하지 않음).
    return checked, broken


def parse_frontmatter_keys(path):
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---"):
        return None
    lines = text.splitlines()
    end = None
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            end = i
            break
    if end is None:
        return None
    keys = set()
    for line in lines[1:end]:
        m = re.match(r'^([A-Za-z_][A-Za-z0-9_]*):', line)
        if m:
            keys.add(m.group(1))
    return keys


def check_frontmatter():
    checked = 0
    issues = []

    skill_files = sorted((REPO_ROOT / ".claude" / "skills").glob("*/SKILL.md"))
    for path in skill_files:
        checked += 1
        keys = parse_frontmatter_keys(path)
        if keys is None:
            issues.append(f"{rel(path)}: frontmatter 없음")
            continue
        missing = [k for k in ("name", "description") if k not in keys]
        if missing:
            issues.append(f"{rel(path)}: frontmatter 필드 누락 — {', '.join(missing)}")

    agent_files = sorted((REPO_ROOT / ".claude" / "agents").glob("*.md"))
    for path in agent_files:
        checked += 1
        keys = parse_frontmatter_keys(path)
        if keys is None:
            issues.append(f"{rel(path)}: frontmatter 없음")
            continue
        missing = [k for k in ("name", "description", "model", "tools") if k not in keys]
        if missing:
            issues.append(f"{rel(path)}: frontmatter 필드 누락 — {', '.join(missing)}")

    return checked, issues


def extract_headings(path):
    headings = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r'^(#{1,3})\s+(.*)$', line.strip())
        if m:
            headings.append(m.group(2).strip())
    return headings


def extract_phrase_before(line, idx):
    """idx(키워드 시작 위치) 바로 앞에서 허용 문자(영문/숫자/하이픈/공백)로만 이어진 구문을 뒤로 훑어 추출한다."""
    j = idx
    while j > 0 and line[j - 1] == " ":
        j -= 1
    k = j
    while k > 0 and line[k - 1] in PHRASE_CHARS:
        k -= 1
    return line[k:j].strip()


def verify_checklist_section(checklist_ref, section_text, heading_cache):
    basename = Path(checklist_ref).name
    checklist_path = CHECKLIST_DIR / basename
    if not checklist_path.exists():
        return False, f"체크리스트 파일 없음 — {basename}"
    if basename not in heading_cache:
        heading_cache[basename] = extract_headings(checklist_path)
    headings = heading_cache[basename]
    target = section_text.lower()
    if not any(target in heading.lower() for heading in headings):
        return False, f"'{section_text}' 섹션이 {basename}에 없음"
    return True, ""


def check_checklist_refs(files):
    checked = 0
    broken = []
    heading_cache = {}

    for path in files:
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            # 형태 A: "| ... | `<name>-ready.md` | <섹션 제목> |" 표 행 (도메인 검토자 stage 매핑 표 등)
            for m in TABLE_ROW_RE.finditer(line):
                checklist_ref, section_text = m.group(1), m.group(2).strip()
                checked += 1
                ok, msg = verify_checklist_section(checklist_ref, section_text, heading_cache)
                if not ok:
                    broken.append(f"{rel(path)}:{lineno}: {msg}")

            # 형태 B: 프로즈 "<체크리스트 언급> ... <구문> 섹션/항목"
            name_positions = [
                (m.start(), name)
                for name in CHECKLIST_NAMES
                for m in re.finditer(re.escape(name), line)
            ]
            if not name_positions:
                continue
            for keyword in SECTION_KEYWORDS:
                for km in re.finditer(re.escape(keyword), line):
                    preceding = [p for p in name_positions if p[0] < km.start()]
                    if not preceding:
                        continue
                    checklist_ref = max(preceding, key=lambda p: p[0])[1]
                    phrase = extract_phrase_before(line, km.start())
                    if len(phrase) < 3:
                        continue
                    checked += 1
                    ok, msg = verify_checklist_section(checklist_ref, phrase, heading_cache)
                    if not ok:
                        broken.append(f"{rel(path)}:{lineno}: {msg}")

    return checked, broken


def report(title, checked, issues):
    print(f"[{title}] 검사 {checked}건, 문제 {len(issues)}건")
    for issue in issues:
        print(f"  - {issue}")
    print()


def main():
    files = iter_markdown_files()
    print(f"검사 대상 문서 {len(files)}개 (CLAUDE.md, .claude/**/*.md, docs/context/**/*.md)\n")

    ref_checked, ref_broken = check_references(files)
    report("1. 참조 무결성", ref_checked, ref_broken)

    fm_checked, fm_broken = check_frontmatter()
    report("2. frontmatter 필수 필드", fm_checked, fm_broken)

    checklist_checked, checklist_broken = check_checklist_refs(files)
    report("3. 체크리스트 참조", checklist_checked, checklist_broken)

    total_issues = len(ref_broken) + len(fm_broken) + len(checklist_broken)
    print(f"총 문제 {total_issues}건")

    # 정보 제공용 도구 — 판정 결과와 무관하게 항상 0으로 종료해 작업을 막지 않는다.
    return 0


if __name__ == "__main__":
    sys.exit(main())
