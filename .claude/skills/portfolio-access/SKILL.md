---
name: portfolio-access
description: 사용자가 "포트폴리오 수정", "포트폴리오 사이트", "payment-flows", "포트폴리오 페이지", "포트폴리오 배포", "index.html 고쳐", "포트폴리오 확인" 같은 표현으로 결제 플랫폼 포트폴리오 페이지를 참조·수정하라는 신호를 줄 때 반드시 사용한다. 이 포트폴리오(구 `docs/site/payment-flows.html`)는 payment-platform이 아니라 **별도 blog 저장소**(`notes/blog`, Astro)에서 관리되며 payment-platform 트리 안엔 존재하지 않으므로, 이 스킬이 정본 페이지(`src/pages/payment-platform-portfolio/index.astro` — CSS·데이터·로직은 각각 `src/styles/`·`src/data/paymentPortfolio/`·`src/scripts/portfolio/` 모듈로 분리)와 dev 툴 위치를 자동으로 찾아 Read / Grep / Edit 으로 접근하게 해준다. "포트폴리오" 단어가 없어도 결제 플랫폼 소개 페이지·시스템 아키텍처 다이어그램·시나리오 극장·섹션 밴드/모션 같은 그 페이지의 콘텐츠를 손봐야 하는 맥락이면 사용을 고려한다.
---

# Portfolio Access

## 목적

결제 플랫폼 포트폴리오 사이트는 payment-platform 코드 저장소가 아니라 **별도 blog 저장소**(Astro, `notes/blog`)에서 관리한다. 위키를 `<project>.wiki/` 별도 저장소로 운영하는 것과 같은 방식이다([[wiki-access]] 패턴). 예전엔 `payment-platform/docs/site/payment-flows.html`에 있었으나 2026-07-15 제거(`git rm`)됐고, 이제 정본은 blog 저장소 안에만 있다. 이 스킬은 사용자가 "포트폴리오" 트리거를 던지면 그 위치를 자동으로 찾아 접근한다.

## 작업 원칙 — 내용 위주 수정

이 스킬을 통한 포트폴리오 작업은 **콘텐츠 수정이 기본**이다. 명시적 요청이 없는 한 디자인·구조는 손대지 않는다.

- **주로 손대는 것**: 본문 카피·문구, 사실/수치(지표·시나리오 설명·설계 결정 텍스트·섹션 산문), 오타·표현 다듬기, JS 데이터 배열의 텍스트 값(시나리오·결정 등).
- **기본적으로 건드리지 않는 것**: 레이아웃·CSS 토큰·섹션 밴드·모션/애니메이션·SVG 다이어그램 엔진 등 디자인·구조·인터랙션. 기존 디자인은 유지한다.
- 디자인·구조·JS 동작을 바꾸는 큰 변경은 이 스킬의 기본 범위 밖이다 — 필요하면 먼저 사용자에게 범위를 확인하고 진행한다.

## 콘텐츠 문체 — 긴 산문 정리

줄줄이 길어지는 산문 벽은 `· ` 불렛으로 나누고 각 불렛을 **명사형으로 종결**한다. 서술문 나열보다 명사형 불렛이 기존 페이지 톤(담백·비AI체)에 맞고 읽기 쉽다.

- **분할 기법**: 각 블록 안에서 `<br>` + 선두 `· `로 나눈다. CSS·구조를 손대지 않는 순수 콘텐츠 편집이며, `<span>`·grid cell 안에서도 안전하다.
- **명사형 종결**: 밋밋한 주제어("상황")로 끝내지 말고 서술성 명사("상황 발생 가능", "~ 위험", "~ 차단", "~ 보장")로 끝낸다.
- **타이트하게**: 짧은 마무리 수식구는 별도 불렛로 떼지 말고 앞 절에 붙여 불렛 2~3개로 압축한다 (과분할 금지). 한 문장짜리 짧은 블록은 억지로 쪼개지 않는다.
- **내용 보존**: 사실·수치·고유명사는 그대로 둔다(검증된 값). 톤은 기존처럼 AI체를 지양한다.
- **JS 데이터 배열 주의**: 데이터 문자열은 `esc()`로 이스케이프돼 `<br>`를 바로 못 넣는다. 정본에는 이미 `escBr(s)`(= `esc(s).replace(/\n/g,'<br>')`) 헬퍼가 있고, 다음 필드는 이미 `escBr` 렌더로 연결돼 있다 — **데이터에 `\n· ` 로 불렛만 넣으면 된다**: DECISIONS(ctx/why/alt/tradeoff) · SOLVES(d) · LIMITS(what/why) · ALERTS(d) · SCENARIOS(impact). 그 외 필드(예: SCN hop `why.text`, RACES)를 불렛화하려면 해당 렌더의 `esc`→`escBr` 교체가 필요 = JS 소폭 수정이므로 먼저 사용자에게 확인한다.
- **라벨 뒤 줄바꿈**: 필드 앞에 굵은 라벨(`<b>지금</b>` 등)이 inline으로 붙는 자리(LIMITS·SCENARIOS impact)는 데이터를 `\n`으로 시작해 라벨과 첫 불렛을 분리한다. 라벨이 이미 block인 자리(DECISIONS의 `.d-line b`)는 선두 `\n` 불필요.

## 트리거 — 사용자 발화 예시

