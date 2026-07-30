# 에이전트 컨텍스트 정비 구현 플랜

> 작성일: 2026-07-30

## 요약 브리핑

### Task 목록

| 태스크 | 하는 일 |
|:---:|:---:|
| 1 | 루트 지침에 dispatch 우선순위와 산출물 길이 기준 신설 |
| 2 | 코드 규칙 정본에 `@Data` 금지와 null 반환 금지(적용 범위 포함) 이식 |
| 3 | 리뷰어의 억제 지시 제거, effort 하향과 원복 조건을 한 커밋으로 |
| 4 | 도메인 검토자에게 입력 계약과 단계별 체크리스트 매핑 신설 |
| 5 | 구현 에이전트에 입력 계약 신설, 컨벤션 항목은 정본 포인터로 |
| 6 | 도메인 검토자 배차 조건을 체크리스트 정본으로 흡수 |
| 7 | 리뷰 체크리스트에서 린트가 잡는 항목만 제거 |
| 8 | 개발 흐름과 커밋 타입 매핑을 각 정본으로 확정 |
| 9 | 최종 검증 커맨드 존치, 다중 서비스 재실행·라이브 검증 원칙 이식 |
| 10 | 문서 작성 컨벤션을 주제별 세 파일로 분할 |
| 11 | 문체 규칙 사본 제거, 문서 검수 관점·라운드 축소 |
| 12 | 브리핑 플로우차트 규칙 완화와 도메인 예외 명시 |
| 13 | 워크플로우 스킬의 dispatch 예시 제거와 라벨 서술 축약 |
| 14 | 후속 위임 항목 기록 (정적 분석 규칙, 재확인, CI 편입) |
| 15 | 메모리에서 범위까지 동일한 중복 항목만 정리 |
| 16 | 검사 스크립트 — 참조·구조 판정 |
| 17 | 검사 스크립트 — 중복·다이어그램 판정과 최종 스캔 |
| 18 | 자동 점검 결과와 대조 |

### 실행 순서와 의존

```mermaid
flowchart LR
    T1[1. 루트 지침] --> T16
    T2[2. 코드 규칙 정본] --> T5[5. 구현 에이전트]
    T3[3. 리뷰어 + 원복 조건] --> T4[4. 도메인 검토자]
    T4 --> T13[13. dispatch 정리]
    T5 --> T13
    T6[6. 배차 조건 정본] --> T12[12. 브리핑 완화]
    T7[7. 체크리스트] --> T16
    T8[8. TDD 사이클 정본] --> T16
    T9[9. 최종 검증 + 이식] --> T15[15. 메모리 정리]
    T10[10. 컨벤션 분할] --> T11[11. 사본 제거]
    T10 --> T13
    T11 --> T16
    T12 --> T16
    T13 --> T16
    T14[14. 후속 위임] --> T16
    T15 --> T16
    T16[16. 검사 스크립트 - 참조] --> T17[17. 검사 스크립트 - 중복]
    T17 --> T18[18. 자동 점검 대조]

    classDef risk fill:#f8d7da,stroke:#c00
    class T2,T3,T4,T6,T7,T9,T12,T15 risk
```

붉은 노드는 결제 도메인 방어망을 건드리는 태스크다. 정본 이식이 포인터화보다 먼저 오도록 의존을 걸었고, 이식과 축약이 한 커밋에 묶이는 자리(3·6)는 중간에 멈춰도 절반만 반영되지 않는다.

### 트레이드오프와 후속

- 구현 에이전트가 컨벤션 파일을 한 번 더 열어야 한다 — 시작 시 읽기 단계를 명시해 상쇄
- 정적 분석 규칙 신설과 검사 스크립트의 CI 편입은 이번 범위 밖, 후속 항목으로 기록
- effort 하향의 실제 영향은 첫 도메인 인접 토픽에서 확인하고, 저하가 보이면 원복

---

## 목표

지침 컨텍스트의 상충 지시가 사라지고, 중복 규칙이 정본 한 곳 + 포인터로 수렴하며, 검사 스크립트가 그 상태를 0건으로 판정하면 이 플랜이 끝난다.

## 컨텍스트

- 설계 문서: `docs/topics/AGENT-CONTEXT-OVERHAUL.md`
- 주요 변경 파일: `CLAUDE.md`, `.claude/skills/**`, `.claude/agents/*.md`, `.claude/skills/_shared/{checklists,conventions}/**`, `docs/context/conventions/code-style.md`, `docs/context/{CONCERNS,TODOS,TESTING}.md`, `scripts/check-agent-docs.py`, 메모리 디렉토리

## 진행 상황

- [x] Task 1: 루트 지침 상충 해소와 산출물 길이 기준
- [x] Task 2: 코드 규칙 정본 보강
- [x] Task 3: reviewer 정비와 effort 원복 조건 등재
- [x] Task 4: domain-expert 입력 계약 신설
- [x] Task 5: implementer 입력 계약과 컨벤션 포인터화
- [x] Task 6: 도메인 검토자 배차 조건 정본화
- [x] Task 7: 체크리스트 정리
- [x] Task 8: TDD 사이클 정본 정리
- [ ] Task 9: ship 검증 절차 존치와 학습 조건 이식
- [ ] Task 10: 문서 작성 컨벤션 분할
- [ ] Task 11: 문체 규칙 사본 제거와 검수 축소
- [ ] Task 12: 브리핑 원칙 완화
- [ ] Task 13: 워크플로우 스킬 dispatch 정리
- [ ] Task 14: 후속 위임 항목 기록
- [ ] Task 15: 메모리 정리
- [ ] Task 16: 검사 스크립트 — 참조·구조 판정
- [ ] Task 17: 검사 스크립트 — 중복·다이어그램 판정과 최종 스캔
- [ ] Task 18: 자동 점검 대조

