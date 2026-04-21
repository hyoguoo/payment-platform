# 현재 작업 상태

> 최종 수정: 2026-04-21

## 활성 작업
- **주제**: MSA-TRANSITION
- **단계**: execute
- **이슈**: #69
- **브랜치**: `#69`
- **활성 태스크**: T1-15 (Graceful Shutdown + Virtual Threads 재검토 — tdd=true, domain_risk=false)
- **비고**: T1-13 스킵(2026-04-21) — T1-11c에서 OutboxProcessingService 삭제로 검증 대상 소실, FCG 불변은 T2b-03(pg-service)로 이관·불변식 7은 T1-12에서 커버. T1-14 완료(2026-04-21) — PaymentReconciler 신설, StockCachePort.findCurrent() / PaymentEventRepository.findInProgressOlderThan()/findAllByStatus() / PaymentEvent.resetToReady() 추가. Redis 캐시 장애 즉시 격리 경로(quarantineForCacheFailure)는 FAILED 전환 여부를 별도 discuss 주제(REDIS-CACHE-FAILURE-POLICY)로 분리 — docs/context/TODOS.md 참고. T0-01·T0-02·T0-03a/b/c·T0-04·T0-05·T0-Gate·T1-03·T1-04·T1-05·T1-06·T1-07·T1-08·T1-09·T1-10·T1-11a·T1-11b·T1-11c·T1-12 완료, T1-13 스킵, T1-14 완료. PLAN 66 태스크 · domain_risk 43건 · 의존 엣지 59개.

## 최근 완료
- **주제**: NICEPAY-PG-STRATEGY
- **완료일**: 2026-04-14
- **아카이브**: docs/archive/nicepay-pg-strategy/
