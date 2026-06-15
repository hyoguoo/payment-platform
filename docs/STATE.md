# 현재 작업 상태

> 최종 수정: 2026-06-15

## 활성 작업

- **주제**: K6-ASYNC-BENCHMARK (비동기 결제 경로 k6 부하 측정 시나리오 신규 작성)
- **단계**: ship
- **이슈/브랜치**: #102
- **활성 태스크**: 전 태스크(Task 1~7) 완료 — 다음은 ship(리뷰 + 마무리)
- **산출물**: `docs/topics/K6-ASYNC-BENCHMARK.md` (설계) + `docs/K6-ASYNC-BENCHMARK-PLAN.md` (plan) + `docs/topics/K6-ASYNC-BENCHMARK-REPORT.md` (측정 결과)

## 재개 메모

- execute 완료(Task 1~7). 다음은 ship — 코드 리뷰(reviewer + domain-expert) → 수정 → 검증 → 문서 동기화 → 아카이브 → PR.
- 신규 자산: `docker-compose.benchmark.yml`(heap 상한 + payment 포트 노출), `scripts/bench-seed-stock.sh`, `scripts/k6/{helpers.js, async-payment.js, run-benchmark.sh, verify-settlement.sh}`, `docs/topics/K6-ASYNC-BENCHMARK-REPORT.md`.
- **측정 완료**: 저/고 2환경 clean 측정(peak 25, confirm 각 1889). 양환경 checks 100%/timeout 0/유실 0. 동기 응답은 벤더 지연 무관(38ms/21ms), e2e만 벤더 지연 반영(582ms/1.62s) — 비동기 흡수 입증. 상세는 REPORT.
- **측정 중 스크립트 버그 6건 수정**(Task 7 커밋): 컨테이너명 동적탐색 / `{data:...}` 래퍼 파싱 / JVM heap 상한·포트노출(OOM) / threshold 허용 / bench-seed 멱등 / VU·부하곡선 env화.
- **환경 한계**(리뷰 시 인지): 로컬 7.65GB OOM 한계로 baseline 100→200→400 대신 peak 25, gateway 우회 + 관측성 미기동. 절대 TPS는 운영 재측정 필요.
- **현재 docker 스택**: benchmark 측정 상태(pg 고지연, payment benchmark profile, gateway/관측성 down). ship 검증 후 `compose-up.sh --down`/`--clean`으로 정리 가능.
- `results/*.json`은 gitignore(측정 raw, 리포트가 SSOT).

## 최근 완료

- **CLEANUP-BATCH-D** (빌드·테스트 위생 정리 — 통합테스트 Flyway 경합 flaky DB명 분리 + build.gradle deprecated 문법 + 상품 서비스 청소 스케줄러 운영 활성화 + 스케줄러 정책 문서화, 2026-06-14, 이슈/브랜치 #100) — `docs/archive/cleanup-batch-d/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-C** (코드 레벨 정리 — 미사용 코드 제거 + pg 워커 보일러플레이트 헬퍼화 + 테스트 헬퍼 위치 통일, 2026-06-13, 이슈/브랜치 #98) — `docs/archive/cleanup-batch-c/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