## 태스크

### Task 1: 루트 지침 상충 해소와 산출물 길이 기준 [tdd=false] [domain_risk=false]

**구현**

- `CLAUDE.md`에 dispatch 우선순위 선언 1문장 추가 — 워크플로우 스킬이 규정한 게이트·구현 dispatch는 사용자가 승인한 절차이며, 서브에이전트 호출을 일반적으로 제한하는 지시보다 우선한다
- `CLAUDE.md`에 산출물 길이 기준 1줄 추가 — 문서 분량을 작업에 맞추고 채워넣기용 섹션을 만들지 않는다
- 세션 설정의 호출 제한 문구는 저장소 밖이라 수정 대상이 아니며, 사용자 안내 사항으로 완료 결과에 기록

**완료 기준**

- `CLAUDE.md`에서 dispatch 우선순위와 길이 기준이 각각 1줄로 확인됨
- Workflow 섹션의 "서브에이전트는 독립 시각이 가치 있는 곳에만 쓴다" 문장과 새 선언이 같은 방향임을 두 문장 대조로 확인 — 새 선언은 워크플로우가 규정한 dispatch에 한정되고, 무제한 위임을 허용하지 않는다

**완료 결과**
> `CLAUDE.md` Workflow 절에 두 문장을 신설했다.
>
> - Dispatch 우선순위: "워크플로우 스킬이 규정한 게이트 판정·구현 dispatch는 사용자가 승인한 절차이며, 서브에이전트 호출을 일반적으로 제한하는 지시보다 우선한다." — 바로 위 "서브에이전트는 독립 시각이 가치 있는 곳에만 쓴다 — 게이트 판정·코드 리뷰(reviewer, domain-expert)와 태스크 구현(implementer)" 문장과 대조하면, 새 선언이 가리키는 dispatch 범위(게이트 판정·구현)가 그 문장이 이미 한정한 두 용도와 정확히 일치한다. 무제한 위임을 허용하지 않는다.
> - 산출물 길이: "문서 분량은 작업 내용에 맞추고, 채워넣기용 섹션을 만들지 않는다."
>
> 세션 설정의 호출 제한 문구는 저장소 밖이라 수정 대상이 아니다 — 사용자에게 별도 안내: 세션 지침에 "요청 없으면 서브에이전트를 부르지 마라"류 문구가 있다면, 워크플로우 dispatch(게이트·구현)는 이번에 신설한 예외 선언으로 커버되니 세션 설정 자체를 고칠 필요는 없다.

---

### Task 2: 코드 규칙 정본 보강 [tdd=false] [domain_risk=true]

**구현**

- `docs/context/conventions/code-style.md` 안티패턴 절에 `@Data` 금지 추가
- 같은 절에 null 반환 금지 추가하되 적용 범위를 함께 명시 — 공개 유스케이스·포트의 반환값에 한정하고, 도메인 상태로서의 null(금액 미수신 판정 등) · wire 계약 DTO 필드(비동기 메시지·HTTP 응답) · private 매핑/탐색 헬퍼는 제외
- 제외 대상은 실제 코드 사례로 근거를 남긴다 — 금액 불일치 판정 경로, 메시지 페이로드의 nullable 필드, aspect 내부의 파라미터 탐색 헬퍼

**완료 기준**

- 두 규칙이 `code-style.md`에 존재하고, null 규칙에 적용 범위와 세 갈래 제외 대상이 함께 적혀 있음
- 기존 코드의 정당한 null 사용 3종(금액 판정 가드 · 메시지·응답 DTO 필드 · private 헬퍼)이 새 문구에 걸리지 않음을 사례 대조로 확인

**완료 결과**
> `docs/context/conventions/code-style.md` 안티패턴 회피 절에 두 룰을 추가했다.
>
> - `@Data` 금지 — 뭉치 애너테이션의 setter 노출·연관관계 순환 참조 위험을 이유로, 개별 애너테이션(`@Getter` + 팩토리/`@Builder`, VO 는 `@EqualsAndHashCode`/`@ToString`)으로 대체하도록 명시.
> - null 반환 금지 — 적용 범위를 **공개 유스케이스·포트의 반환값**으로 한정하고, 세 갈래 제외 대상을 실제 코드 사례와 함께 적었다.
>   - 도메인 상태로서의 null: `PaymentConfirmResultUseCase.isAmountMismatch`(수신 금액 null 을 "미수신 → 불일치"로 판정)
>   - wire 계약 DTO 필드: `ConfirmedEventMessage`(payment-service)/`ConfirmedEventPayload`(pg-service) 의 `amount`/`approvedAt`/`reasonCode`, 관리자 조회 응답 `PgAttemptEntryViewResponse` 의 `attemptNo`/`publishedAt`
>   - private 탐색 헬퍼: `DomainEventLoggingAspect.findReasonParameter`(`@Reason` 파라미터 미발견 시 null 반환, 호출부가 즉시 기본값으로 흡수)
>
> **사례 대조 결과** — 세 파일을 직접 열어 확인했다.
> - `isAmountMismatch`(`payment-service/.../PaymentConfirmResultUseCase.java`)는 `private static boolean` 메서드로, null 을 반환하지 않고 nullable 파라미터를 받아 도메인 판정에 쓴다 — 새 룰이 막는 대상(공개 유스케이스·포트의 null 반환)이 아니라 애초에 룰 범위 밖이며, 명시적으로 예외 사례에도 올려 이후 혼동을 막았다.
> - `ConfirmedEventMessage`/`ConfirmedEventPayload`는 record 필드로 `amount`/`approvedAt`/`reasonCode`가 상태별 nullable — 유스케이스·포트 반환값이 아닌 wire DTO 필드라 룰 범위 밖. `PgAttemptEntryViewResponse`도 `@Builder` 로 조립되는 응답 DTO 필드(`attemptNo`/`publishedAt`)라 동일하게 범위 밖.
> - `DomainEventLoggingAspect.findReasonParameter`는 `private String` 헬퍼로 클래스 내부에서만 호출되고 호출부(`processResultAndPublishEvent`)가 `reason != null ? reason : 기본값`으로 즉시 처리 — private 헬퍼 제외 대상에 정확히 부합.
>
> 세 부류 모두 새 문구에 걸리지 않음을 확인했다.

