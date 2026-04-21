# 현재 작업 상태

> 최종 수정: 2026-04-21 (T2a-01 완료 — pg-service 모듈 신설 + port 계층 + 벤더 전략 이관)

## 활성 작업
- **주제**: MSA-TRANSITION
- **단계**: execute
- **이슈**: #69
- **브랜치**: `#69`
- **활성 태스크**: T2a-02 (pg-service AOP 축 복제 이관 — tdd=false, domain_risk=false)
- **비고**: **Phase 2.a 진행 중.** T2a-01 완료(2026-04-21) — pg-service 신규 Spring Boot 모듈 스캐폴드. settings.gradle include 'pg-service' 추가. PgGatewayPort·PgEventPublisherPort outbound 포트, PgConfirmCommandService inbound 포트 선언. TossPaymentGatewayStrategy·NicepayPaymentGatewayStrategy 스켈레톤 이관(실제 HTTP 호출은 T2b-01). PgTopics·KafkaTopicConfig 독립 복제(ADR-30). 전 타입 payment-service 의존 없음. :pg-service:compileJava PASS, 395/395 회귀 없음. T1-Gate 완료(2026-04-21) — scripts/phase-gate/phase-1-gate.sh(11개 섹션·35+ 체크포인트) + docs/phase-gate/phase-1-gate.md(운영자용 문서) 신설. bash -n 문법 검증 통과. 395/395 회귀 없음. T1-18 완료(2026-04-21) — gateway application.yml에 payment-service route(`/api/v1/payments/**` → lb://payment-service) 추가, OutboxImmediateEventHandler에 @ConditionalOnProperty(payment.monolith.confirm.enabled, matchIfMissing=false) 추가(모놀리스 confirm 경로 기본 비활성화), chaos/scripts/migrate-pending-outbox.sh 신설(PENDING outbox 이관 헬퍼 + --dry-run). 395/395 통과. T1-17 완료(2026-04-21) — StockSnapshotEvent DTO + StockCacheWarmupService(applySnapshots/handleSnapshot/isWarmupCompleted) + StockSnapshotWarmupConsumer(KafkaListener thin adapter) + StockCacheWarmupApplicationEventListener(ApplicationReadyEvent 훅) 신설. 테스트 5개 신규(395/395 통과). T1-16 완료(2026-04-21) — OutboxPendingAgeMetrics(histogram) + StockCacheDivergenceMetrics(counter) 신설, PaymentReconciler에 StockCacheDivergenceMetrics 주입·AtomicLong 제거, 테스트 4개 신규(390/390 통과). T1-15 완료(2026-04-21) — OutboxImmediateWorkerTest ADR-25 graceful drain + ADR-26 VT 워커 수 테스트 추가·보강(4개), OutboxImmediateWorker.java 변경 없음(T1-11c 구현이 이미 올바르게 drain 수행). T1-13 스킵(2026-04-21) — T1-11c에서 OutboxProcessingService 삭제로 검증 대상 소실, FCG 불변은 T2b-03(pg-service)로 이관·불변식 7은 T1-12에서 커버. T1-14 완료(2026-04-21) — PaymentReconciler 신설, StockCachePort.findCurrent() / PaymentEventRepository.findInProgressOlderThan()/findAllByStatus() / PaymentEvent.resetToReady() 추가. Redis 캐시 장애 즉시 격리 경로(quarantineForCacheFailure)는 FAILED 전환 여부를 별도 discuss 주제(REDIS-CACHE-FAILURE-POLICY)로 분리 — docs/context/TODOS.md 참고. T0-01·T0-02·T0-03a/b/c·T0-04·T0-05·T0-Gate·T1-03·T1-04·T1-05·T1-06·T1-07·T1-08·T1-09·T1-10·T1-11a·T1-11b·T1-11c·T1-12 완료, T1-13 스킵, T1-14·T1-15·T1-16·T1-17·T1-18·T1-Gate 완료. PLAN 66 태스크 · domain_risk 43건 · 의존 엣지 59개.

## 최근 완료
- **주제**: NICEPAY-PG-STRATEGY
- **완료일**: 2026-04-14
- **아카이브**: docs/archive/nicepay-pg-strategy/
