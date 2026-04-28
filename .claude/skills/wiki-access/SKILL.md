---
name: wiki-access
description: 사용자가 "위키 참고", "깃헙 위키 참고", "위키 확인", "wiki 보기", "위키랑 비교", "위키 수정", "위키 안에 있어?" 같은 표현으로 GitHub Wiki 콘텐츠를 참조하라는 신호를 줄 때 반드시 사용한다. 위키 파일은 메인 프로젝트와 별도 git 저장소로 `<project>.wiki/` 디렉토리에 클론되어 있어 메인 트리 안에서는 안 보이므로, 이 스킬이 그 위치를 자동으로 찾아 Read / Grep / Edit 으로 접근하게 해준다. 사용자가 위키 내용을 묻거나, README · 코드 · 위키 간 정합성을 확인하거나, 위키를 수정하려 하거나, 특정 키워드 / 파일이 위키에 존재하는지 묻는 모든 상황에서 사용한다. "wiki" 라는 단어가 등장하지 않더라도 결제 도메인 설계 문서(outbox / dedupe / trace-propagation 등)를 참조해야 할 맥락이면 사용을 고려한다.
---

# Wiki Access

## 목적

이 프로젝트는 GitHub Wiki 를 메인 코드 저장소와 **별도 git 저장소** 로 운영한다. 위키 저장소는 보통 메인 프로젝트와 같은 부모 디렉토리에 `<project>.wiki/` 형태로 클론되며, 메인 프로젝트의 git 트리 안에서는 보이지 않는다. 이 스킬은 사용자가 "위키" 트리거를 던지면 그 디렉토리를 자동으로 찾아 접근한다.

## 트리거 — 사용자 발화 예시

다음 표현이 등장하면 이 스킬을 활성화한다.

| 직접 트리거 | 간접 트리거 |
|:---:|:---:|
| "위키 참고", "위키 확인", "위키 봐줘" | "이거 위키에 있어?", "위키랑 비교" |
| "깃헙 위키", "GitHub 위키", "Wiki 참고" | "outbox 어디 정리되어 있지?", "trace-propagation 보자" |
| "wiki 에서", "wiki 안에", "wiki 의 ~" | 위키 파일명 직접 언급 (예: `outbox-pattern.md`) |
| "위키도 수정해줘", "위키도 같이" | README ↔ 위키 정합성 점검 요청 |

## 위키 경로 탐색 — 3단계

### 1단계 — 고정 경로 시도 (이 프로젝트 기본)

payment-platform 워크스페이스의 위키 경로는 다음에 고정:

```
/Users/hyoguoo/Repositories/hyoguoo/projects/payment-platform.wiki/
```

먼저 존재 확인:

```bash
ls -d /Users/hyoguoo/Repositories/hyoguoo/projects/payment-platform.wiki/ 2>/dev/null
```

존재하면 그 디렉토리를 위키 루트로 채택하고 다음 단계 건너뛴다.

### 2단계 — 컨벤션 기반 탐색 (fallback)

고정 경로가 없으면 GitHub Wiki 의 일반 컨벤션을 따라 탐색한다. wiki 저장소는 메인 저장소와 같은 부모 디렉토리에 `<repo>.wiki/` 로 클론되는 게 표준 관습.

```bash
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
[ -z "$REPO_ROOT" ] && exit 1
REPO_NAME=$(basename "$REPO_ROOT")
PARENT=$(dirname "$REPO_ROOT")

# 후보 1 — 자매 디렉토리
ls -d "$PARENT/${REPO_NAME}.wiki" 2>/dev/null

# 후보 2 — 한 단계 위
ls -d "$(dirname "$PARENT")/${REPO_NAME}.wiki" 2>/dev/null

# 후보 3 — 메인 저장소 안 (일부 워크플로우)
ls -d "$REPO_ROOT/.wiki" 2>/dev/null
ls -d "$REPO_ROOT/wiki" 2>/dev/null
```

후보 중 가장 먼저 매칭된 디렉토리를 위키 루트로 채택. 안에 `.md` 파일이 1개라도 있는지 확인 (`ls *.md` 로 sanity check) 후 사용한다.