---

### Task 3: reviewer 정비와 effort 원복 조건 등재 [tdd=false] [domain_risk=true]

**구현**

- `.claude/agents/reviewer.md`에서 억제 지시("영향 없는 취향 문제는 침묵이 낫다") 제거
- 대체 지침 추가 — 발견한 것은 모두 올리되 severity로 구분한다(영향 없는 취향 문제는 minor)
- 검토 방법 2항(도메인 리스크 escalation 의무)은 문구 그대로 보존
- frontmatter `effort: xhigh` → `high`
- 같은 커밋에서 `docs/context/CONCERNS.md`에 항목 신설 — 하향 근거, 도메인 인접 diff 오분류 사각, 원복 조건(사후 배석에서 새 critical·major 발생 시 xhigh 복귀)

**완료 기준**

- 억제 문구가 없고 severity 분류 지침이 있음
- 검토 방법 2항이 변경 전 문구와 동일
- frontmatter가 `effort: high`
- `CONCERNS.md`에 원복 조건이 존재하며, effort 하향과 같은 커밋에 포함됨 — 하향만 먼저 반영되는 구간을 만들지 않는다

**완료 결과**
> `.claude/agents/reviewer.md`에서 억제 지시("스타일 트집으로 finding 수를 부풀리지 않는다. 영향 없는 취향 문제는 침묵이 낫다")를 제거하고, 검토 방법 4항을 "발견한 것은 모두 finding으로 올리되 severity로 구분한다 — 영향 없는 취향 문제는 minor로 분류한다"로 대체했다.
>
> **검토 방법 2항 문구 대조** — 편집 전후 동일함을 확인했다: "체크리스트에 없더라도 실질 결함이 보이면 finding으로 올린다 — 단, 결제 도메인 리스크(상태 전이·멱등성·race 등)는 Domain Expert의 영역이므로 명백한 것만 짚고 깊이 파지 않는다. Domain Expert가 이번 라운드에 미배석인데 도메인 리스크 의심이 보이면, 깊이 파는 대신 domain-expert 호출 권고를 finding으로 올린다." — 이 항목은 이번 편집에서 손대지 않았다.
>
> frontmatter `effort`를 `xhigh`에서 `high`로 낮췄다.
>
> 같은 커밋에서 `docs/context/CONCERNS.md` Medium 섹션에 `C-11. Reviewer effort 하향(xhigh → high) — 도메인 인접 diff 오분류 사각`을 신설했다 — 하향 근거(검토 방법 2항의 역할 분리: 도메인 리스크 깊은 판정은 Domain Expert 몫), 사각(Domain Expert 미배석 라운드에서 도메인 인접 diff 오분류 위험 증가), 원복 조건(사후 Domain Expert 배석 라운드에서 Reviewer가 놓쳤던 critical·major 도메인 finding이 새로 발견되면 `effort: high`를 `xhigh`로 즉시 복귀)을 명시했다. 헤더의 최종 갱신 시점을 2026-07-30으로 갱신하고 신규 항목 요약을 이전 이력 위에 이어 붙였다.
>
> effort 하향과 `CONCERNS.md` 원복 조건 등재는 계획대로 같은 커밋에 포함했다 — 하향만 반영된 상태로 중단되는 구간은 없다.

---

### Task 4: domain-expert 입력 계약 신설 [tdd=false] [domain_risk=true]

**선행**: Task 3 (`CONCERNS.md` 항목이 있어야 선행 읽기에 추가 가능)

**구현**

- `.claude/agents/domain-expert.md`에 `reviewer.md`와 동등한 필수 입력 절 신설 — 호출자 제공 항목(stage · topic · 검토 대상 · 체크리스트 경로 · 참고 입력)과 미비 시 거부 규칙
- stage별 체크리스트 매핑을 표로 흡수 — discuss는 `discuss-ready.md` domain risk 섹션, plan은 `plan-ready.md` domain risk 섹션, ship·단독은 `code-ready.md` domain risk 섹션
- 필수 선행 읽기에 `docs/context/CONCERNS.md` 추가 — effort 원복 조건이 매 도메인 판정에서 재확인되도록
- frontmatter `effort: xhigh` 유지

**완료 기준**

- 필수 입력 절과 거부 규칙이 존재
- stage별 매핑 표가 실제 체크리스트 파일·섹션명과 일치
- 필수 선행 읽기에 `CONCERNS.md`가 포함됨
- frontmatter가 `effort: xhigh`

**완료 결과**
> `.claude/agents/domain-expert.md`에 `reviewer.md`와 동등한 "필수 입력 (호출자가 제공)" 절을 신설했다 — `stage`(discuss/plan/ship/standalone) · `topic`(단독 리뷰는 생략 가능) · 검토 대상(문서 경로 또는 diff 범위) · 체크리스트 경로 · 참고 입력. 마지막 줄에 reviewer.md와 동일한 강도의 거부 규칙을 그대로 넣었다: "입력이 빠지면 추측하지 말고 거부하고 무엇이 필요한지 반환한다."
>
> stage별 체크리스트 매핑은 표로 흡수했다 — 세 파일을 직접 열어 실제 섹션 제목과 대조 확인:
> - discuss → `.claude/skills/_shared/checklists/discuss-ready.md` `## domain risk (Domain Expert 전용)`
> - plan → `.claude/skills/_shared/checklists/plan-ready.md` `## domain risk (Domain Expert 전용 — 도메인 리스크 토픽만)`
> - ship / standalone → `.claude/skills/_shared/checklists/code-ready.md` `## domain risk (Domain Expert 전용)`
>
> 필수 선행 읽기 목록에 `docs/context/CONCERNS.md`를 추가했다 — Task 3에서 등재한 C-11(effort 원복 조건)이 매 도메인 판정에서 재확인되도록.
>
> frontmatter `effort: xhigh`는 손대지 않고 그대로 유지했다.

