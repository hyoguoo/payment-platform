---
name: review
description: >
  payment-platform 프로젝트의 변경 사항을 구조적으로 코드 리뷰한다.
  "리뷰", "코드 리뷰", "review", "check my changes", "뭐 문제 없어?", "looks good?" 등
  변경 사항 검토를 요청할 때 반드시 이 스킬을 사용한다.
  캐주얼한 요청이더라도 Critic + Domain Expert 페르소나로 1라운드 구조적 리뷰를 실행한다.
---

# Code Review (단독 호출)

워크플로우 외부에서도 호출 가능한 단독 리뷰 스킬. **1 라운드만 수행**(토론 없음).
워크플로우 내부에서는 `workflow-review`가 이 스킬을 감싼다.

---

## 1. 리뷰 대상 파악
`git diff HEAD` + `git status`로 변경 파일 확인 후 각 파일 Read.
체크리스트만 적용하지 않고 내용을 먼저 이해한다.

## 2. 페르소나 호출 (1 라운드)

- **Critic** (`_shared/personas/critic.md`)
  - 아키텍처 / 컨벤션 / 테스트 / 실행 디시플린
  - 체크리스트 기준: `_shared/checklists/code-ready.md` 중 해당 항목

- **Domain Expert** (`_shared/personas/domain-expert.md`)
  - 결제 도메인 리스크 (멱등성, 상태 머신, PII, race window, 외부 PG)
  - 단독 호출 시에는 항상 실행

두 페르소나 모두 `qa-round.md` 스키마로 출력.

## 3. 판정 집계

아래 형식으로 사용자에게 보고:

```
[critical] path/to/File.java:42 — 한 문장 설명
  이유: ...
  수정: ...

[major] path/to/File.java:15 — ...
  이유: ...
  수정: ...

[minor] path/to/File.java:8 — 관찰 사항
```

## 4. 마무리 요약

```
---
요약: critical N건, major N건, minor N건
판정: PASS (critical 0건) | FAIL (critical N건 — 머지 전 반드시 수정)
```

## 참고 — 주요 판정 관점

페르소나 파일의 상세 규칙을 따르되, 핵심 관점:

**아키텍처** — `Presentation → Application → Domain ← Infrastructure`. 포트 위치, 모듈 간 직접 임포트 금지.

**컨벤션** — `@Data` 금지, `create()`/`of()` 팩토리, `LogFmt`, `catch (Exception e)` 제한, `null` 반환 금지.

**테스트** — Fake 우선, `@MockBean` 금지, `@ParameterizedTest @EnumSource`로 상태 전환 커버.

**도메인 리스크** — 보상 트랜잭션 멱등성, `executePayment` race window, PII 로그 노출, 상태 머신 위반, `existsByOrderId` 가드, `ALREADY_PROCESSED_PAYMENT` 처리, broad catch 금지.
