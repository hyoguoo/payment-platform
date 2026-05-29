# discuss-critic-2

**Topic**: EOS-FOLLOWUP-CLEANUP
**Round**: 2
**Persona**: Critic

## Reasoning

Round 1 대비 delta 3건이 모두 회귀 없이 반영됐다. (1) pg_inbox cleanup 전면 제거가 산출물 전반(§2-1 in-scope 표, §3-C 결정 블록, §4 데이터 모델, §5 호환성, §6 멱등/장애, §8 검증, §9 적신호)에 일관 반영돼 끊긴 참조·표 불일치가 없다 — 남은 pg_inbox 언급은 전부 traceparent(D-TRACE) 또는 "cleanup 대상 아님" 명시뿐이며, 옛 D-PGINBOX-1/2·8일 보존 항목은 §9에서도 사라졌다. (2) D-SPLIT-3 교차 불변식 테스트가 결정(§3-B)·수락조건(§7)·장애 L-2(§6-2)·검증계획(§8)에 관찰 가능한 형태("한쪽만 드리프트 시 RED")로 들어갔고, 실제 enum(QUARANTINED/EXPIRED 둘 다 false)과 정합한다. (3) R1 minor 2건(product Flyway 불요 명시 / 포트 동명 주의)이 §2-3 line 126·§3-C line 179에 해소됐다. Gate checklist 5섹션 전 항목 yes 유지, critical/major finding 없음 → pass.

## Checklist judgement

### scope
- TOPIC UPPER-KEBAB-CASE: **yes** (line 1)
- 모듈/패키지 경계 명시: **yes** (§2-3 — payment/product/pg 패키지 경로. pg는 "cleanup 워커 없음" 명시로 범위 축소 반영)
- non-goals ≥1: **yes** (§2-2 6개 항목)
- 범위 밖 이슈 위임/포함: **yes** (§2-2 — TC-13-FOLLOW-*, TC-7, STOCK-COMPENSATION 위임)
  - 주: pg_inbox cleanup 제외는 사용자 지시로 non-goals/TODOS 미기재가 의도된 것 — 누락으로 지적하지 않음(호출자 명시)

### design decisions
- hexagonal layer 배치: **yes** (D-CLEAN-1 port=application/port/out, D-CLEAN-2 worker=infrastructure/scheduler, D-TRACE-2 OTel 추출=infrastructure, D-SPLIT-1 상태판별=domain)
- 포트 인터페이스 위치 결정: **yes** (D-CLEAN-1 — 기존 포트에 메서드 추가, 신규 포트 미생성. 동명 충돌 §3-C line 179에서 product 단독으로 못박음)
- 새 상태 추가 시 전이 다이어그램: **n/a** (§4 — 새 비즈니스 상태 없음. PaymentEventStatus 9개·PgInbox 5개 불변, 코드 대조 확인)
- 전체 결제 흐름 호환성 검토: **yes** (§5 — FOLLOW-6/5 동작보존, dedupe cleanup 시간분리, traceparent 비참여, pg confirm SoT 무삭제 명시)

### acceptance criteria
- 성공 조건 관찰가능: **yes** (§7 — grep 0건/compile 경고 0/만료행만 삭제/trace-id 연속성 + D-SPLIT-3 교차 불변식 RED)
- 실패 관찰 수단: **yes** (§7 실패 관찰 수단 — ERROR/WARN 로그 + Micrometer cleanup_deleted_total + D-SPLIT-3 테스트)

### verification plan
- 테스트 계층 결정: **yes** (§8 표 — 단위+Testcontainers, FOLLOW-5에 D-SPLIT-3 교차 불변식 명시 추가)
- 벤치마크 지표: **n/a** (§8 — 전 작업군 k6 불요, 측정 의존 non-goal)

### artifact
- 결정 사항 섹션 존재: **yes** (§3 — D-TM-1~4/D-SPLIT-1~3/D-CLEAN-1~4/D-TRACE-1~3. D-SPLIT-3 신설 확인, D-PGINBOX-* 제거 확인)

## Findings

critical/major/minor 없음. R1 minor 2건 해소, pg_inbox 제거 잔여 모순 없음, D-SPLIT-3 관찰 가능 형태로 안착. findings 비었으므로 decision=pass.

## §9 적신호 잔여 분류

D-TM-3(setKafkaAwareTransactionManager 버전 존재), D-TRACE-2(추출 소스 택일) 2건만 잔존 — 둘 다 plan 이월 가능, discuss 종료 blocker 아님. 옛 pg_inbox 8일 보존 항목은 제거와 함께 §9에서 사라져 정합.