---

### Task 5: implementer 입력 계약과 컨벤션 포인터화 [tdd=false] [domain_risk=false]

**선행**: Task 2 (정본에 규칙이 실재해야 포인터화 가능)

**구현**

- `.claude/agents/implementer.md`에 필수 입력 절 신설 — 호출자 제공 항목(모드 · topic · task_id · tdd 플래그 · 플랜/상태 경로)과 미비 시 거부 규칙
- 코드 패턴 절과 금지 목록에 흩어진 컨벤션 항목(`var` · `catch (Exception)` · null 반환 · `@Data` · try 블록 외부 변수 재할당)을 `code-style.md` 포인터로 축약
- 시작 시 `code-style.md`를 읽는 단계를 명시 — 현재 `commit.md`에만 있는 "시작 시 읽는다" 문장과 동등한 강도로 둔다. 포인터화의 전제가 이 읽기다
- 워크플로우 고유 금지(범위 밖 수정, `git add -A`, amend, 인접 태스크 침범)는 그대로 존치

**완료 기준**

- 필수 입력 절이 존재하고 컨벤션 항목이 포인터 한 줄로 축약됨
- 시작 시 `code-style.md`를 읽는 문장이 `commit.md` 읽기와 같은 위치·강도로 존재
- 워크플로우 고유 금지 항목은 문구 유지
- 포인터가 가리키는 규칙 5종이 `code-style.md`에 모두 실재
- frontmatter가 `effort: high` 유지

**완료 결과**
> `.claude/agents/implementer.md`에 "필수 입력 (호출자가 제공)" 절을 신설했다 — 모드 1(PLAN 태스크 실행)은 `mode`/`topic`/`task_id`/`tdd`/PLAN.md·STATE.md 경로, 모드 2(리뷰 finding 수정)는 `mode`/findings 목록/관련 태스크의 tdd 성격을 열거하고, 마지막 줄에 reviewer.md·domain-expert.md와 동일한 강도의 거부 규칙("입력이 빠지면 추측하지 말고 거부하고 무엇이 필요한지 반환한다")을 넣었다.
>
> 시작 시 읽는 문장은 기존 "커밋 규칙(`.claude/skills/_shared/conventions/commit.md`)을 읽는다" 한 문장에 코드 스타일 컨벤션(`docs/context/conventions/code-style.md`)을 같은 문장 안에 병기해, 같은 위치·같은 강도(동일 동사 "읽는다")로 신설했다.
>
> 포인터화 전 5종이 `code-style.md`에 모두 실재하는지 먼저 확인했다 — 안티패턴 회피 절에 `var` 키워드 금지, `catch (Exception)` swallow 금지(→ error-logging.md), 공개 유스케이스·포트 반환값의 null 반환 금지, `@Data` 금지가 있고, 별도 "Try 블록 패턴" 절에 try 블록 외부 변수 재할당 금지가 있다. 5종 모두 확인 후 축약했다.
>
> - **코드 패턴** 절: Lombok 줄 끝의 "`@Data` 금지" 언급을 제거했다 (이제 금지 절 포인터가 커버).
> - **금지 (타협 불가)** 절: 개별 서술 4줄(`var` · try 재할당 · `catch (Exception e)` · null 반환)을 한 줄 포인터로 합쳤다 — "코드 컨벤션 위반(`var` 키워드 · `catch (Exception e)` swallow · 공개 유스케이스·포트의 null 반환 · `@Data` · try 블록 내 외부 변수 재할당) — 기준은 `docs/context/conventions/code-style.md` 안티패턴 회피 절". 기존 `catch (Exception e)` 항목에 있던 "불가피하면 `handleUnknownFailure` 경유" 세부는 제거했다 — 코드베이스 전체에 `handleUnknownFailure` 메서드가 더 이상 존재하지 않는(과거 리팩터로 삭제된) 낡은 참조였고, `code-style.md`가 가리키는 `error-logging.md`의 현재 규칙("잡으면 LogFmt.error + 재throw 또는 명시적 fallback")으로 대체된다.
> - 워크플로우 고유 금지(테스트 없이 구현, 범위 밖 코드 수정, `git add -A`/`--amend`/`--no-verify`, 인접 태스크 침범)는 문구 그대로 유지했다.
>
> frontmatter `effort: high`는 손대지 않고 그대로 유지했다.

---

### Task 6: 도메인 검토자 배차 조건 정본화 [tdd=false] [domain_risk=true]

**구현**

- `discuss-ready.md`의 생략 조건에 "결제 도메인 동작(상태 전이·멱등성·복구·정산) 서술·정정" 열거를 흡수해 정본 문구 완성
- `workflow-discuss` 5절과 `workflow-ship` A1의 조건 서술을 정본 포인터로 축약 — 2갈래 판단 자체는 정본에서 읽는다
- 생략 시 사전 브리핑·리뷰 보고에 사유를 표기하는 규칙은 각 스킬에 남긴다(실행 시점 지시라 정본 이관 대상이 아님)

**완료 기준**

