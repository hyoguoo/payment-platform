#!/usr/bin/env python3
"""check-agent-docs.py — 지침·스킬·프로젝트 문서의 정합성 점검.

판정 6종:
  1. 참조 무결성 — 마크다운 링크 `[text](path)`와 파일 경로 모양의 백틱 스팬(`a/b.md`)이 실제 파일·디렉터리로
     해석되는지 본다. 코드 펜스(```) 안, 공백이 든 문자열, URL, placeholder(`<...>`, `{...}`, `*`, `$`)는 제외한다.
     `.agents/templates/`는 복사될 토픽 폴더 기준으로 링크를 쓰므로 제외한다.
  2. frontmatter 필수 필드 — `.agents/skills/*/SKILL.md`는 name·description, `.claude/agents/*.md`는
     name·description·model·tools가 있는지 본다.
  3. 문서 식별자 대조 — `docs/context`, `docs/guides`, `docs/smoke`, `docs/quality`, `docs/TODOS.md`의 백틱 스팬 중
     클래스 이름(UpperCamelCase)이 저장소 코드(주석·문자열 리터럴 제외)에 나오는지, 점으로 이은 설정 키·토픽 이름이 설정·코드·
     스크립트에 나오는지 본다. 설정 키는 그 키나 바로 위 키가 있으면 통과한다(기본값으로 쓰는 하위 키 허용).
     경로 모양 스팬은 판정 1이 맡는다.
  4. Mermaid 금지 문자 — ```mermaid 펜스 안 노드 라벨과 엣지 라벨에 중괄호, 가운뎃점(U+00B7),
     유니코드 화살표(U+2192)가 있는지 본다.
  5. 고아 문서 — `.agents/`, `.claude/` 아래에서 어디서도 참조되지 않는 문서를 찾는다.
     `SKILL.md`와 역할 파일은 도구가 자동으로 찾는 진입점이라 제외한다.
  6. 역할 설정 동기화 — 역할 정본과 Claude·Codex 생성 파일의 일치를 확인한다.

기본 실행은 보고만 하고 항상 0을 반환한다. --strict는 판정 1·2·3·6에 문제가 있으면 1을 반환한다.
판정 4·5는 정보 제공용이다.

실행:  python3 scripts/check-agent-docs.py [--strict]
"""
import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

SCAN_ROOTS = [
    REPO_ROOT / "AGENTS.md",
    REPO_ROOT / ".agents",
    REPO_ROOT / ".claude",
    REPO_ROOT / "docs" / "context",
    REPO_ROOT / "docs" / "guides",
    REPO_ROOT / "docs" / "smoke",
    REPO_ROOT / "docs" / "quality",
    REPO_ROOT / "docs" / "STATE.md",
    REPO_ROOT / "docs" / "TODOS.md",
    REPO_ROOT / "docs" / "archive" / "README.md",
]

# 판정 3의 대상. 에이전트가 작업 근거로 읽거나 사람이 따라 하는 문서다.
IDENTIFIER_ROOTS = ["docs/context", "docs/guides", "docs/smoke", "docs/quality", "docs/TODOS.md"]

# 백틱 단독 스팬에서 경로로 인정하는 확장자. 웹 자산(.html/.css/.js/.ts)은 별도 blog 저장소·브라우저 URL
# 예시에서 반복되는 오탐 원인이라 제외한다. 그런 경로는 마크다운 링크로 쓰지 않는다.
BACKTICK_PATH_EXTENSIONS = {
    ".md", ".sh", ".py", ".java", ".yml", ".yaml", ".json", ".properties",
    ".gradle", ".toml", ".conf", ".sql", ".txt", ".lua",
}

TOP_LEVEL_ENTRIES = {p.name for p in REPO_ROOT.iterdir()}

LINK_RE = re.compile(r'\[([^\]]*)\]\(([^)]+)\)')
BACKTICK_RE = re.compile(r'`([^`\n]+)`')
PLACEHOLDER_CHARS = set("<>{}*$:")

