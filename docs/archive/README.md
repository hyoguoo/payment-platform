# 끝난 토픽

끝난 토픽의 기록을 그대로 보관한다. 폴더 안 문서는 고치지 않는다. 지난 결정이나 같은 문제를 다룬 적이 있는지 확인할 때 읽는다.

| 형식 | 폴더 구성 | 먼저 읽을 문서 |
|---|---|---|
| 현재 (2026-11 이후) | BRIEF(사전 브리핑 → 합의 → 완료 요약), DESIGN(결정, 장애 시나리오, 태스크, 리뷰 처리), 실험이면 EXPERIMENTS | BRIEF의 완료 요약 |
| 이전 (2026-06 ~ 2026-10) | `COMPLETION-BRIEFING.md`, 토픽 설계 문서, PLAN | `COMPLETION-BRIEFING.md` |
| 초기 (2026-06 이전) | 토픽 문서, PLAN, `rounds/`(옛 라운드 판정 기록). 완료 브리핑이 없는 폴더가 있다 | 토픽 문서 |

결과가 "닫음"인 토픽은 완료하지 않고 끝낸 것이며 완료 요약에 하지 않은 이유가 있다.
`state-history-2026H1.md`는 2026-06-12에 STATE를 줄이기 전의 이력이다.

| 폴더 | 내용 | 결과 | 기간 | 이슈 |
|---|---|---|---|---|
| `async-payment-migration/` | 비동기 결제 처리 마이그레이션 (Sync / Outbox / Kafka 전략 구현) | 완료 | 2026-03-14 ~ 2026-03-18 |  |
| `checkout-idempotency/` | Checkout 멱등성 구현 (Caffeine 캐시 기반 중복 요청 감지, 201/200 분기) | 완료 | 2026-03-22 |  |
| `outbox-immediate-dispatch/` | Outbox 즉시 처리 | 완료 | 2026-03-28 |  |
| `k6-benchmark/` | k6 벤치마크 설계와 측정 (옛 목차의 벤치마크 개선, 측정 개선, 방법론 수정, 재설계 네 작업을 묶은 폴더) | 완료 | 2026-03-27 ~ 2026-04-03 |  |
| `async-payment-cleanup/` | 비동기 결제 클린업 | 완료 | 2026-03-29 |  |
| `async-channel/` | 비동기 채널 | 완료 | 2026-03-31 |  |
| `outbox-only-refactor/` | Outbox 단일화 리팩토링 | 완료 | 2026-04-06 |  |
| `client-integration/` | 클라이언트 통합 | 완료 | 2026-04-06 |  |
| `payment-retry-state/` | 재시도 정교화 | 완료 | 2026-04-07 |  |
| [payment-double-fault-recovery](payment-double-fault-recovery/COMPLETION-BRIEFING.md) | 이중장애 복구 | 완료 | 2026-04-10 |  |
| [nicepay-pg-strategy](nicepay-pg-strategy/COMPLETION-BRIEFING.md) | NicePay PG 전략 추가 | 완료 | 2026-04-14 |  |
| [msa-transition](msa-transition/COMPLETION-BRIEFING.md) | MSA 전환 | 완료 | 2026-04-24 |  |
| `client-side-lb/` | Client-side Load Balancing 도입 | 완료 | 2026-04-27 ~ 2026-05-01 |  |
| [pre-phase-4-hardening](pre-phase-4-hardening/COMPLETION-BRIEFING.md) | Pre-Phase-4 하드닝 | 완료 | 2026-04-24 |  |
| `phase-4-readiness-sweep/` | Phase 4 진입 전 self-loop 정리 + 코드 리팩터링 검수 | 완료 | 2026-04-27 |  |
| [stock-compensation-recovery](stock-compensation-recovery/COMPLETION-BRIEFING.md) | 결제 결과 보상 silent loss 회복 layer | 완료 | 2026-05-08 |  |
| `stock-compensation-recovery-alternatives/` | 재고 보상 복구의 설계 대안 검토 라운드 기록 (완료 브리핑 없음) | 완료 | 2026-06-12 |  |
| [pg-confirm-listener-split](pg-confirm-listener-split/COMPLETION-BRIEFING.md) | pg-service Kafka listener TX 에서 벤더 호출 분리 | 완료 | 2026-05-09 |  |
| [cleanup-batch-a](cleanup-batch-a/COMPLETION-BRIEFING.md) | PR 묶음 A | 완료 | 2026-05-12 |  |
| [payment-eos-transition](payment-eos-transition/COMPLETION-BRIEFING.md) | TC-13 payment-service 결제 결과 컨슈머 EOS 전환 | 완료 | 2026-05-17 ~ 2026-05-18 | #77 |
| [eos-followup-cleanup](eos-followup-cleanup/COMPLETION-BRIEFING.md) | EOS 전환 후속 정합 + 결제 비동기 경로 청소 | 완료 | 2026-05-29 | #79 |
| [cleanup-batch-b](cleanup-batch-b/COMPLETION-BRIEFING.md) | 빌드·테스트 게이트 위생 | 완료 | 2026-05-30 ~ 2026-05-31 | #81 |
| [time-model-and-expiry](time-model-and-expiry/COMPLETION-BRIEFING.md) | 시간 모델 Clock/Instant 통일 + 결제 만료 정책 명문화 | 완료 | 2026-06-01 ~ 2026-06-03 | #83 |
| [time-model-followup](time-model-followup/COMPLETION-BRIEFING.md) | 시간 모델 잔여 정합 3건 묶음 | 완료 | 2026-06-06 ~ 2026-06-07 | #89 |
| [ci-pipeline-redesign](ci-pipeline-redesign/COMPLETION-BRIEFING.md) | CI 파이프라인 서비스별 fan-out 재설계 + 빌드·게이트 위생 4건 흡수 | 완료 | 2026-06-08 | #91 |
| [observability-completion](observability-completion/COMPLETION-BRIEFING.md) | 관측성 완성 | 완료 | 2026-06-10 ~ 2026-06-11 | #94 |
| [cleanup-batch-c](cleanup-batch-c/COMPLETION-BRIEFING.md) | 코드 레벨 정리 | 완료 | 2026-06-13 | #98 |
| [cleanup-batch-d](cleanup-batch-d/COMPLETION-BRIEFING.md) | 빌드·테스트 위생 정리 + 상품 서비스 청소 스케줄러 운영 활성화 | 완료 | 2026-06-14 | #100 |
| [k6-async-benchmark](k6-async-benchmark/COMPLETION-BRIEFING.md) | 비동기 결제 경로 k6 부하 측정 자산 신규 + 병목 분석 사이클 | 완료 | 2026-06-15 | #102 |
| [capacity-and-scaleout](capacity-and-scaleout/COMPLETION-BRIEFING.md) | 결제 처리량 부하 측정 2페이즈 + scale-out 검증 | 완료 | 2026-06-19 | #104 |
| [stock-compensation-other-paths](stock-compensation-other-paths/COMPLETION-BRIEFING.md) | 재고 보상 경로 정리: 死 코드 제거 + 확정 진입 보상 폐기 | 완료 | 2026-06-21 | #106 |
| [cleanup-batch-e](cleanup-batch-e/COMPLETION-BRIEFING.md) | 비동기 confirm 死 코드 정리 + Fake PG 멱등성 시뮬 | 완료 | 2026-06-21 | #108 |
| [retry-metric-cleanup](retry-metric-cleanup/COMPLETION-BRIEFING.md) | payment 재시도 metric 잔재 정리: 死 metric 전면 제거 | 완료 | 2026-06-22 | #110, #106 |
| [confirm-approved-resend-gap](confirm-approved-resend-gap/COMPLETION-BRIEFING.md) | 비동기 confirm APPROVED 재고 확정 재발행 갭 수정 | 완료 | 2026-06-22 | #112 |
| [dlq-reachability](dlq-reachability/COMPLETION-BRIEFING.md) | 장애 지속 시 DLQ 도달 보장 | 완료 | 2026-06-25 | #114 |
| [alerting-rules-and-fault-drill](alerting-rules-and-fault-drill/COMPLETION-BRIEFING.md) | Prometheus 알람 규칙 인프라 + Toxiproxy 장애 주입 발화 실증 | 완료 | 2026-06-27 | #116 |
| [fault-injection-resilience](fault-injection-resilience/COMPLETION-BRIEFING.md) | 서비스·DB·Redis 가용성 알람 + docker stop 완전 다운 정합 거동 실증 | 완료 | 2026-06-28 ~ 2026-06-30 | #118 |
| [docs-consistency-overhaul](docs-consistency-overhaul/COMPLETION-BRIEFING.md) | 문서 전수 정합 개선 | 완료 | 2026-07-02 ~ 2026-07-07 | #120 |
| [dlq-quarantine-recovery](dlq-quarantine-recovery/COMPLETION-BRIEFING.md) | 격리 결제·유실 메시지 관리자 수동 복구 | 완료 | 2026-07-10 ~ 2026-07-11 | #122 |
| [admin-visibility](admin-visibility/COMPLETION-BRIEFING.md) | 관리자 화면 가시성 확충 | 완료 | 2026-07-27 ~ 2026-07-28 | #126 |
| [agent-context-overhaul](agent-context-overhaul/COMPLETION-BRIEFING.md) | 에이전트 지침 컨텍스트의 상충·중복 정비 | 완료 | 2026-07-30 ~ 2026-08-03 | #130 |
| [live-drill-formalization](live-drill-formalization/COMPLETION-BRIEFING.md) | 라이브 검증 체계 정식화 | 완료 | 2026-07-28 ~ 2026-07-29 | #128 |
| [signal-and-guardrail-sweep](signal-and-guardrail-sweep/COMPLETION-BRIEFING.md) | 운영 신호 정합과 규칙 자동 검출 정비 | 완료 | 2026-08-03 ~ 2026-08-04 | #132 |
| [backlog-residue-cleanup](backlog-residue-cleanup/COMPLETION-BRIEFING.md) | 미해결 항목 잔여 정리 | 완료 | 2026-08-04 ~ 2026-08-05 | #134 |
| [retry-exhaustion-disposition](retry-exhaustion-disposition/COMPLETION-BRIEFING.md) | 재시도 소진 이후 처리 | 완료 | 2026-08-05 ~ 2026-08-06 | #136 |
| [pg-message-dedupe-layer-removal](pg-message-dedupe-layer-removal/COMPLETION-BRIEFING.md) | pg 리스너 메시지 dedupe 층 제거 | 완료 | 2026-08-10 ~ 2026-08-11 | #138 |
| [pg-duplicate-approval-settlement](pg-duplicate-approval-settlement/COMPLETION-BRIEFING.md) | 중복 승인 응답을 받은 결제의 종결 | 완료 | 2026-08-12 ~ 2026-08-13 | #140 |
| [pg-vendor-signal-consolidation](pg-vendor-signal-consolidation/COMPLETION-BRIEFING.md) | 벤더 응답 신호 경로 단일화 | 완료 | 2026-08-13 ~ 2026-08-14 | #142 |
| [stock-gate-per-product](stock-gate-per-product/COMPLETION-BRIEFING.md) | 재고 선차감 게이트 상품 단위 분해 | 완료 | 2026-08-15 ~ 2026-08-18 | #144 |
| [shared-resource-scaleout](shared-resource-scaleout/COMPLETION-BRIEFING.md) | payment 성능 측정 기반 구축과 스케일아웃 천장 규명 | 완료 | 2026-08-15 ~ 2026-09-04 | #146 |
| [confirm-result-nonretryable-status](confirm-result-nonretryable-status/COMPLETION-BRIEFING.md) | 되돌아간 결제가 확정 결과를 받게 하기 | 완료 | 2026-09-05 ~ 2026-09-06 | #150 |
| [ai-change-guardrails](ai-change-guardrails/COMPLETION-BRIEFING.md) | AI 코드 변경 통제 | 완료 | 2026-09-07 | #152 |
| [cleanup-batch-f](cleanup-batch-f/COMPLETION-BRIEFING.md) | 가벼운 후속 정리 6건 | 완료 | 2026-09-10 ~ 2026-09-11 | #155 |
| [ci-integration-test-sharding](ci-integration-test-sharding/COMPLETION-BRIEFING.md) | CI 통합 테스트 시간 단축 | 완료 | 2026-09-28 | #158, #159 |
| [pg-inbox-index-merge](pg-inbox-index-merge/COMPLETION-BRIEFING.md) | 힌트 없는 index_merge·데드락 재현 | 완료 | 실험 2026-10-01 · 이관 2026-10-02 |  |
| [agent-structure-overhaul](agent-structure-overhaul/BRIEF.md) | 에이전트 지침을 agent-backend-kit 구조로 바꾸고 프로젝트 문서를 현재 코드 기준으로 정리 | 완료 | 2026-11-06 | #163 |