- `discuss-ready.md`에 2갈래 조건과 열거가 모두 존재
- 현재 `workflow-discuss` 5절의 예시절("문서 정정, 운영 런북, CONCERNS/TODOS 정리 포함")이 정본 문구에 문자 그대로 승계됨 — 이 예시절이 소스 변경 없는 메타 토픽을 포함 대상으로 판정하는 근거다
- 두 스킬에 조건 본문이 중복 서술되지 않고 포인터만 있음
- 생략 사유 표기 규칙은 스킬에 유지됨

**완료 결과**
> `discuss-ready.md` domain risk 섹션 머리말을 `workflow-discuss` 5절의 조건 본문(2갈래 조건 + 예시절)으로 대체해 정본으로 세웠다.
>
> - 소스 코드 **또는 런타임 설정**(알람 규칙·Kafka 설정·스케줄러 등) 변경을 계획 (한 줄이라도)
> - 산출물이 **결제 도메인 동작(상태 전이·멱등성·복구·정산)을 서술·정정** — 문서 정정, 운영 런북, CONCERNS/TODOS 정리 포함
>
> 예시절("문서 정정, 운영 런북, CONCERNS/TODOS 정리 포함")은 원문과 글자 단위로 대조해 그대로 옮겼다 — 이 절이 소스 변경 없는 메타 토픽(문서 정정 등)도 domain-expert 포함 대상으로 판정하는 근거이므로 누락하면 판정 범위가 좁아진다.
>
> `workflow-discuss` 5절은 조건 본문을 지우고 "`discuss-ready.md` domain risk 섹션 참조" 포인터 한 줄 + 생략 시 사전 브리핑 표기 지시(실행 시점 지시라 존치)로 축약했다.
>
> `workflow-ship` A1도 조건 본문을 지우고 같은 정본을 가리키는 포인터로 축약했다 — "`discuss-ready.md` domain risk 섹션의 2갈래 조건을 실제 diff에 적용한다"는 문장으로, discuss의 산출물 판정을 ship의 diff 판정으로 바꿔 적용하는 실행 디테일만 남기고 조건 자체는 재서술하지 않는다. 생략 시 리뷰 완료 보고에 표기하는 지시는 그대로 남겼다.
>
> 세 파일을 다시 읽어 대조한 결과 조건 본문이 `discuss-ready.md` 한 곳에만 있고, 나머지 두 스킬에는 중복 서술 없이 포인터만 남아 있음을 확인했다.

---

### Task 7: 체크리스트 정리 [tdd=false] [domain_risk=true]

**구현**

- `code-ready.md` execution discipline 항목에서 미사용 import를 빼고 "죽은 코드가 새로 생기지 않음"으로 좁힘 — 미사용 import는 린트 게이트가 잡는다
- convention 섹션의 나머지 항목은 존치 — 정적 분석 규칙이 없어 수동 판정이 유일한 수단
- domain risk 섹션 3종(`code-ready` 6항목 · `discuss-ready` 5항목 · `plan-ready` 3항목)은 건드리지 않음, `ship-ready.md`에는 신설하지 않음

**완료 기준**

- execution discipline 항목에서 미사용 import만 빠지고 죽은 코드 판정은 남음
- convention 섹션 항목 수 변화 없음
- `git diff -- .claude/skills/_shared/checklists/` 결과에서 세 파일의 domain risk 섹션 라인 범위에 hunk가 0건 — 자기 판정이 아니라 diff로 확인한다

**완료 결과**
> `code-ready.md` execution discipline 절의 항목을 "죽은 코드/미사용 import가 새로 생기지 않음"에서 "죽은 코드가 새로 생기지 않음"으로 좁혔다 — 미사용 import는 린트 게이트(spotbugs 등)가 잡고, 죽은 코드는 정적 분석이 없어 수동 판정만 남긴다.
>
> convention 섹션(Lombok 패턴 · `@AllArgsConstructor`+`@Builder` · LogFmt · null 반환 금지 · `catch (Exception e)` · `var` 금지, 6항목)은 손대지 않았다 — `var`·`@Data`·`catch (Exception)`·null 반환 모두 checkstyle에 대응 규칙이 없어 수동 판정이 유일한 검증 수단이기 때문이다.
>
> domain risk 섹션 3종은 한 글자도 건드리지 않았다. `ship-ready.md`에도 신설하지 않았다.
>
> **diff 대조 결과** — `git diff -- .claude/skills/_shared/checklists/` 실행 결과 hunk가 정확히 1개, `code-ready.md` execution discipline 절의 해당 한 줄만 잡혔다:
> ```
> - [ ] 죽은 코드/미사용 import가 새로 생기지 않음
> + [ ] 죽은 코드가 새로 생기지 않음
> ```
> `discuss-ready.md`·`plan-ready.md`는 diff 자체가 0건이었고, `code-ready.md`도 domain risk 섹션(37~45행) 및 convention 섹션(23~30행)에는 hunk가 잡히지 않았다.
>
> **Task 5 발견 사항 확인** — `code-ready.md` convention 절의 "`catch (Exception e)` 없음 (있다면 `handleUnknownFailure` 경유)" 항목은 여전히 남아 있다. 코드베이스 전체에 `handleUnknownFailure` 메서드가 존재하지 않는다(과거 리팩터로 삭제됨, Task 5 완료 결과에서도 동일하게 확인). 이번 태스크는 convention 섹션을 존치하기로 범위를 확정했으므로 손대지 않는다 — 정정이 필요하다는 판단만 남긴다. 실제 교체(현재 `error-logging.md` 규칙인 "잡으면 LogFmt.error + 재throw 또는 명시적 fallback"으로)는 이번 범위 밖이다.

---

### Task 8: TDD 사이클 정본 정리 [tdd=false] [domain_risk=false]

**구현**

