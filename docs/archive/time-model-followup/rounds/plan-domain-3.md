# plan-domain-3

**Topic**: TIME-MODEL-FOLLOWUP
**Round**: 3
**Persona**: Domain Expert

## Reasoning
Round 2 의 critical(P14 BaseEntity Instant+datetime(6) 전환 태스크 본문 소실 + orphan 의존)과 minor(DM-2 경계 동치 미반영)가 둘 다 처방대로 반영됐다. P14 본문이 P13(DDL) 직후·P15 앞에 복원되며 다섯 AC 가 전부 존재하고, 전역 P14 참조가 실제 본문과 정합한다. P2 테스트 스펙에 `expires_at == now` 미만료 보존(차감 skip 멱등) 단정이 추가됐고 실제 DELETE SQL(strict less-than)과 정합한다. 잔여 차단 사유 없음 → pass.

## Domain risk checklist
- [x] discuss domain risk 대응 태스크 보유 — DM-F1→P4/P5, DM-F2→P10 매핑 유지.
- [x] 중복 방지 체크 경로 계획됨 — recordIfAbsent INSERT IGNORE(event_uuid PK) 멱등 SSOT. P2 새 테스트가 만료/미만료/경계동치 3분기로 차감 skip 멱등 가드.
- [x] 재시도 안전성 검증 태스크 존재 — P4 `commit_중복이벤트_...false반환시스킵`(commitToRdb 미호출)이 Kafka 재배달 시 재고 미차감 검증.
- [x] audit 불변 — P14 AC 가 `createdAt @Column(updatable=false)` 보존 + 리플렉션 단정(`createdAt_updatableFalse_보존()`)으로 회귀 가드.
- [x] 만료 cutoff 정밀도 — P13 DDL DATETIME(6) → P14 엔티티 datetime(6) 매핑, 순서 불변(P13→P14) 명시. created_at 기반 만료 cutoff·정산 앵커 서브초 정합 봉인.

## 도메인 관점 추가 검토
1. **[n/a — 반영 확인] P14 BaseEntity Instant+datetime(6) 전환 태스크 본문 복원 + orphan 의존 해소.** PLAN line 271 에 `### P14` 섹션 존재(`grep '^### P14'` 1건, Round 2 는 0건). 다섯 AC 전부 확인: (1) 3필드 `LocalDateTime`→`Instant`(line 280), (2) `columnDefinition "datetime"→"datetime(6)"`(line 281, `columnDefinition` AC 0→1건), (3) `createdAt @Column(updatable=false)` 보존(line 282), (4) P13(V4 DDL) 이후 순서 불변(line 284, 286 의존), (5) 검증을 리플렉션 단위 단정으로 한정 + validate round-trip 은 P17/P18 이관(line 285). 전역 P14 참조 — P13 line 257 / P15 line 307-308 / P16 line 328 / P18 line 365 / D4 추적 line 376 — 가 모두 실제 본문(엔티티 datetime(6) 매핑)과 정합. 실제 `BaseEntity.java:18-27` 은 여전히 `LocalDateTime`+`columnDefinition="datetime"` 이라 전환 대상이 정확. P15 line 307 "P14 완료(createdAt이 Instant) 상태 컴파일 통과" 의 전제가 P14 본문으로 성립. D4 핵심(만료 cutoff·정산 앵커 서브초 정밀도 정합)이 실행 가능 상태로 복귀.
2. **[n/a — 반영 확인] DM-2 경계 동치(`expires_at == now`) P2 테스트 추가.** P2 테스트 스펙 line 57 이 "경계 동치(DM-2) `expires_at == now`: `expires_at < now` 만 삭제하므로 동일 시각 행은 만료로 보지 않고 잔존 → 동일 uuid `recordIfAbsent` 는 `false`(미만료 보존, 중복 재고 차감 skip 멱등 유지) 단정" 을 명시. 실제 `JdbcEventDedupeStore.java:53` SQL `expires_at < NOW()`(strict less-than) → P2 AC line 44 가 `expires_at < ?` 바인딩으로 교체. 경계 동치 행이 미만료로 보존되는 의미가 소스 semantic 과 정확히 일치. 미만료 보존 = INSERT IGNORE 0 = 차감 skip 멱등 회귀 가드 확보.
3. **[n/a — 확인] 정산/금전 앵커 무변경.** approvedAt(PITFALLS §13)·amount 대조(§8)·EOS 발행(CONFIRM-FLOW §5)은 본 PLAN 범위 밖. product `stock_commit_dedupe.expires_at` 은 TIMESTAMP 라 D4 datetime(6) 승급 대상 아님. payment audit 컬럼 정밀도 정합은 finding 1 복원으로 봉인.

## Findings
- **DM-1 (n/a — 반영)**: P14 BaseEntity Instant+datetime(6) 전환 태스크 본문 복원. 다섯 AC 전부 존재, 전역 P14 참조 정합, orphan 의존 해소.
- **DM-2 (n/a — 반영)**: P2 DELETE 경계 테스트에 `expires_at==now` 동치(미만료 보존=차감 skip) 케이스 추가. 실제 strict less-than SQL semantic 과 정합.

## JSON
```json
{
  "stage": "plan",
  "topic": "TIME-MODEL-FOLLOWUP",
  "round": 3,
  "persona": "domain-expert",
  "decision": "pass",
  "findings": [
    {
      "id": "DM-1",
      "severity": "n/a",
      "title": "BaseEntity Instant+datetime(6) 전환 태스크(P14) 본문 복원 — critical 해소",
      "evidence": "PLAN line271 ### P14 섹션 존재(grep '^### P14' 1건). 다섯 AC 확인: (1) 3필드 LocalDateTime→Instant line280, (2) columnDefinition 'datetime'→'datetime(6)' line281(columnDefinition AC 0→1건), (3) createdAt @Column(updatable=false) 보존 line282, (4) P13 V4 DDL 이후 순서 불변 line284/286, (5) 검증 리플렉션 단위 단정 한정 + validate round-trip P17/P18 이관 line285. P13 line257 / P15 line307-308 / P16 line328 / P18 line365 / D4 추적 line376 의 P14 참조가 실제 본문과 정합. 실제 BaseEntity.java:18-27 여전히 LocalDateTime+columnDefinition='datetime' 이라 전환 대상 정확.",
      "recommendation": "추가 조치 불요.",
      "domain_vector": "audit/expiry-cutoff precision transition completeness, orphan dependency resolved"
    },
    {
      "id": "DM-2",
      "severity": "n/a",
      "title": "P2 DELETE 경계 테스트에 expires_at==now 동치(미만료 보존) 케이스 추가 — minor 해소",
      "evidence": "P2 테스트 스펙 line57 이 경계 동치 expires_at==now 행이 미만료로 잔존 → 동일 uuid recordIfAbsent false(차감 skip 멱등) 단정 명시. 실제 JdbcEventDedupeStore.java:53 SQL expires_at < NOW()(strict) ↔ P2 AC line44 expires_at < ? 바인딩 교체. 경계 semantic 소스 정합.",
      "recommendation": "추가 조치 불요.",
      "domain_vector": "idempotency boundary — duplicate stock decrement prevention"
    }
  ],
  "checklist": {
    "domain_risk_has_task": "pass",
    "dedupe_check_planned": "pass",
    "retry_safety_task": "pass",
    "audit_invariant_guarded": "pass",
    "expiry_precision_consistent": "pass"
  }
}
```
