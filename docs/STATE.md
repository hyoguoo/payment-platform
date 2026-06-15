# 현재 작업 상태

> 최종 수정: 2026-06-15

## 활성 작업

- **주제**: K6-ASYNC-BENCHMARK (비동기 결제 경로 k6 부하 측정 시나리오 신규 작성)
- **단계**: ship
- **이슈/브랜치**: #102
- **활성 태스크**: 전 태스크(Task 1~7) 완료 — 다음은 ship(리뷰 + 마무리)
- **산출물**: `docs/topics/K6-ASYNC-BENCHMARK.md` (설계) + `docs/K6-ASYNC-BENCHMARK-PLAN.md` (plan) + `docs/topics/K6-ASYNC-BENCHMARK-REPORT.md` (측정 결과 + 병목 분석 사이클 1/2 + 후속 과제)

## 재개 메모

- execute 완료(Task 1~7) + **병목 분석 사이클 1/2 완료**(사용자 추가 목표). 다음은 ship.
- 신규 자산: `docker-compose.benchmark.yml`(heap·포트·Hikari·reconciler env), `scripts/bench-seed-stock.sh`, `scripts/k6/{helpers.js, async-payment.js(constant-rate·skip-poll), run-benchmark.sh, verify-settlement.sh, sweep.sh}`, `docs/topics/K6-ASYNC-BENCHMARK-REPORT.md`.
- **baseline 측정**: 저/고 2환경(peak 25). 동기 응답은 벤더 지연 무관(38/21ms), e2e만 반영(582ms/1.62s) — 비동기 흡수 입증. 유실 0.
- **병목 사이클1(동기 confirm)**: Hikari 풀(30) 병목, knee ~150(active 30 상한 + pending 64~121). 처방 풀 30→60 → knee 300, p95 65~87%↓. **신뢰도 높음**.
- **병목 사이클2(비동기 e2e)**: 파이프라인 처리량 병목 없음(큐 모두 ~0). consumer 블로킹은 reconciler 30s cascade(중복) + DLT suffix 갭 — 처방(완화+토픽)으로 lag 33204→0. e2e 지연은 폴링 자가부하(http_reqs 664/s)+단일 인스턴스 CPU(정황 증거까지).
- **후속 과제(다음 목표)**: ① payment scale-out 처리량 측정(메모리·gateway·EOS transactional.id 멀티 검증 선행, 운영급 환경 권장) ② DLT 토픽 suffix 갭 버그 수정(별도) ③ status 폴링→push 측정 개선. 상세는 REPORT §후속 과제.
- **환경 한계**: 로컬 7.65GB OOM 한계로 peak 하향, gateway 우회 + 관측성 미기동, reconciler 측정 중 완화(600s). 절대 TPS 무의미, 상대 비교만 유효.
- **현재 docker 스택**: benchmark 측정 상태(Hikari 60, reconciler 600s, pg 저지연, gateway/관측성 down). 정리: `compose-up.sh --down`/`--clean`.
- `results/*.json`은 gitignore(측정 raw, 리포트가 SSOT).

## 최근 완료

- **CLEANUP-BATCH-D** (빌드·테스트 위생 정리 — 통합테스트 Flyway 경합 flaky DB명 분리 + build.gradle deprecated 문법 + 상품 서비스 청소 스케줄러 운영 활성화 + 스케줄러 정책 문서화, 2026-06-14, 이슈/브랜치 #100) — `docs/archive/cleanup-batch-d/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-C** (코드 레벨 정리 — 미사용 코드 제거 + pg 워커 보일러플레이트 헬퍼화 + 테스트 헬퍼 위치 통일, 2026-06-13, 이슈/브랜치 #98) — `docs/archive/cleanup-batch-c/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