- 개발 흐름(RED → GREEN → REFACTOR)은 `docs/context/conventions/testing.md`를 정본으로 확정
- 커밋 타입 매핑(`test:` / `feat:` / `refactor:`)은 `_shared/conventions/commit.md`를 정본으로 확정
- `CLAUDE.md` Coding Rules · `docs/context/TESTING.md` TDD 흐름 절 · `implementer.md` 모드 1 서술을 각 정본 포인터로 축약
- `implementer.md`의 실행 순서 지시(커밋 시점, 테스트 실행 시점)는 실행 절차라 존치

**완료 기준**

- 두 정본에 각 축의 규칙이 존재
- 나머지 세 곳에 사이클 본문이 중복 서술되지 않음
- 실행 순서 지시는 `implementer.md`에 유지됨

**완료 결과**
> 두 정본을 확정하고 서로 상호 포인터를 걸었다.
>
> - `docs/context/conventions/testing.md` "TDD 흐름 (정본)" 절 — RED(실패 테스트 먼저, 도메인 entity는 `@ParameterizedTest @EnumSource` 유효/무효 커버·use case는 Mockito 단위 테스트 먼저) → GREEN(최소 구현 + PLAN.md/STATE.md 갱신) → REFACTOR(선택, 변경 없으면 생략) 흐름 본문을 온전히 싣고, 매 태스크 완료 후 `./gradlew test` 확인 원칙도 함께 뒀다. 기존에 없던 use case 서술(`CLAUDE.md`에만 있던 것)을 이 절로 이관해 개발 흐름 축이 한 곳에 완전히 모이도록 보강했다. 마지막 줄에 커밋 타입 매핑은 `commit.md`가 정본이라는 포인터를 걸었다.
> - `.claude/skills/_shared/conventions/commit.md` "TDD 커밋 분리 (정본)" 절 — 기존 `test:`/`feat:`/`refactor:` 매핑은 이미 온전했으므로 그대로 두고, 첫 줄에 개발 흐름은 `testing.md`가 정본이라는 역방향 포인터만 추가했다.
>
> 나머지 세 곳을 포인터로 축약했다.
> - `CLAUDE.md` Coding Rules 1항 — 도메인 entity/use case 커버 서술을 제거하고 "개발 흐름은 testing.md TDD 흐름 절, 커밋 타입 매핑은 commit.md 참고"로 축약. Rule 2(Minimal change)·Rule 3(Verify)는 TDD 사이클 서술이 아니라 손대지 않았다.
> - `docs/context/TESTING.md` "TDD 흐름" 절 — RED/GREEN/REFACTOR 단계 나열과 커밋 타입을 지우고 두 정본을 가리키는 포인터 한 줄로 축약.
> - `.claude/agents/implementer.md` 모드 1 — RED/GREEN/REFACTOR 단계별 서술(`test:`/`feat:`/`refactor:` 커밋 시점, `./gradlew test` 실행 시점, PLAN.md/STATE.md 갱신 시점)은 이 태스크 실행에 한정된 실행 순서 지시이므로 그대로 유지했다. 대신 그 앞에 "TDD 사이클 정의는 testing.md, 커밋 타입 매핑은 commit.md가 정본이고 아래는 실행 절차(정본 이관 대상 아님)" 포인터 한 줄을 신설해, 실행 절차와 정본 정의를 명확히 구분했다.
>
> `grep -rn "RED.*GREEN.*REFACTOR\|REFACTOR.*선택"`로 대조한 결과, 사이클 본문이 남아 있는 곳은 두 정본(`testing.md`/`commit.md`)과 `implementer.md`의 실행 절차 서술뿐이었다 — `CLAUDE.md`·`TESTING.md`에는 포인터만 남았다.

---

### Task 9: ship 검증 절차 존치와 학습 조건 이식 [tdd=false] [domain_risk=true]

**구현**

- `workflow-ship` B1의 통합테스트 `--rerun` 커맨드와 라이브 검증 조건부 판단을 본문 그대로 존치 — 포인터화 대상에서 제외
- B1과 `ship-ready.md` test & build 섹션에 다중 서비스 조건 이식 — 변경이 닿은 모든 서비스의 통합테스트를 재실행한다, 일부만 재실행하면 나머지는 캐시로 조용히 스킵된다
- 같은 두 곳에 라이브 검증 일반 원칙 이식 — 합성 테스트 통과를 검증 완료로 보지 않고, 가능하면 실제 주입으로 신호 경로 끝까지 관측한다
- `workflow-ship` A5에 산출물 역할 경계 1줄 명시 — 마크다운 브리핑은 설계·플랜 판단용, HTML 설명 페이지는 완료 후 변경 이해용

**완료 기준**

- B1에 `--rerun` 커맨드와 라이브 검증 조건이 본문으로 존재
- B1과 `ship-ready.md`에 다중 서비스 재실행 조건과 라이브 검증 일반 원칙이 존재
- `ship-ready.md`에 캐시 함정 경고가 남아 있음
- 두 산출물의 역할 경계가 1줄로 명시됨

**완료 결과**
> (execute에서 채움)

---

### Task 10: 문서 작성 컨벤션 분할 [tdd=false] [domain_risk=false]

**구현**

- `_shared/conventions/writing.md` 352줄을 주제별로 분할 — 문체(종결·문장 밀도·목소리) / 용어·식별자(4단계 룰·동의어·약어·즉석 라벨) / 표·다이어그램(정렬·Mermaid 금지 문자)
- 분할 후 원 파일은 세 파일을 가리키는 인덱스로 축약
- 기존 참조(스킬·체크리스트·`CLAUDE.md`)가 가리키는 경로를 새 구조에 맞게 갱신

**완료 기준**

- 세 파일이 존재하고 원 파일이 인덱스로 축약됨
- 분할 전후의 **최상위 불릿 개수와 코드 예시 블록 개수**가 일치 — 규칙 유실을 이 두 수치로 판정한다
- 기존 참조가 모두 유효한 경로를 가리킴

