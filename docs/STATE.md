# 현재 작업 상태

> 최종 수정: 2026-06-19

## 활성 작업

- **주제**: CAPACITY-AND-SCALEOUT (결제 처리량 부하 측정 2페이즈 — 단일 인스턴스 자원 병목 규명 → payment 1→2 scale-out)
- **단계**: ship
- **이슈/브랜치**: #104
- **파일**: `docs/CAPACITY-AND-SCALEOUT-PLAN.md` / `docs/topics/CAPACITY-AND-SCALEOUT-REPORT.md`(측정 SSOT, 사이클 3~7 + 종합 결론) / `scripts/usl-fit.py`

## 재개 메모

- **execute 완료 (2026-06-19)**: ✅ T1~10 전부 완료. ship 진입 — 리뷰(reviewer + domain-expert) → 수정 → 최종 검증 → context 동기화 → 아카이브 → PR.
- **이번 토픽 변경 요약(리뷰 대상)**: `docker-compose.benchmark.yml`(payment ports 동적화), `scripts/usl-fit.py` + `scripts/usl-data/*.csv`(USL 도구), `docs/` 측정 산출물(REPORT 사이클 3~7·PLAN·설계). 코드 로직 변경 없음 — 측정 인프라/도구/문서 중심.
- **측정 결론**: 페이즈 1 = 1 인스턴스 병목 Hikari 풀(knee 450). 페이즈 2 = **scale-out 기각**(confirm 1.0×/e2e 1.3×), 병목 이동(풀→공유 DB 경합, CPU 여유). fencing 고유화 정상·정합 완벽. USL N≤2 한계.
- **ship 주의**: 측정 스택 down 상태(재기동 불필요 — 문서/도구 리뷰). `usl-fit.py`는 순수 Python(의존 없음), Python 3.14에서 동작 확인. raw 측정 로그·충돌 override는 `/tmp/cap-bench/`(미커밋).

## 최근 완료

- **K6-ASYNC-BENCHMARK** (비동기 결제 경로 k6 부하 측정 자산 + 병목 분석 — 비동기 흡수 입증 + 동기 confirm Hikari 풀 병목 처방(knee 150→300), DLT suffix 갭(C-12) 발견, 2026-06-15, 이슈/브랜치 #102) — `docs/archive/k6-async-benchmark/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-D** (빌드·테스트 위생 정리 — 통합테스트 Flyway 경합 flaky DB명 분리 + build.gradle deprecated 문법 + 상품 서비스 청소 스케줄러 운영 활성화 + 스케줄러 정책 문서화, 2026-06-14, 이슈/브랜치 #100) — `docs/archive/cleanup-batch-d/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