## JSON
```json
{
  "stage": "discuss",
  "persona": "critic",
  "round": 2,
  "task_id": null,

  "decision": "pass",
  "reason_summary": "R1 대비 delta 3건(pg_inbox cleanup 전면 제거 / D-SPLIT-3 교차 불변식 테스트 신설 / R1 minor 2건 해소) 모두 회귀 없이 반영. Gate checklist 5섹션 전 항목 yes 유지, critical/major finding 없음. pg_inbox 제거가 in-scope표/결정/데이터모델/호환성/검증/§9에 일관 반영돼 끊긴 참조·표 불일치 없음. D-SPLIT-3가 수락조건·장애·검증에 관찰 가능 형태로 안착하고 실제 enum과 정합.",

  "checklist": {
    "source": "_shared/checklists/discuss-ready.md",
    "items": [
      { "section": "scope", "item": "TOPIC이 UPPER-KEBAB-CASE로 확정됨", "status": "yes", "evidence": "docs/topics/EOS-FOLLOWUP-CLEANUP.md line 1" },
      { "section": "scope", "item": "건드리는 모듈/패키지 경계 명시", "status": "yes", "evidence": "§2-3 line 125-128 — payment/product/pg 패키지. pg line 127 'cleanup 워커 없음' 으로 범위 축소 반영" },
      { "section": "scope", "item": "non-goals 최소 1개", "status": "yes", "evidence": "§2-2 line 116-121 — 6개 non-goal" },
      { "section": "scope", "item": "범위 밖 이슈 위임/포함", "status": "yes", "evidence": "§2-2 — TC-13-FOLLOW-*/TC-7/STOCK-COMPENSATION 위임. pg_inbox cleanup 제외는 사용자 지시로 미기재가 의도된 것(호출자 명시), 누락 아님" },
      { "section": "design decisions", "item": "hexagonal layer 배치 명시", "status": "yes", "evidence": "D-CLEAN-1(application/port/out), D-CLEAN-2(infrastructure/scheduler), D-TRACE-2/3(OTel=infrastructure), D-SPLIT-1(domain)" },
      { "section": "design decisions", "item": "포트 인터페이스 위치 결정", "status": "yes", "evidence": "D-CLEAN-1 표 — 기존 포트 메서드 추가. §3-C line 179 동명 EventDedupeStore 충돌을 product 단독으로 못박음(R1 C-MIN-2 해소)" },
      { "section": "design decisions", "item": "새 상태 추가 시 전이 다이어그램", "status": "n/a", "evidence": "§4 — 새 비즈니스 상태 없음. PaymentEventStatus 9상태(enum 코드 대조)·pg_inbox 5상태 불변" },
      { "section": "design decisions", "item": "전체 결제 흐름 호환성 검토", "status": "yes", "evidence": "§5 line 238-240 — FOLLOW-6/5 동작보존, cleanup 시간분리, traceparent 비참여, pg confirm SoT(pg_inbox) 무삭제 명시" },
      { "section": "acceptance criteria", "item": "성공 조건 관찰가능 형태", "status": "yes", "evidence": "§7 line 271-276 — grep 0건/compile 경고 0/만료행만 삭제/trace-id 연속성 + D-SPLIT-3 한쪽 드리프트 시 RED(line 272)" },
      { "section": "acceptance criteria", "item": "실패 관찰 수단", "status": "yes", "evidence": "§7 line 280-282 — ERROR/WARN 로그 + Micrometer cleanup_deleted_total + D-SPLIT-3 회귀 가드" },
      { "section": "verification plan", "item": "테스트 계층 결정", "status": "yes", "evidence": "§8 표 line 289 — FOLLOW-5에 D-SPLIT-3 교차 불변식 테스트 명시 추가, 단위+Testcontainers 정합" },
      { "section": "verification plan", "item": "벤치마크 지표", "status": "n/a", "evidence": "§8 line 294 — 전 작업군 k6 불요, 측정 의존 non-goal" },
      { "section": "artifact", "item": "결정 사항 섹션 존재", "status": "yes", "evidence": "§3 — D-TM-1~4/D-SPLIT-1~3/D-CLEAN-1~4/D-TRACE-1~3. D-SPLIT-3 신설(line 165-167), D-PGINBOX-* 제거 확인" }
    ],
    "total": 13,
    "passed": 11,
    "failed": 0,
    "not_applicable": 2
  },

  "scores": {
    "clarity": 0.93,
    "completeness": 0.90,
    "risk": 0.88,
    "testability": 0.93,
    "fit": 0.94,
    "mean": 0.916
  },

  "findings": [],

  "previous_round_ref": "discuss-critic-1.md",
  "delta": {
    "newly_passed": [],
    "newly_failed": [],
    "still_failing": [],
    "resolved_minor": [
      "C-MIN-1 (product Flyway 불요 명시) — §2-3 line 126에서 'Flyway 마이그레이션 불요(product는 db/schema 디렉토리 사용)' 명시로 해소",
      "C-MIN-2 (포트 동명 주의) — §3-C line 179 '포트 동명 주의' 항목 신설, product의 EventDedupeStore 단독 + FQCN 구분 지시로 해소"
    ],
    "notable_changes": [
      "pg_inbox cleanup 전면 제거 — R1 Domain Expert critical(confirm 재배달 멱등 SoT) 반영. in-scope표/결정/§4/§5/§6/§8/§9 전반 일관, 끊긴 참조·표 불일치 없음",
      "D-SPLIT-3 신설 — 두 가드(canApplyConfirmResult/canCompensateStock) QUARANTINED/EXPIRED 답 동조 교차 불변식 테스트. 수락조건(§7)·장애 L-2·검증(§8)에 관찰 가능 형태 안착, 실제 enum과 정합"
    ]
  },

  "unstuck_suggestion": null
}
```
