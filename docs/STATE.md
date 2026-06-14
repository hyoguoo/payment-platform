# 현재 작업 상태

> 최종 수정: 2026-06-15

## 활성 작업

- **주제**: K6-ASYNC-BENCHMARK (비동기 결제 경로 k6 부하 측정 시나리오 신규 작성)
- **단계**: execute
- **이슈/브랜치**: #102
- **활성 태스크**: Task 3 (k6 helpers.js — 상수·메트릭·요청 헬퍼)
- **산출물**: `docs/topics/K6-ASYNC-BENCHMARK.md` (설계) + `docs/K6-ASYNC-BENCHMARK-PLAN.md` (plan 완료, 7 태스크)

## 재개 메모

- plan 완료(게이트 R2 reviewer + domain-expert 모두 pass). 다음은 execute — Task 1부터 순차 구현.
- 측정 자산 백지 상태. 신규: `docker-compose.benchmark.yml`, `scripts/bench-seed-stock.sh`, `scripts/k6/{helpers.js, async-payment.js, run-benchmark.sh, verify-settlement.sh}`, 결과 리포트.
- 전 태스크 tdd=false(측정 자산, Java 단위 테스트 대상 없음). 검증은 smoke run 정합성 확인 + 교차식. Task 4·6 domain_risk=true.
- 게이트 정정 반영된 코드 사실(execute 시 준수): checkout 중복=HTTP 200/201(body isDuplicate 필드 없음), 재고 부족 confirm=400, confirm은 동기 재고차감+TX 후 202, reconciler timeout(`RECONCILER_IN_FLIGHT_TIMEOUT_SECONDS`)+scan(`RECONCILER_FIXED_DELAY_MS`) 둘 다 yml 미정의→env 단축 주입.
- Task 7(측정 실행)은 로컬 풀스택 + k6 설치 필요(환경 의존). 부하 곡선·타임아웃·reconciler 단축 구체값은 execute 실측 보정.

## 최근 완료

- **CLEANUP-BATCH-D** (빌드·테스트 위생 정리 — 통합테스트 Flyway 경합 flaky DB명 분리 + build.gradle deprecated 문법 + 상품 서비스 청소 스케줄러 운영 활성화 + 스케줄러 정책 문서화, 2026-06-14, 이슈/브랜치 #100) — `docs/archive/cleanup-batch-d/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-C** (코드 레벨 정리 — 미사용 코드 제거 + pg 워커 보일러플레이트 헬퍼화 + 테스트 헬퍼 위치 통일, 2026-06-13, 이슈/브랜치 #98) — `docs/archive/cleanup-batch-c/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
