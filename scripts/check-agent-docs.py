#!/usr/bin/env python3
"""check-agent-docs.py — 에이전트 지침 문서(CLAUDE.md / .claude / docs/context) 정합성 점검.

판정 6종:
  1. 참조 무결성 — 마크다운 링크 `[text](path)` 와 파일 경로 형태의 백틱 스팬(`a/b.md`)이
     실제 파일/디렉터리로 해석되는지 확인한다. 펜스(```) 코드 블록 내부, 공백 포함 문자열,
     URL, placeholder(`<...>`/`{...}`/`*`/`$`)는 검사 대상에서 제외한다.
  2. frontmatter 필수 필드 — `.claude/skills/*/SKILL.md` 는 name/description,
     `.claude/agents/*.md` 는 name/description/model/tools 가 있는지 확인한다.
  3. 체크리스트 참조 — 문서가 지정한 체크리스트 파일이 실재하고, 함께 언급한 섹션 제목이
     그 파일의 헤딩에 존재하는지 확인한다(도메인 검토자 stage 매핑 표의 3열 형식과
     "<구문> 섹션/항목" 프로즈 언급 두 가지 형태를 인식한다).
  4. 중복 규칙 — 정본으로 확정한 규칙 문구가 정본 파일 밖에 본문으로 그대로 등장하는지
     문구 사전 기반 정확 매칭으로 확인한다. 정본 경로를 포함한 포인터 문장, 코드
     펜스(커맨드 예시), 존치 결정된 `code-ready.md` convention/domain risk 섹션은
     매칭에서 제외한다.
  5. Mermaid 금지 문자 — ```mermaid 펜스 안 노드 라벨(`[...]`/`(...)`/`{...}`)과
     엣지 라벨(`|...|`) 안에 중괄호 · 가운뎃점(U+00B7) · 유니코드 화살표(U+2192)가
     있는지 확인한다.
  6. 고아 문서 — `.claude/` 하위 마크다운 중 어디서도 참조되지 않는 파일을 찾는다.
     `SKILL.md`/`.claude/agents/*.md`는 frontmatter 로 자동 탐색되는 진입점이라 제외한다.

판정 4(중복 규칙)·1(참조 무결성)은 작업 완료 조건(0건)이고, 판정 5·6 은 정보 제공용이다.
이 스크립트는 어느 판정이든 종료 코드로 작업을 막지 않는다 — 항상 0으로 끝난다.

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

# 판정 4: 중복 규칙 — 정본 파일에서 뽑은 문구 사전. 각 문구가 canonical 파일 밖에 본문으로
# 등장하면 중복 의심으로 본다. 문구는 마크다운 강조(백틱/별표)를 벗겨낸 평문 기준이다.
DUPLICATE_RULES = [
    {
        "name": "`var` 키워드 금지",
        "canonical": "docs/context/conventions/code-style.md",
        "phrases": ["var 키워드 금지"],
    },
    {
        "name": "`@Data` 금지",
        "canonical": "docs/context/conventions/code-style.md",
        "phrases": ["equals/hashCode/toString/getter/setter 를 한 번에 여는 뭉치 애너테이션"],
    },
    {
        "name": "공개 유스케이스·포트 null 반환 금지",
        "canonical": "docs/context/conventions/code-style.md",
        "phrases": ["공개 유스케이스·포트 반환값의 null 반환 금지"],
    },
    {
        "name": "try 블록 외부 변수 재할당 금지",
        "canonical": "docs/context/conventions/code-style.md",
        "phrases": ["try 블록 안에서 외부 변수 재할당 금지"],
    },
    {
        "name": "`catch (Exception)` swallow 금지",
        "canonical": "docs/context/conventions/code-style.md",
        "phrases": ["catch (Exception) swallow 금지"],
    },
    {
        "name": "TDD 사이클(RED/GREEN/REFACTOR)",
        "canonical": "docs/context/conventions/testing.md",
        "phrases": ["실패하는 테스트를 먼저 작성한다"],
    },
    {
        "name": "도메인 검토자 배차 2갈래 조건",
        "canonical": ".claude/skills/_shared/checklists/discuss-ready.md",
        "phrases": [
            "소스 코드 또는 런타임 설정(알람 규칙·Kafka 설정·스케줄러 등) 변경을 계획",
            "결제 도메인 동작(상태 전이·멱등성·복구·정산)을 서술·정정",
        ],
    },
    {
        "name": "문체 종결 규칙",
        "canonical": ".claude/skills/_shared/conventions/writing-style.md",
        "phrases": ["로 끝내거나 명사형으로 끝낸다"],
    },
]

# code-ready.md 는 정적 분석이 없는 규칙의 유일한 수동 판정 수단이라 convention · domain risk
# 두 섹션을 그대로 존치하기로 확정했다(Task 7) — 이 섹션 라인은 중복 매칭에서 제외한다.
CODE_READY_EXCLUDED_HEADING_PREFIXES = ("## convention", "## domain risk")

# 판정 5: Mermaid 노드/엣지 라벨 금지 문자 (writing-visuals.md "Mermaid 노드 라벨 금지 문자" 절 중
# 이 스크립트가 판정하는 부분집합).
MERMAID_FORBIDDEN_CHARS = {
    "{": "중괄호",
    "}": "중괄호",
    "·": "가운뎃점(U+00B7)",
    "→": "유니코드 화살표(U+2192)",
}
MERMAID_LABEL_RES = [
    re.compile(r'\[([^\[\]]+)\]'),
    re.compile(r'\(([^()]+)\)'),
    re.compile(r'\{([^{}]+)\}'),
    re.compile(r'\|([^|]+)\|'),
]

# 판정 6: 고아 문서 — SKILL.md/agents/*.md 는 frontmatter 로 자동 탐색되는 진입점이라 제외.
ORPHAN_SCAN_ROOT = REPO_ROOT / ".claude"


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


def code_ready_excluded_lines():
    """code-ready.md convention · domain risk 섹션의 라인 번호(1-indexed) 집합.

    두 섹션은 정적 분석 대체 수단이 없어 존치하기로 확정했다(Task 7) — 중복 매칭 제외 대상."""
    path = CHECKLIST_DIR / "code-ready.md"
    if not path.exists():
        return set()
    excluded = set()
    in_excluded = False
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip().lower()
        if stripped.startswith("## "):
            in_excluded = stripped.startswith(CODE_READY_EXCLUDED_HEADING_PREFIXES)
        if in_excluded:
            excluded.add(lineno)
    return excluded


def check_duplicate_rules(files):
    """정본 문구 사전이 정본 밖 파일에 본문으로 그대로 등장하는지 정확 매칭으로 확인한다.

    제외 대상: 펜스 코드 블록(커맨드 예시), 정본 파일 자신, 정본 경로를 포함한 포인터 문장,
    code-ready.md convention/domain risk 섹션(존치 결정)."""
    checked = 0
    issues = []
    code_ready_path = (CHECKLIST_DIR / "code-ready.md").resolve()
    code_ready_excluded = code_ready_excluded_lines()

    for entry in DUPLICATE_RULES:
        checked += 1
        canonical_path = (REPO_ROOT / entry["canonical"]).resolve()
        canonical_name = Path(entry["canonical"]).name

        for path in files:
            resolved_path = path.resolve()
            if resolved_path == canonical_path:
                continue
            is_code_ready = resolved_path == code_ready_path
            scan_lines = strip_fenced_code(path.read_text(encoding="utf-8").splitlines())

            for lineno, line in enumerate(scan_lines, start=1):
                if not line:
                    continue
                if is_code_ready and lineno in code_ready_excluded:
                    continue
                if canonical_name in line:
                    continue  # 정본 경로를 포함한 포인터 문장 — 매칭 제외
                normalized = line.replace("`", "").replace("*", "")
                for phrase in entry["phrases"]:
                    if phrase in normalized:
                        issues.append(
                            f"{rel(path)}:{lineno}: '{entry['name']}' 규칙 중복 의심"
                            f" (정본: {entry['canonical']}) — {line.strip()}"
                        )
                        break

    return checked, issues


def check_mermaid_labels(files):
    """```mermaid 펜스 안 노드/엣지 라벨에 금지 문자(중괄호·가운뎃점·유니코드 화살표)가 있는지 확인한다.

    정보 제공용 — 기존 아카이브 다이어그램까지 0건을 강제하지 않는다."""
    checked = 0
    issues = []

    for path in files:
        in_mermaid = False
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith("```"):
                in_mermaid = stripped.startswith("```mermaid") if not in_mermaid else False
                continue
            if not in_mermaid:
                continue
            checked += 1
            found = set()
            for pattern in MERMAID_LABEL_RES:
                for label in pattern.findall(line):
                    for ch, name in MERMAID_FORBIDDEN_CHARS.items():
                        if ch in label:
                            found.add(name)
            if found:
                issues.append(f"{rel(path)}:{lineno}: 금지 문자({', '.join(sorted(found))}) — {line.strip()}")

    return checked, issues


