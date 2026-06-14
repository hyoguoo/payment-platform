# 현재 작업 상태

> 최종 수정: 2026-06-15

## 활성 작업

- **주제**: K6-ASYNC-BENCHMARK (비동기 결제 경로 k6 부하 측정 시나리오 신규 작성)
- **단계**: plan
- **이슈/브랜치**: #102
- **산출물**: `docs/topics/K6-ASYNC-BENCHMARK.md` (설계 완료) → `docs/K6-ASYNC-BENCHMARK-PLAN.md` (plan 작성 예정)

## 재개 메모

- discuss 완료(게이트 R2 reviewer pass + domain-expert 잔여 findings 반영 완료). 다음은 plan 단계 — 설계 문서를 구현 태스크로 분해.
- 측정 자산은 백지 상태(라이브 k6 스크립트 0건). 신규 작성: `scripts/k6/*`, `docker-compose.benchmark.yml` override, 벤치 전용 재고 시드.
- 핵심 측정 오염 차단 전제(매 iteration 고유 멱등키 / 대용량 재고 시드 / baseline failRate=0 / 폴링 타임아웃 하한 ≥ outbox 폴백 2s / settle 대기 교차 검증)는 설계 결정 사항·검증 전략에 명시됨 — plan에서 태스크로 구체화.
- 부하 곡선 최종값·폴링 타임아웃·reconciler 단축 override 구체값은 plan/execute에서 실측 보정.

## 최근 완료

- **CLEANUP-BATCH-D** (빌드·테스트 위생 정리 — 통합테스트 Flyway 경합 flaky DB명 분리 + build.gradle deprecated 문법 + 상품 서비스 청소 스케줄러 운영 활성화 + 스케줄러 정책 문서화, 2026-06-14, 이슈/브랜치 #100) — `docs/archive/cleanup-batch-d/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-C** (코드 레벨 정리 — 미사용 코드 제거 + pg 워커 보일러플레이트 헬퍼화 + 테스트 헬퍼 위치 통일, 2026-06-13, 이슈/브랜치 #98) — `docs/archive/cleanup-batch-c/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
