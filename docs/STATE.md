# 현재 작업 상태

> 최종 수정: 2026-04-21 (T2a-02 완료 — pg-service AOP 축 복제 이관)

## 활성 작업
- **주제**: MSA-TRANSITION
- **단계**: execute
- **이슈**: #69
- **브랜치**: `#69`
- **활성 태스크**: T2a-03 (Fake pg-service 구현 — tdd=false, domain_risk=false)
- **비고**: **Phase 2.a 진행 중.** T2a-02 완료(2026-04-21) — TossApiMetric(annotation)·ErrorCode(annotation)·TossApiMetrics·TossApiMetricsAspect를 pg.infrastructure.aspect.* 패키지에 신설. payment-service TossPaymentErrorCode 참조 제거(extractErrorCode Optional<String> 반환). pg-service/build.gradle에 spring-boot-starter-aop + spring-boot-starter-actuator 최소 추가. payment-service 원본 미수정(Phase 2.b/2.c 삭제 예정). :pg-service:compileJava PASS, 395/395 회귀 없음. T2a-01 완료(2026-04-21) — pg-service 신규 Spring Boot 모듈 스캐폴드. settings.gradle include 'pg-service' 추가. PgGatewayPort·PgEventPublisherPort outbound 포트, PgConfirmCommandService inbound 포트 선언. TossPaymentGatewayStrategy·NicepayPaymentGatewayStrategy 스켈레톤 이관(실제 HTTP 호출은 T2b-01). PgTopics·KafkaTopicConfig 독립 복제(ADR-30). 전 타입 payment-service 의존 없음. :pg-service:compileJava PASS, 395/395 회귀 없음. T1-Gate 완료(2026-04-21) — scripts/phase-gate/phase-1-gate.sh(11개 섹션·35+ 체크포인트) + docs/phase-gate/phase-1-gate.md(운영자용 문서) 신설. bash -n 문법 검증 통과. 395/395 회귀 없음. T1-18 완료(2026-04-21) — gateway application.yml에 payment-service route(`/api/v1/payments/**` → lb://payment-service) 추가, OutboxImmediateEventHandler에 @ConditionalOnProperty(payment.monolith.confirm.enabled, matchIfMissing=false) 추가(모놀리스 confirm 경로 기본 비활성화), chaos/scripts/migrate-pending-outbox.sh 신설(PENDING outbox 이관 헬퍼 + --dry-run). 395/395 통과. T0-01·T0-02·T0-03a/b/c·T0-04·T0-05·T0-Gate·T1-03·T1-04·T1-05·T1-06·T1-07·T1-08·T1-09·T1-10·T1-11a·T1-11b·T1-11c·T1-12 완료, T1-13 스킵, T1-14·T1-15·T1-16·T1-17·T1-18·T1-Gate·T2a-01·T2a-02 완료. PLAN 66 태스크 · domain_risk 43건 · 의존 엣지 59개.

## 최근 완료
- **주제**: NICEPAY-PG-STRATEGY
- **완료일**: 2026-04-14
- **아카이브**: docs/archive/nicepay-pg-strategy/