### 3단계 — 못 찾으면 명시

위 두 단계 모두 실패하면 사용자에게 솔직히 보고한다 — 추측하거나 무시하지 말고 다음 형식으로 안내:

```
위키 디렉토리를 찾지 못했어. 시도한 경로:
- /Users/hyoguoo/Repositories/hyoguoo/projects/payment-platform.wiki/
- <탐색 fallback 후보들>

위키 저장소가 클론돼 있는지 확인해줄래? 보통 다음 명령으로 클론:
  git clone https://github.com/<user>/<repo>.wiki.git
경로가 다른 곳이면 알려주면 그 경로로 접근할게.
```

이 명시 안내가 핵심 — 잘못된 경로를 추측해서 작업하면 사용자가 한참 후에야 깨닫고 고치기 힘들어진다.

## 사용 패턴

### 위키 파일 목록

위키 루트 발견 후 전체 파일 인덱스:

```bash
ls <wiki-root>/*.md
```

### 위키 파일 읽기

`Read` 툴을 절대 경로로 호출. 위키 루트 + 파일명 조합:

```
Read /Users/hyoguoo/Repositories/hyoguoo/projects/payment-platform.wiki/outbox-pattern.md
```

### 위키 콘텐츠 검색

특정 키워드가 어느 위키 파일에 있는지:

```bash
grep -rln "<keyword>" <wiki-root>
```

검색 결과를 사용자에게 보고할 때는 위키 루트 기준 상대 경로(`outbox-pattern.md`) 와 라인 번호를 같이 명시하면 사용자가 바로 추적 가능.

### 위키 파일 수정

`Edit` 툴을 그대로 사용한다. 단 다음 사실을 사용자가 인지하도록 보고에 포함:

- 위키 저장소는 **별도 git 저장소** 라 메인 프로젝트의 `git status` 에는 안 잡힘
- 수정 후 사용자가 별도로 wiki 디렉토리에서 `git add / commit / push` 해야 GitHub Wiki 에 반영됨
- 이 스킬은 파일 수정만 담당, commit / push 는 자동 실행 안 한다

## README ↔ 위키 정합성 점검

자주 등장하는 패턴 — README 의 표현과 위키의 표현이 같은지, 위키에 README 가 위임한 콘텐츠가 실제로 있는지 검증.

체크리스트:

- README 의 외부 링크가 실제 위키 파일을 가리키는지 (`<project>/wiki/<page>` URL 의 슬러그가 위키 디렉토리의 `.md` 파일명과 일치)
- README 가 "자세한 내용은 위키 X 참고" 라고 위임한 부분이 위키 X 안에 정말로 있는지 grep
- 같은 코드 경로 / 클래스명 / 설정 키가 README 와 위키에서 동일한 표현으로 쓰이는지

불일치 발견 시 사용자에게 보고만 하고 자동 수정은 하지 않는다 — 사용자가 어느 쪽을 정답으로 둘지 결정해야 함.

## 출력 표현 규칙

- 위키 파일을 인용할 때는 위키 루트 기준 **상대 경로** + 라인 번호 (예: `outbox-pattern.md` L101-134) — 절대 경로는 길어서 가독성 떨어짐
- 위키 파일을 수정한 경우 마지막에 한 줄 안내: "위키는 별도 저장소이므로 `<wiki-root>` 에서 commit / push 필요"
- 못 찾은 경우 시도한 경로를 모두 나열 — 추측 금지

## 주의

- 위키 디렉토리 안에 있는 `.git/` 은 **메인 프로젝트 git 과 별개**. 메인 프로젝트의 git 명령이 위키 변경을 감지하지 않는다.
- 위키 파일을 수정하면 사용자가 별도로 wiki 디렉토리에서 commit + push 해야 GitHub Wiki 에 반영된다.
- 이 스킬은 파일 시스템 접근만 담당. GitHub Wiki HTTP API 호출은 하지 않는다.
- 위키 파일명은 GitHub Wiki 페이지 슬러그와 1:1 대응 — 파일명 변경은 외부 링크 깨뜨림. 함부로 rename 하지 않는다.