**완료 결과**
> (execute에서 채움)

---

### Task 11: 문체 규칙 사본 제거와 검수 축소 [tdd=false] [domain_risk=false]

**선행**: Task 10 (분할된 파일이 있어야 포인터 가능)

**구현**

- `writing/SKILL.md`의 컨벤션 요약 40줄을 제거하고 분할 파일 포인터로 대체 — 작성 절차는 유지
- `doc-review/SKILL.md` 관점 1 표를 분할된 문체·용어 파일 포인터로 대체하고 판정 형식만 남김
- 검수 관점을 문서 유형별 필수만 dispatch하도록 정리, 루프 상한 3 → 2

**완료 기준**

- 두 스킬에 문체 규칙 본문이 없고 포인터만 있음
- 루프 상한이 2로 조정됨
- 문서 유형별 관점 적용 표가 남아 있음

**완료 결과**
> (execute에서 채움)

---

### Task 12: 브리핑 원칙 완화 [tdd=false] [domain_risk=true]

**구현**

- `workflow/SKILL.md` 브리핑 원칙의 "간략화 금지, 전체 경로"를 "이해에 필요한 분기·예외를 빠짐없이"로 완화
- 같은 자리에 도메인 예외를 **두 단계 모두에서 걸리는 형태로** 명시 — 도메인 검토자 포함 대상 토픽(`discuss-ready.md`의 2갈래 조건)이거나 `domain_risk=true` 태스크를 가진 토픽이면, 상태 전이·예외 분기는 전체 경로를 유지한다
- 브리핑 원칙은 4단계 공통이고 discuss 시점에는 아직 PLAN.md가 없어 태스크 플래그가 존재하지 않는다 — 그래서 discuss에서도 판정 가능한 배차 조건을 함께 트리거로 둔다

**완료 기준**

- 완화된 문구와 도메인 예외가 같은 절에 함께 존재
- 예외 트리거가 두 갈래(배차 조건 / 태스크 플래그)로 적혀 있어 discuss 단계에서도 발동함
- 태스크 플래그 쪽 어휘가 `workflow-plan/SKILL.md`의 `domain_risk=true` 판정 기준과 일치

**완료 결과**
> (execute에서 채움)

---

### Task 13: 워크플로우 스킬 dispatch 정리 [tdd=false] [domain_risk=false]

**선행**: Task 4·5 (에이전트 입력 계약), Task 10 (분할 파일)

**구현**

- `workflow-discuss` · `workflow-plan` · `workflow-ship` · `review`의 dispatch 예시 블록 제거 — 에이전트 정의의 필수 입력 계약을 참조하고, 각 스킬에는 무엇을 넘길지만 남긴다
- `workflow-discuss` 4절의 즉석 코드 라벨 서술을 분할된 용어 파일 포인터로 축약

**완료 기준**

- 네 스킬에 `Agent(...)` 예시 블록이 없고 필수 입력 참조로 대체됨
- 각 스킬에 stage별로 넘길 대상·체크리스트가 무엇인지는 남아 있음
- 즉석 코드 라벨 서술이 포인터 한 줄로 축약됨

**완료 결과**
> (execute에서 채움)

---

### Task 14: 후속 위임 항목 기록 [tdd=false] [domain_risk=false]

**구현**

- `docs/context/TODOS.md`에 재확인 후속 항목 기록 — 적용 후 첫 도메인 인접 토픽에서 배석 판단 근거 대조, `CONCERNS.md` 항목 연결
- 같은 문서에 정적 분석 규칙 신설 위임 항목 기록 — `var` · `@Data` · null 반환 · `catch (Exception)`을 checkstyle·ArchUnit으로 강제하는 작업
- 검사 스크립트의 CI 편입도 후속 항목으로 기록
- 갱신한 문서의 최종 갱신 시점을 본문과 동기화

**완료 기준**

- `TODOS.md`에 세 항목이 존재하고 각각 근거 문서 경로를 가리킴
- 최종 갱신 시점이 동기화됨

**완료 결과**
> (execute에서 채움)

---

### Task 15: 메모리 정리 [tdd=false] [domain_risk=true]

**선행**: Task 9 (누락 조건이 프로젝트 파일에 이식된 뒤라야 삭제 가능)

**구현**

- 각 메모리 항목을 프로젝트 파일과 대조해 적용 범위까지 동일한 것만 삭제
- Task 9에서 이식한 두 조건(다중 서비스 재실행, 라이브 검증 일반 원칙)은 이식 완료를 확인한 뒤 해당 메모리 항목 정리
- 범위가 더 넓은데 이식되지 않은 항목은 보존
- frontmatter `name`이 비어 있는 파일 보정
- `MEMORY.md` 인덱스를 결과에 맞게 갱신

**완료 기준**

- 삭제한 항목마다 대응하는 프로젝트 파일 경로가 완료 결과에 기록됨
- 이식이 필요했던 조건이 프로젝트 파일에 실재함을 확인한 뒤 삭제됨
- 부분 삭제한 파일은 이식되지 않은 서술(통합테스트 cold-start 재현 경위 등)이 그대로 남아 있음을 대조 확인
- 모든 메모리 파일의 frontmatter에 `name`이 있음

**완료 결과**
> (execute에서 채움)

---

### Task 16: 검사 스크립트 — 참조·구조 판정 [tdd=false] [domain_risk=false]

**구현**

- `scripts/check-agent-docs.py` 신설, 판정 3종 구현
  - 참조 무결성 — 마크다운 링크와 백틱 경로 중 파일 경로 형태인 것이 실제 파일로 해석되는지, 코드 예시·커맨드 인자는 제외
  - frontmatter 필수 필드 — 스킬은 `name`·`description`, 에이전트는 `name`·`description`·`model`·`tools`
  - 체크리스트 참조 — 스킬이 지정한 체크리스트 파일과 섹션 제목이 실재하는지
