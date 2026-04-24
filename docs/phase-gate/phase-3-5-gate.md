# Phase 3.5 Gate — Pre-Phase-4 안정화

> T3.5-01 ~ T3.5-13 + T3.5-08 ~ T3.5-12 완료 후 Phase 4 진입 전 게이트.

## 검증 항목

| 섹션 | 체크 | 수락 기준 | 태스크 |
|---|---|---|---|
| pre | `./gradlew test` 전수 PASS | 461건 이상, 회귀 없음 | 전체 |
| a | `@Lazy` 잔재 0건 | 주석 언급 제외 0건 | T3.5-05 |
| b | `matchIfMissing=true` 의도적 잔존 2건 | OutboxImmediateEventHandler, TossPaymentGatewayStrategy | T3.5-02 |
| c | consumer groupId 분리 | StockCommit: `product-service-stock-commit`, StockRestore: `product-service-stock-restore` | T3.5-09 |
| d | 동기 발행 테스트 PASS | KafkaMessagePublisher + OutboxRelayService + PgEventPublisher + PgOutboxRelayService | T3.5-08 |
| e | HTTP Adapter 계약 PASS | ProductHttpAdapterContractTest + UserHttpAdapterContractTest (4 케이스씩) | T3.5-10 |
| f | PgOutbox RepeatedTest x50 | PgOutboxImmediateWorkerTest race 불변식 | T3.5-12 |
| g | phase-3-integration-smoke (옵션) | `--with-smoke` 지정 시 | T3.5-11 |

## 실행

```bash
# 빠른 gate (gradle test 중심)
bash scripts/phase-gate/phase-3-5-gate.sh

# compose-up 스모크까지 포함 — CI/E2E 검증용
bash scripts/phase-gate/phase-3-5-gate.sh --with-smoke
```

## Phase 3.5 완료 확정 규약

1. **Kafka 발행 동기 불변식** — `whenComplete` fire-and-forget 금지, 모든 publisher 는 `.get(timeout)` 으로 예외를 호출자 스레드에 전파 (T3.5-08)
2. **StockCommit/StockRestore 독립 groupId** — commit 경로 lag/rebalance 가 보상 경로에 파급되지 않도록 격리 (T3.5-09)
3. **서브도메인 HTTP 404/503/429/500 계약** — `ProductNotFoundException` / `UserNotFoundException` / `*ServiceRetryableException` / `IllegalStateException` 매핑 고정 (T3.5-10)
4. **compose-up 기반 E2E smoke 자동화** — FakePgGatewayStrategy 로 브라우저 PG SDK 대체, 배선 회귀를 단위 테스트가 못 잡는 영역에서 탐지 (T3.5-11)
5. **Outbox race 50회 반복 불변식** — Immediate+Polling 동시 경쟁 시 publish 횟수 1 (T3.5-12)
6. **Kafka W3C `traceparent` 자동 전파** — `spring.kafka.template.observation-enabled=true` + 수동 빈 `setObservationEnabled(true)` (T3.5-13)

## FAIL 조치

| 실패 섹션 | 빈번한 원인 | 조치 |
|---|---|---|
| pre | 신규 변경으로 기존 테스트 깨짐 | `./gradlew test --info` 로 실패 테스트 확인 |
| a | `@Lazy` 재도입 | 해당 파일의 의존성 구조 재설계 — port 분해 선호 |
| b | 추가 `matchIfMissing=true` 도입 | T3.5-02 규약 위배 — `application-test.yml` 또는 `@MockitoBean` 으로 대체 |
| c | groupId 재병합 | T3.5-09 규약 재확인 — `ARCHITECTURE.md` Consumer Group 섹션 |
| d | publisher `whenComplete` 패턴 재도입 | `.get(timeout)` 동기 호출로 전환 |
| e | 404/5xx 매핑 분기 변경 | `mapResponseException` 메서드 재검토 |
| f | race 1회 이상 실패 | 실패 iteration 번호로 단일 재현 후 race window 근본 원인 조사 |

## 다음 단계

Phase 4 — Toxiproxy 장애 주입 시나리오 8종 + k6 시나리오 재설계 + 로컬 오토스케일러.