# 판정 3: 클래스 이름은 대문자로 시작하고 대소문자 경계가 두 번 이상인 단어(PaymentEvent)만 본다.
# 한 단어(Kafka), 상태값(IN_FLIGHT), 약어로 시작하는 이름(HTTPClient)은 오탐이 많아 대조하지 않는다.
CLASS_SPAN_RE = re.compile(r'^([A-Z][A-Za-z0-9]+)(?:[.#][A-Za-z_][A-Za-z0-9_]*(?:\([^)]*\))?)*$')
CAMEL_RE = re.compile(r'^[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+$')
JAVA_TOKEN_RE = re.compile(r'\b[A-Z][A-Za-z0-9]+\b')
# 설정 키·토픽 이름: 소문자로 시작하고 점으로 이은 마디가 셋 이상. 마지막 마디가 확장자면 파일 이름으로 본다.
DOTTED_KEY_RE = re.compile(r'^[a-z][a-z0-9_-]*(?:\.[a-z0-9_-]+){2,}$')
FILE_SUFFIXES = {
    "md", "sh", "py", "java", "yml", "yaml", "properties", "json", "sql", "txt", "html", "log",
    "gradle", "toml", "xml", "lua", "conf", "csv", "png", "jar", "env", "js", "ts",
}
# 어느 깊이에서든 건너뛰는 디렉터리. IDE 출력 `out`은 저장소 루트와 모듈 루트(깊이 1)에서만 건너뛴다(`application/port/out`과 겹치지 않게).
CODE_SKIP_DIRS = {".git", "build", ".gradle", "node_modules", "docs", "live-drill", ".archive", ".idea"}
ROOT_ONLY_SKIP_DIRS = {"out"}
# 대문자 시작 단어를 모으는 파일. 알람 규칙 이름(YAML), Checkstyle 모듈 이름(XML), hook 이벤트 이름(JSON)도 여기서 온다.
CODE_TOKEN_SUFFIXES = {".java", ".gradle", ".kts", ".yml", ".yaml", ".xml", ".json", ".properties"}
# 우리 코드는 쓰지 않지만 문서가 동작을 설명하는 외부 라이브러리 타입. 삭제된 우리 클래스를 가리지 않도록
# 실제 라이브러리 타입만 넣는다.
EXTERNAL_TYPE_NAMES = {
    "MissingMigrationException",  # Flyway
    "DefaultLifecycleProcessor",  # Spring Framework
    "RestTemplate",  # Spring Framework
    "ChainedKafkaTransactionManager",  # Spring Kafka
    "AfterRollbackProcessor",  # Spring Kafka (우리 코드는 구현체 DefaultAfterRollbackProcessor를 연결)
    "KafkaAdmin",  # Spring Kafka
    "LogstashEncoder",  # logstash-logback-encoder
    "RateLimiter",  # Resilience4j
    "TreeWalker",  # Checkstyle
}
# 우리 설정에 없고 문서가 라이브러리 기본값을 설명하는 설정 키.
EXTERNAL_CONFIG_KEYS = {
    "max.poll.interval.ms",  # Kafka consumer
}
CONFIG_CORPUS_SUFFIXES = {
    ".yml", ".yaml", ".properties", ".java", ".sh", ".py", ".js", ".json", ".conf", ".toml",
    ".gradle", ".lua", ".sql", ".xml", ".env",
}

# 판정 4: Mermaid 노드·엣지 라벨 금지 문자 (writing 스킬 style-guide 8.5절 중 이 스크립트가 판정하는 부분집합)
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

ORPHAN_SCAN_ROOTS = [REPO_ROOT / ".agents", REPO_ROOT / ".claude"]


def rel(path, root=REPO_ROOT):
    return path.resolve().relative_to(root.resolve()).as_posix()


def iter_markdown_files(roots):
    files = []
    for root in roots:
        if root.is_file() and root.suffix == ".md":
            files.append(root)
        elif root.is_dir():
            files.extend(sorted(root.rglob("*.md")))
    return unique_paths(files)


def unique_paths(paths):
    """같은 실제 파일을 가리키는 호환 링크(.claude/skills)를 한 번만 검사한다."""
    return list(dict.fromkeys(path.resolve() for path in paths))


