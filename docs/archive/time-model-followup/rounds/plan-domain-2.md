# plan-domain-2

**Topic**: TIME-MODEL-FOLLOWUP
**Round**: 2
**Persona**: Domain Expert

## Reasoning
DM-3(P10 raw-JDBC 바인딩 열거에 recordIfAbsent DELETE-by-uuid 추가)는 반영됐다. 그러나 DM-1 처방을 적용하며 Flyway DDL 을 P13 으로 앞당겨 번호만 바꾸는 과정에서 BaseEntity `LocalDateTime→Instant` + `columnDefinition datetime→datetime(6)` 매핑 전환 태스크(문서 전역이 P14 로 참조)의 **본문이 통째로 사라졌다**. P15/P16/P18 이 존재하지 않는 P14 에 의존하고, D4 의 핵심 전환에 객관적 완료 기준이 없어 만료/감사 시각 정밀도 전환 자체가 실행 불가 상태다.

## Domain risk checklist
- [x] discuss domain risk 대응 태스크 보유 — DM-F1→P4/P5, DM-F2→P10 매핑 유지(PLAN §discuss findings).
- [x] 중복 방지 체크 경로 계획됨 — recordIfAbsent INSERT IGNORE(event_uuid PK) 멱등 SSOT 유지. P2 새 테스트가 만료/미만료 행 분기로 차감 skip 멱등 가드.
- [x] 재시도 안전성 검증 태스크 존재 — P4 `commit_중복이벤트_...false반환시스킵`(commitToRdb 미호출)이 Kafka 재배달 시 재고 미차감 검증.
- [블로커] D4 BaseEntity 전환 태스크(P14) 본문 부재 — 만료 cutoff·정산 앵커가 의존하는 audit 컬럼 정밀도(datetime(6)) ↔ 엔티티 매핑 정합이 어떤 태스크로도 산출되지 않음. P13 DM-1 불변 AC(line 256)가 가리키는 "P14 후 엔티티 매핑 정밀도 datetime(6) 정합" 대상이 존재하지 않아 AC 가 자기모순.
- [추가] DM-2 경계 동치(`expires_at == now`) 케이스 P2 테스트 미반영 — 미만료 보존=차감 skip 멱등 경계 회귀 가드 여전히 부재(minor).

## 도메인 관점 추가 검토
1. **[critical] P14(BaseEntity Instant + datetime(6) 매핑 전환) 태스크 본문 소실 — D4 핵심 전환이 실행 불가 + orphan 의존.** PLAN 에 `### P14` 섹션이 없다(`grep "^### P14"` 0건). 반면 (a) P13 line 256 DM-1 불변 AC, (b) P15 line 282·283 의존, (c) P16 line 300·303 의존, (d) P18 line 340 의존, (e) D4 추적 테이블 line 351 이 모두 P14 를 참조한다. 실제 `BaseEntity.java:18-27` 은 여전히 `LocalDateTime` + `columnDefinition="datetime"` 이고, PLAN 전역에 `columnDefinition` 변경 AC 가 0건(`grep -nc columnDefinition` = 0)이다. 즉 audit 컬럼 정밀도 전환(D4 핵심)을 수행하는 태스크가 사라졌다. 결과: P15 line 282 "P14 완료(createdAt이 Instant) 상태에서 컴파일 통과" 가 성립 불가(getter 가 여전히 LocalDateTime → `getCreatedAt()` 직접 사용 시 도메인 Instant 와 타입 불일치). P13 의 DM-1 정합 불변("validate 부팅 시 엔티티 매핑 정밀도 datetime(6) 정합")도 그 datetime(6) 매핑을 만드는 태스크가 없어 자기모순. Gate 체크리스트 위반: dependency ordering "orphan 없음", task quality "객관적 완료 기준". 돈 경로 영향: D4 가 노린 created_at 기반 만료 cutoff·approvedAt 정산 앵커의 서브초 정밀도 정합이 미완으로 남아, 이번 토픽이 종료돼도 비-UTC/절삭 회귀가 봉인되지 않는다. 처방: BaseEntity `LocalDateTime→Instant` 필드 전환 + `columnDefinition="datetime(6)"` + `updatable=false` 보존 + 회귀 가드 테스트를 담은 P14 태스크 본문을 P13(DDL) 직후·P15 앞에 복원하고, 의존/AC 의 P14 참조를 실제 본문과 일치시킨다.
2. **[minor] DM-2 경계 동치(`expires_at == now`) 미반영.** `JdbcEventDedupeStore.java` DELETE 신 SQL 은 `expires_at < ?`(strict less-than, P2 AC line 44). P2 테스트 스펙(line 53-56)은 만료 행(now 이전)·미만료 행(now 이후)만 단정하고 `==now` 동치 행(미만료로 보존 = INSERT IGNORE 0 = 차감 skip)을 다루지 않는다. TTL P8D >> Kafka retention 7d 라 동시각 충돌 확률 0 수렴이므로 minor 유지. Round 1 권고 미반영.
3. **[n/a — 반영 확인] DM-3 P10 raw-JDBC 바인딩 열거 보강.** P10 AC line 199 가 "recordIfAbsent INSERT IGNORE / **recordIfAbsent DELETE-by-uuid** / deleteExpired" 3경로를 명시 열거하고 DM-3 근거를 괄호로 박았다. UTC backstop 근거 완전성 확보 — 반영 완료.
4. **[n/a — 확인] 정산/금전 앵커 무변경.** approvedAt(PITFALLS §13)·amount 대조(§8)·EOS 발행(CONFIRM-FLOW §5)은 본 PLAN 범위 밖. product `stock_commit_dedupe.expires_at` 은 TIMESTAMP 라 D4 datetime(6) 승급 대상 아님. 단 finding 1 미해결 시 payment audit 컬럼 정밀도 정합이 미완으로 남는 점만 critical 로 별도 계상.