| 직접 트리거 | 간접 트리거 |
|:---:|:---:|
| "포트폴리오 수정", "포트폴리오 사이트", "포트폴리오 확인" | "그 결제 소개 페이지 고쳐" |
| "payment-flows", "index.html 고쳐" | "시스템 아키텍처 다이어그램 손봐" |
| "포트폴리오 배포", "포트폴리오에 반영" | "시나리오 극장 / 섹션 밴드 / 모션 조정" |
| "포트폴리오 라이트/다크 확인" | payment-platform엔 없는데 그 페이지를 찾을 때 |

## 위치 — 정본 파일과 dev 툴

### 1단계 — 고정 경로 시도 (기본)

> **구조(2026-07-15 Astro 이관 완료)**: 정본이 `public/`의 self-contained 단일 HTML → **Astro `src/` 페이지 + 역할별 모듈**로 분리됐다. 페이지 셸 `.astro`는 HTML만 남기고, 나머지는 모듈로:
>
> - **데이터**(콘텐츠 배열, **주 편집 대상**): `src/data/paymentPortfolio/*.ts` — arch·stages·states·modules·trace·scenarios·decisions·races·alerts·summary + `index.ts` 배럴
> - **CSS**(가끔): `src/styles/payment-portfolio.css`
> - **로직**(렌더/인터랙션 엔진, 평소 안 건드림): `src/scripts/portfolio/*.ts` — util·diagrams·scenario·panels·interactions·motion + `main.ts` 엔트리
>
> `.astro`의 `<script>`는 `import '../../scripts/portfolio/main'` 한 줄, CSS는 frontmatter에서 `import`. **콘텐츠 문구/수치 수정은 대부분 `src/data/paymentPortfolio/*.ts`에서 이뤄진다** — 아래 "콘텐츠 문체" 섹션의 필드도 이 데이터 모듈에 있다.

```
정본 셸  : /Users/hyoguoo/Repositories/hyoguoo/notes/blog/src/pages/payment-platform-portfolio/index.astro
데이터   : /Users/hyoguoo/Repositories/hyoguoo/notes/blog/src/data/paymentPortfolio/*.ts
CSS      : /Users/hyoguoo/Repositories/hyoguoo/notes/blog/src/styles/payment-portfolio.css
로직     : /Users/hyoguoo/Repositories/hyoguoo/notes/blog/src/scripts/portfolio/*.ts
빌드 산출: dist/payment-platform-portfolio/index.html (astro build) → URL /payment-platform-portfolio/
```

먼저 존재 확인:

```bash
ls -l /Users/hyoguoo/Repositories/hyoguoo/notes/blog/src/pages/payment-platform-portfolio/index.astro 2>/dev/null
```

존재하면 그 파일을 정본으로 채택하고 다음 단계는 건너뛴다.

### 2단계 — 탐색 (fallback)

고정 경로가 없으면 blog 저장소를 찾아 그 안에서 탐색한다.

```bash
# blog 저장소 후보
for d in \
  /Users/hyoguoo/Repositories/hyoguoo/notes/blog \
  "$(find /Users/hyoguoo/Repositories -maxdepth 3 -type d -name blog 2>/dev/null | head -1)"; do
  [ -f "$d/src/pages/payment-platform-portfolio/index.astro" ] && echo "$d/src/pages/payment-platform-portfolio/index.astro" && break
done
```

매칭된 파일을 정본으로 채택한다.

### 3단계 — 못 찾으면 명시

두 단계 모두 실패하면 추측하지 말고 사용자에게 시도한 경로를 나열해 보고하고, blog 저장소 클론 위치를 물어본다.

## 사용 패턴

### 읽기 / 검색 / 수정

- **Read / Edit** 툴을 정본 절대 경로로 호출한다. `Edit` 은 그대로 사용.
- 콘텐츠 검색: `grep -n "<keyword>" <정본 경로>` (단일 파일이라 `-r` 불필요). 보고 시 라인 번호를 같이 명시.

## 별도 저장소 주의

- 이 파일들은 **blog 저장소(`notes/blog`)** 소속이라 payment-platform 의 `git status` 에는 안 잡힌다.
- 수정 후 사용자가 **blog 저장소에서 별도로** `git add / commit / push` 해야 실제 사이트에 반영된다. 이 스킬은 파일 수정만 담당, commit / push 는 자동 실행하지 않는다.
- blog 는 **Astro** — `src/` 는 빌드 파이프라인을 타서 번들·배포된다(포트폴리오 로직 `src/scripts/portfolio/` 포함). `public/` 은 가공 없이 그대로 공개되므로, 배포 대상이 아닌 순수 dev 툴을 만든다면 `public/` 이 아닌 저장소 루트 `scripts/` 아래 둔다(`src/scripts/`와 혼동 주의).
- 원래 정본은 self-contained 단일 HTML이었으나 이제 CSS·데이터·로직이 모듈로 분리됐다(위 구조 참고). 다만 **외부 리소스 비링크 원칙은 유지** — 외부 이미지·폰트·스크립트를 새로 링크하지 않고, SVG 다이어그램은 로직 모듈에서 로컬 생성한다.

## 출력 표현 규칙

- 파일 인용 시 라인 번호를 명시. 경로는 blog 기준(예: `src/pages/payment-platform-portfolio/index.astro`, `src/data/paymentPortfolio/scenarios.ts`)으로 표기.
- 파일을 수정한 경우 마지막에 한 줄 안내: "이건 blog 저장소이므로 `notes/blog` 에서 commit / push 필요".
- 못 찾은 경우 시도한 경로를 모두 나열 — 추측 금지.