def first_existing(path_part, base_dir):
    """참조 무결성 판정의 후보 경로 순회 로직을 재사용해, 처음 존재하는 실제 경로를 반환한다."""
    if path_part.startswith(("./", "../")):
        candidate = (base_dir / path_part).resolve()
        return candidate if candidate.exists() else None
    for candidate in path_candidates(path_part, base_dir):
        resolved = candidate.resolve()
        if resolved.exists():
            return resolved
    return None


def collect_referenced_paths(files):
    """스캔 대상 문서 전체에서 실제로 해석되는 링크/백틱 경로 대상 집합을 모은다."""
    referenced = set()
    for path in files:
        scan_lines = strip_fenced_code(path.read_text(encoding="utf-8").splitlines())
        for line in scan_lines:
            if not line:
                continue
            for _text, target in LINK_RE.findall(line):
                target = target.strip()
                if target.startswith(("#", "http://", "https://", "mailto:")):
                    continue
                path_part = target.split("#", 1)[0].strip()
                if not path_part:
                    continue
                resolved = first_existing(path_part, path.parent)
                if resolved:
                    referenced.add(resolved)

            line_without_links = LINK_RE.sub(lambda m: " " * len(m.group(0)), line)
            for span in BACKTICK_RE.findall(line_without_links):
                span = span.strip()
                if not is_path_shaped(span):
                    continue
                path_part = span.split("#", 1)[0]
                resolved = first_existing(path_part, path.parent)
                if resolved:
                    referenced.add(resolved)
    return referenced