## Findings
- **DM-1 (critical)**: DM-1 처방 적용 중 Flyway DDL 을 P13 으로 앞당기며 BaseEntity Instant+datetime(6) 전환 태스크(전역 참조명 P14) 본문이 소실. P15/P16/P18 이 미정의 P14 에 의존(orphan), D4 핵심 전환에 객관적 완료 기준 부재, P13 DM-1 불변 AC 가 존재하지 않는 datetime(6) 엔티티 매핑을 참조해 자기모순. Gate(dependency ordering / task quality) 위반 → 차단.
- **DM-2 (minor)**: P2 DELETE 경계 테스트에 `expires_at==now` 동치(미만료 보존=차감 skip) 케이스 미반영. Round 1 권고 잔존.
- **DM-3 (n/a — 반영)**: P10 raw-JDBC 바인딩 열거에 recordIfAbsent DELETE-by-uuid 추가 완료.

## JSON
```json
{
  "stage": "plan",
  "topic": "TIME-MODEL-FOLLOWUP",
  "round": 2,
  "persona": "domain-expert",
  "decision": "fail",
  "findings": [
    {
      "id": "DM-1",
      "severity": "critical",
      "title": "BaseEntity Instant+datetime(6) 전환 태스크(P14) 본문 소실 — D4 핵심 전환 실행 불가 + orphan 의존",
      "evidence": "PLAN에 ### P14 섹션 없음(grep '^### P14' 0건). columnDefinition 변경 AC 0건(grep -nc columnDefinition=0). 그러나 P13 line256 DM-1 불변 AC / P15 line282-283 / P16 line300-303 / P18 line340 / D4 추적 line351 이 모두 P14 참조. 실제 BaseEntity.java:18-27 여전히 LocalDateTime + columnDefinition='datetime'. P15 line282 'P14 완료(createdAt이 Instant) 상태 컴파일 통과' 성립 불가. payment ddl-auto: validate(PITFALLS §14).",
      "recommendation": "BaseEntity LocalDateTime→Instant + columnDefinition='datetime(6)' + updatable=false 보존 + 회귀 가드 테스트를 담은 P14 태스크 본문을 P13(DDL) 직후·P15 앞에 복원하고, 의존/AC의 P14 참조를 실제 본문과 일치. P13의 DM-1 순서 불변 AC가 가리키는 datetime(6) 엔티티 매핑이 실제 산출되도록 정합.",
      "domain_vector": "audit/expiry-cutoff precision transition completeness, orphan dependency on undefined task"
    },
    {
      "id": "DM-2",
      "severity": "minor",
      "title": "P2 DELETE 경계 테스트에 expires_at==now 동치(미만료 보존) 케이스 미반영",
      "evidence": "JdbcEventDedupeStore DELETE 신 SQL expires_at < ? (strict, P2 AC line44). P2 테스트 스펙 line53-56 은 now 이전/이후만 단정, 경계 동치 미커버. Round 1 권고 잔존.",
      "recommendation": "P2 새 테스트에 expires_at==now 행이 미만료로 보존되어 false 반환(차감 skip)되는 1줄 단정 추가.",
      "domain_vector": "idempotency boundary — duplicate stock decrement prevention"
    },
    {
      "id": "DM-3",
      "severity": "n/a",
      "title": "P10 raw-JDBC 바인딩 열거에 recordIfAbsent DELETE-by-uuid 추가 — 반영 완료",
      "evidence": "P10 AC line199 가 'recordIfAbsent INSERT IGNORE / recordIfAbsent DELETE-by-uuid / deleteExpired' 3경로 명시 열거 + DM-3 근거 괄호 명기.",
      "recommendation": "추가 조치 불요.",
      "domain_vector": "raw-JDBC UTC convention completeness"
    }
  ],
  "checklist": {
    "domain_risk_has_task": "fail",
    "dedupe_check_planned": "pass",
    "retry_safety_task": "pass"
  }
}
```