- 종료 코드로 작업을 막지 않는 정보 제공용

**완료 기준**

- 스크립트가 현재 저장소에서 실행되고 3종 판정 결과를 출력
- 깨진 참조 0건 — Task 1~15에서 갱신한 경로가 모두 유효
- 종료 코드가 판정 결과와 무관하게 0

**완료 결과**
> (execute에서 채움)

---

### Task 17: 검사 스크립트 — 중복·다이어그램 판정과 최종 스캔 [tdd=false] [domain_risk=false]

**선행**: Task 16 (같은 스크립트에 판정 추가)

**구현**

- 판정 3종 추가
  - 중복 규칙 — 문구 사전 기반 정확 매칭. 사전 항목은 정본으로 확정한 규칙에서 뽑는다(`var` 금지, `@Data` 금지, null 반환 금지, try 블록 외부 변수 재할당 금지, TDD 사이클, 배차 2갈래 조건, 문체 종결 규칙). 포인터 문장·실행 커맨드·존속 결정된 판정 체크리스트 섹션(`code-ready.md`의 convention · domain risk)은 매칭 제외
  - Mermaid 금지 문자 — 노드·엣지 라벨의 `{`·`}`·중간점·유니코드 화살표
  - 고아 문서 — `.claude/` 마크다운 중 어디서도 참조되지 않는 것. `SKILL.md`와 `agents/*.md`는 frontmatter 자동 탐색 대상이라 제외
- `ship-ready.md`에 스크립트 결과 확인 항목 추가

**완료 기준**

- 샘플 쌍 검증 — (a) 임시 파일에 `code-style.md`의 `var` 금지 문구를 본문으로 복제하면 중복으로 검출됨, (b) 다른 문서가 경로로 참조하는 체크리스트 파일(`_shared/checklists/code-ready.md` 등)은 고아로 검출되지 않음 — 카테고리 배제가 아니라 참조 그래프 판정이 동작하는지를 본다. 확인 후 임시 파일 제거
- 전체 저장소 스캔에서 중복 탐지 0건, 깨진 참조 0건
- `ship-ready.md`에 확인 항목이 추가됨

**완료 결과**
> (execute에서 채움)

---

### Task 18: 자동 점검 대조 [tdd=false] [domain_risk=false]

**구현**

- `/doctor` 실행 결과를 이번 정비 결과와 대조
- 채택할 지적만 반영하고, 기각한 지적은 사유와 함께 기록

**완료 기준**

- 대조 결과가 채택·기각 구분으로 정리됨
- 채택분이 반영되거나 후속 항목으로 위임됨

**완료 결과**
> (execute에서 채움)

---

## 결정 → 태스크 매핑

| 설계 결정 | 태스크 |
|:---:|:---:|
| 상충 해소 (루트 지침 예외 선언) | Task 1 |
| 산출물 길이 | Task 1 |
| 코드 규칙 정본 | Task 2 · Task 5 |
| 정본의 규칙 공백 | Task 2 |
| null 반환 금지의 적용 범위 | Task 2 |
| reviewer 억제 지시 | Task 3 |
| reviewer 검토 방법 2항 | Task 3 |
| effort | Task 3 · Task 4 · Task 5 |
| effort 원복 조건의 위치 | Task 3 · Task 4 · Task 14 |
| dispatch 예시 블록 | Task 4 · Task 5 · Task 13 |
| domain-expert 포함 조건 | Task 6 |
| 체크리스트 | Task 7 |
| 체크리스트 domain risk 섹션 | Task 7 |
| TDD 사이클 정본 | Task 8 |
| ship 최종 검증 절차 | Task 9 |
| 브리핑 형식 | Task 9 |
| `writing.md` | Task 10 · Task 11 |
| doc-review | Task 11 |
| 브리핑 플로우차트 | Task 12 |
| 즉석 코드 라벨 금지 서술 | Task 13 |
| 메모리 | Task 15 |
| 검증 전략 (검사 스크립트 6종 판정) | Task 16 · Task 17 |
| `/doctor` | Task 18 |

## 수락 조건 → 태스크 매핑

설계 문서 "수락 조건" 15개를 어느 태스크가 확인하는지 대조한다.

| 수락 조건 | 확인 태스크 |
|:---:|:---:|
| 중복 규칙 5종이 정본 1곳 + 포인터로 수렴, 중복 탐지 0건 | Task 17 |
| dispatch 우선순위가 `CLAUDE.md`에 1문장 | Task 1 |
| domain risk 섹션 3종 문구 보존 | Task 7 |
| execution discipline에서 미사용 import만 제거 | Task 7 |
| `workflow-ship` B1에 `--rerun`·라이브 검증 본문 존재 | Task 9 |
| `reviewer.md` 억제 지시 부재, 검토 방법 2항 존재 | Task 3 |
| 검사 스크립트 실행과 샘플 쌍 기대 판정 | Task 17 |
| 깨진 참조 0건 | Task 16 · Task 17 |
| `code-style.md`에 두 규칙 + null 적용 범위, 이후 포인터화 | Task 2 · Task 5 |
| `CONCERNS.md` 원복 트리거 + `TODOS.md` 후속 연결 | Task 3 · Task 14 |
| 에이전트 정의에 필수 입력 계약과 stage 매핑, 이후 예시 제거 | Task 4 · Task 5 · Task 13 |
| `doc-review` 관점 1 표 대체 | Task 11 |
| 에이전트 3종 effort 값 | Task 3 · Task 4 · Task 5 |
| 배차 조건 열거 흡수 후 스킬 포인터화 | Task 6 |
| 즉석 코드 라벨 서술 축약 | Task 13 |

## 리뷰 처리

> (ship 단계에서 채움 — finding별 채택/스킵 + 사유)