def check_orphan_docs(files):
    """`.claude/` 마크다운 중 어디서도 참조되지 않는 파일을 찾는다.

    `SKILL.md`/`.claude/agents/*.md`는 frontmatter 로 자동 탐색되는 진입점이라 제외한다.
    정보 제공용 — 판정 결과가 카테고리 배제가 아니라 실제 참조 그래프인지 샘플로 검증한다."""
    referenced = collect_referenced_paths(files)

    candidates = [
        p for p in sorted(ORPHAN_SCAN_ROOT.rglob("*.md"))
        if p.name != "SKILL.md" and p.parent.name != "agents"
    ]
    checked = len(candidates)
    orphans = [f"{rel(p)}: 어디서도 참조되지 않음" for p in candidates if p.resolve() not in referenced]
    return checked, orphans


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

    dup_checked, dup_issues = check_duplicate_rules(files)
    report("4. 중복 규칙", dup_checked, dup_issues)

    mermaid_checked, mermaid_issues = check_mermaid_labels(files)
    report("5. Mermaid 금지 문자", mermaid_checked, mermaid_issues)

    orphan_checked, orphan_issues = check_orphan_docs(files)
    report("6. 고아 문서", orphan_checked, orphan_issues)

    total_issues = (
        len(ref_broken) + len(fm_broken) + len(checklist_broken)
        + len(dup_issues) + len(mermaid_issues) + len(orphan_issues)
    )
    print(f"총 문제 {total_issues}건")

    # 정보 제공용 도구 — 판정 결과와 무관하게 항상 0으로 종료해 작업을 막지 않는다.
    return 0


if __name__ == "__main__":
    sys.exit(main())
