# PAYMENT-EOS-TRANSITION — Round 2 Architect

## 흡수 항목

| DR ID | severity | 흡수 위치 | 변경 요약 |
|---|---|---|---|
| DR-1 | critical | §3 flowchart + §4 D8 신설 + §5 layer 표 + §6 유지 대상 섹션 + §7 acceptance + §8 통합 테스트 4 + §9 멱등성 표 | `StockEventUuidDeriver` 보존 결정 명시 (§6 삭제 대상에서 제외, 유지 대상 섹션 신설). 두 종류 UUID (`event_uuid` = 수신 측 멱등 키 / `idempotencyKey` = 발행 측 결정성 키) 의 역할 분리를 D8 로 명시. §3 flowchart 의 StockSend 박스를 multi-product loop (PaymentOrder 순회 → deriveKey → producer.send) 으로 풀어 표현. 통합 테스트 #4 multi-product 회귀 가드 추가 (productId 2건 stock_commit_dedupe 양쪽 모두 박힘 + 재배달 시 양쪽 모두 skip 검증). |
| DR-2 | high | §4 D4 갱신 + §2 영향 모듈 + §9 시나리오 (g) + §11 L6 신설 + 후속 작업 목록 | D4 정책을 단일 인스턴스 가정으로 명시 제한. docker-compose `hostname: payment-service` 고정과의 충돌을 표로 분석 (단일 OK / 다중 fence 무한 반복). 본 토픽은 라인 수정 안 함 (§2 non-goals 2번 — 다중 인스턴스 검증 deferred). verify 단계에서 CONCERNS.md + TODOS.md 등재. 시나리오 (g) 로 운영자 인지 경로 확보. 다중 인스턴스 확장 트리거 시 옵션 (a) hostname 라인 제거 또는 (b) INSTANCE_ID 환경변수 도입을 D4 본문에 명시. |
| DR-3 | high | §3 flowchart GuardCheck + §4 D2 항목 5 + §4 D7 신설 + §8 통합 테스트 5 + §9 시나리오 (f) + §9 재시도 정책 | `PaymentStatusException` 의 부모 클래스 코드 확인 (RuntimeException 직접 상속, IllegalStateException 아님) → not-retryable 화이트리스트 미매칭 → 5회 retry 후 DLQ (즉시 DLQ 아님, 결과는 silent DLQ). D7 가드 정책 신설: `isCompensatableByFailureHandler` 부재 (READY/IN_PROGRESS/RETRYING 만 proceed) 를 skip 신호로 사용 → QUARANTINED 도 skip + LogFmt.warn. 기각 대안 3가지 (isTerminal only / OR 표현 / 가드 제거) 명시. §3 flowchart 의 TerminalGuard 박스를 GuardCheck (D7 정책 참조) 로 변경. 통합 테스트 #5 (QUARANTINED 늦은 APPROVED) 추가 — DLQ 0건 + dedupe row 0건 + warn 1건 검증. |
| DR-4 | high | §12 신설 (배포 순서) + §7 acceptance 7번 | 새 섹션 §12 로 deploy 순서 결정 명시. 잘못된 순서의 위험 시나리오 (t0/t1 windows 표) + 룰 (product-service 먼저 + actuator /env 확인 → payment-service 나중) + Mermaid flowchart. 단일 PR + 배포 순서 강제 채택 이유 (기각 대안 = D6 별 선행 PR 분리 의 중간 상태 혼란 비교). Acceptance 7번에 PR 본문 + 운영 배포 체크리스트 명시 의무. |
| DR-5 | medium | §9 멱등성 전략 표 | INSERT IGNORE 1 row 신호와 비즈니스 진행 의미 불일치 race window 명시 — partition rebalance 짧은 윈도우에 두 consumer 가 시간차 처리 시 dedupe row 박혔으나 실제는 `done()` 자기전이 no-op. TC-11 cleanup 스케줄러 SLO 계산 시 잡음 고려 명시 (dedupe row count ≠ 처리량). |
| DR-6 | medium | §11 L5 신설 + 후속 작업 목록 | 빅뱅 PR 의 회복 비대칭 명시 — Flyway down migration 부재 + 17 단위 동시 revert 필요 + outbox 큐 회복 결 소실. 머지 직후 24시간 운영 모니터링 SLO 명시 (consumer 처리 정지 알람 / DLQ inbound rate / dedupe row 추세). 회귀 판정 메트릭 60분 룰. verify 단계 CONCERNS.md 등재. |
| DR-7 | medium | §10 SCR L7 cascade 빈도 평가 섹션 | EOS 도입 후 markPaymentAsFail 영구 실패 cascade 빈도 평가 — 4 항목 표 (broker 일시 장애 / RDB lock / Redis 보상 / 도메인 예외). 총평: broker 불안정 시 marginal 증가 vs D7 가드로 도메인 예외 분기 감소 → net 무변에 가까운 marginal 증가. 운영 모니터링 SLO 변경 불필요 결론. |

## 미흡수 항목 (이유)

- **DR-8 (minor — EventDedupeStore 동명 재사용)**: 흡수 보류. 본 토픽 §5 layer 표가 이미 "직전 SCR 폐기한 동명 port 와 시그니처 다름" 명시. plan 단계에서 (a) 동명 재사용 / (b) `PaymentEventDedupeStore` 등 분리 명명 결정 명시 권장 — Architect 가 plan-review 에서 인라인 주석 가능. 본 라운드는 토픽 문서 흡수 범위 밖.

## 다음 라운드 입력

- Round 2 Critic / Domain Expert dispatch 시 본 갱신 토픽 + Round 1 findings + 본 라운드 findings 비교를 통해:
  - DR-1: §3 multi-product loop + D8 두 UUID 분리 + §6 유지 대상 명시가 silent 재고 사고 회귀 경로를 봉쇄하는지 확인
  - DR-2: §11 L6 + §9 (g) + TODOS 등재 deferred 결정이 multi-instance fencing 충돌의 운영 인지 경로를 충분히 확보하는지 확인
  - DR-3: D7 가드 정책 + `isCompensatableByFailureHandler` 재사용 + 통합 테스트 #5 가 QUARANTINED 늦은 APPROVED silent DLQ 회귀를 봉쇄하는지 확인
  - DR-4: §12 배포 순서 + Acceptance 7번이 spurious 재고 차감 windows 회귀 가드로 충분한지 확인
- plan 단계에서 Architect 가 plan-review 시 확인할 주요 사항:
  - `EventDedupeStore` 명명 결정 (DR-8 보류 항목)
  - `transaction.timeout.ms` 명시값 (L4 — 10s 권장)
  - 통합 테스트 #4 / #5 의 SUT 위치 (PaymentEosIntegrationTest 단일 파일 vs 분할)
  - 운영 배포 체크리스트 신설 산출물 위치 (verify 단계)