def strip_fenced_code(lines):
    """코드 펜스(```) 안 줄은 빈 문자열로 바꾼다. 코드 예시와 명령은 검사하지 않는다."""
    out = []
    in_fence = False
    for line in lines:
        if line.strip().startswith("```"):
            in_fence = not in_fence
            out.append("")
            continue
        out.append("" if in_fence else line)
    return out


def backtick_spans(line):
    """링크로 처리한 부분을 뺀 줄의 백틱 스팬."""
    line_without_links = LINK_RE.sub(lambda m: " " * len(m.group(0)), line)
    return [span.strip() for span in BACKTICK_RE.findall(line_without_links)]


# ── 판정 1: 참조 무결성 ──────────────────────────────────────────────────────

def is_path_shaped(candidate):
    """백틱 스팬이 파일 경로 모양인지 판정한다. 명령, URL, 줄임(...), placeholder는 제외한다.

    `./`, `../`로 시작하는 스팬은 셸 명령 예시(`./gradlew test`)와 구분되지 않아 제외한다. 문서 사이의
    상대 경로 참조는 마크다운 링크로 쓰고, 그쪽은 따로 검사한다.
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
    """경로 후보: 저장소 루트 기준, 참조한 문서 기준, 그 상위 기준."""
    path_part = path_part.lstrip("/")
    return [REPO_ROOT / path_part, base_dir / path_part, base_dir.parent / path_part]


def first_existing(path_part, base_dir):
    if path_part.startswith(("./", "../")):
        candidate = (base_dir / path_part).resolve()
        return candidate if candidate.exists() else None
    for candidate in path_candidates(path_part, base_dir):
        resolved = candidate.resolve()
        if resolved.exists():
            return resolved
    return None


TEMPLATE_DIR = (REPO_ROOT / ".agents" / "templates").resolve()


def check_references(files):
    checked = 0
    broken = []
    for path in files:
        if path.is_relative_to(TEMPLATE_DIR):
            continue
        scan_lines = strip_fenced_code(path.read_text(encoding="utf-8").splitlines())
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
                if not first_existing(path_part, path.parent):
                    broken.append(f"{rel(path)}:{lineno}: 링크 대상 없음 — {target}")
            for span in backtick_spans(line):
                if not is_path_shaped(span):
                    continue
                path_part = span.split("#", 1)[0]
                if first_existing(path_part, path.parent):
                    checked += 1
                elif path_part.lstrip("/").split("/", 1)[0] in TOP_LEVEL_ENTRIES:
                    # 최상위 마디가 저장소에 실제로 있을 때만 깨진 경로로 본다. 그 밖(db/migration,
                    # application/port 같은 계층 줄임 표기)은 검사 신뢰 범위 밖이라 건너뛴다.
                    checked += 1
                    broken.append(f"{rel(path)}:{lineno}: 경로 미해석 — `{span}`")
    return checked, broken


# ── 판정 2: frontmatter ──────────────────────────────────────────────────────

def parse_frontmatter_keys(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].strip() != "---":
        return None
    for end in range(1, len(lines)):
        if lines[end].strip() == "---":
            break
    else:
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
    targets = [
        (path, ("name", "description"))
        for path in unique_paths(sorted((REPO_ROOT / ".agents" / "skills").glob("*/SKILL.md")))
    ] + [
        (path, ("name", "description", "model", "tools"))
        for path in sorted((REPO_ROOT / ".claude" / "agents").glob("*.md"))
    ]
    for path, required in targets:
        checked += 1
        keys = parse_frontmatter_keys(path)
        if keys is None:
            issues.append(f"{rel(path)}: frontmatter 없음")
            continue
        missing = [k for k in required if k not in keys]
        if missing:
            issues.append(f"{rel(path)}: frontmatter 필드 누락 — {', '.join(missing)}")
    return checked, issues


# ── 판정 3: 문서 식별자 대조 ─────────────────────────────────────────────────

def iter_code_files(root, suffixes):
    root = Path(root)
    for dirpath, dirnames, filenames in os.walk(root):
        shallow = len(Path(dirpath).relative_to(root).parts) <= 1
        dirnames[:] = [
            d for d in dirnames
            if d not in CODE_SKIP_DIRS and not (shallow and d in ROOT_ONLY_SKIP_DIRS)
        ]
        for name in filenames:
            if Path(name).suffix in suffixes:
                yield Path(dirpath) / name


def normalize_key(key):
    """Spring의 느슨한 바인딩처럼 하이픈·밑줄·대소문자 차이를 무시한다."""
    return key.lower().replace("-", "").replace("_", "")


def flatten_yaml_keys(text):
    """YAML의 중첩 키를 점으로 이은 경로로 펼친다. 목록 항목과 값은 보지 않는 단순 파서다."""
    keys = set()
    stack = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped == "---":
            stack = []
            continue
        if not stripped or stripped.startswith(("#", "-")):
            continue
        m = re.match(r'^(\s*)([^\s:#][^:#]*?):(\s|$)', line)
        if not m:
            continue
        indent = len(m.group(1))
        key = m.group(2).strip().strip("\"'")
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, key))
        keys.add(normalize_key(".".join(k for _, k in stack)))
    return keys


JAVA_STRING_RE = re.compile(r'"(?:\\.|[^"\\\n])*"')
JAVA_BLOCK_COMMENT_RE = re.compile(r'/\*.*?\*/', re.S)
JAVA_LINE_COMMENT_RE = re.compile(r'//[^\n]*')
HASH_COMMENT_RE = re.compile(r'(^|\s)#[^\n]*')
XML_COMMENT_RE = re.compile(r'<!--.*?-->', re.S)


def strip_non_code(path, text):
    """주석과 문자열 리터럴을 걷어 낸다. 주석에만 남은 옛 이름이 "코드에 있음"으로 통과하지 않게 한다."""
    if path.suffix in {".java", ".gradle", ".kts"}:
        text = JAVA_STRING_RE.sub('""', text)
        text = JAVA_BLOCK_COMMENT_RE.sub(" ", text)
        return JAVA_LINE_COMMENT_RE.sub(" ", text)
    if path.suffix in {".yml", ".yaml", ".properties"}:
        return HASH_COMMENT_RE.sub(r"\1", text)
    if path.suffix == ".xml":
        return XML_COMMENT_RE.sub(" ", text)
    return text


def build_code_index(root):
    """식별자 대조에 쓰는 색인: 코드(주석·문자열 제외)에 나오는 대문자 시작 단어, 펼친 YAML 키, 설정·코드·스크립트 원문."""
    tokens = set()
    for path in iter_code_files(root, CODE_TOKEN_SUFFIXES):
        text = path.read_text(encoding="utf-8", errors="ignore")
        tokens.update(JAVA_TOKEN_RE.findall(strip_non_code(path, text)))
    yaml_keys = set()
    corpus_parts = []
    for path in iter_code_files(root, CONFIG_CORPUS_SUFFIXES):
        text = path.read_text(encoding="utf-8", errors="ignore")
        corpus_parts.append(normalize_key(text))
        if path.suffix in {".yml", ".yaml"}:
            yaml_keys.update(flatten_yaml_keys(text))
    return {"tokens": tokens, "yaml_keys": yaml_keys, "corpus": "\n".join(corpus_parts)}


def config_key_known(key, index):
    if key in EXTERNAL_CONFIG_KEYS:
        return True
    for candidate in (key, key.rsplit(".", 1)[0]):
        normalized = normalize_key(candidate)
        if normalized.count(".") < 1:
            continue
        if normalized in index["yaml_keys"] or normalized in index["corpus"]:
            return True
    # Kafka 클라이언트 속성(max.poll.records)은 Spring 설정에 하이픈 형태(max-poll-records)로 들어간다.
    return normalize_key(key).replace(".", "") in index["corpus"]


def check_identifiers(files, index, root=REPO_ROOT):
    checked = 0
    issues = []
    for path in files:
        scan_lines = strip_fenced_code(path.read_text(encoding="utf-8").splitlines())
        for lineno, line in enumerate(scan_lines, start=1):
            if not line:
                continue
            for span in backtick_spans(line):
                class_match = CLASS_SPAN_RE.match(span)
                if class_match and CAMEL_RE.match(class_match.group(1)):
                    checked += 1
                    name = class_match.group(1)
                    if name not in index["tokens"] and name not in EXTERNAL_TYPE_NAMES:
                        issues.append(f"{rel(path, root)}:{lineno}: 코드에 없는 클래스 — `{span}`")
                    continue
                if DOTTED_KEY_RE.match(span) and span.rsplit(".", 1)[1] not in FILE_SUFFIXES:
                    checked += 1
                    if not config_key_known(span, index):
                        issues.append(f"{rel(path, root)}:{lineno}: 설정·코드·스크립트에 없는 키 — `{span}`")
    return checked, issues


# ── 판정 4: Mermaid ──────────────────────────────────────────────────────────

def check_mermaid_labels(files):
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
                issues.append(f"{rel(path)}:{lineno}: 금지 문자({', '.join(sorted(found))}) — {stripped}")
    return checked, issues


# ── 판정 5: 고아 문서 ────────────────────────────────────────────────────────

def collect_referenced_paths(files):
    referenced = set()
    for path in files:
        for line in strip_fenced_code(path.read_text(encoding="utf-8").splitlines()):
            if not line:
                continue
            for _text, target in LINK_RE.findall(line):
                target = target.strip()
                if target.startswith(("#", "http://", "https://", "mailto:")):
                    continue
                resolved = first_existing(target.split("#", 1)[0].strip(), path.parent)
                if resolved:
                    referenced.add(resolved)
            for span in backtick_spans(line):
                if is_path_shaped(span):
                    resolved = first_existing(span.split("#", 1)[0], path.parent)
                    if resolved:
                        referenced.add(resolved)
    return referenced


def check_orphan_docs(files):
    referenced = collect_referenced_paths(files)
    candidates = [
        p for p in unique_paths(p for root in ORPHAN_SCAN_ROOTS for p in sorted(root.rglob("*.md")))
        if p.name != "SKILL.md" and p.parent.name != "agents"
    ]
    orphans = [f"{rel(p)}: 어디서도 참조되지 않음" for p in candidates if p not in referenced]
    return len(candidates), orphans


# ── 판정 6: 역할 설정 동기화 ─────────────────────────────────────────────────

def check_role_configs():
    result = subprocess.run(
        [sys.executable, str(REPO_ROOT / "scripts/sync-agent-configs.py"), "--check"],
        capture_output=True, text=True, check=False,
    )
    if result.returncode:
        return 1, [(result.stdout + result.stderr).strip() or "역할 설정 검사 실패"]
    return 1, []


def report(title, checked, issues):
    print(f"[{title}] 검사 {checked}건, 문제 {len(issues)}건")
    for issue in issues:
        print(f"  - {issue}")
    print()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true", help="판정 1·2·3·6에 문제가 있으면 종료 코드 1")
    args = parser.parse_args(argv)

    files = iter_markdown_files(SCAN_ROOTS)
    identifier_files = iter_markdown_files([REPO_ROOT / r for r in IDENTIFIER_ROOTS])
    print(f"검사 대상 문서 {len(files)}개, 식별자 대조 문서 {len(identifier_files)}개\n")

    results = [
        ("1. 참조 무결성", *check_references(files), True),
        ("2. frontmatter 필수 필드", *check_frontmatter(), True),
        ("3. 문서 식별자 대조", *check_identifiers(identifier_files, build_code_index(REPO_ROOT)), True),
        ("4. Mermaid 금지 문자", *check_mermaid_labels(files), False),
        ("5. 고아 문서", *check_orphan_docs(files), False),
        ("6. 역할 설정 동기화", *check_role_configs(), True),
    ]
    for title, checked, issues, _blocking in results:
        report(title, checked, issues)

    print(f"총 문제 {sum(len(issues) for _, _, issues, _ in results)}건")
    blocking = any(issues for _, _, issues, is_blocking in results if is_blocking)
    return 1 if args.strict and blocking else 0


if __name__ == "__main__":
    sys.exit(main())
