# 현재 작업 상태

> 최종 수정: 2026-06-19

## 활성 작업

- **주제**: CAPACITY-AND-SCALEOUT (결제 처리량 부하 측정 2페이즈 — 단일 인스턴스 자원 병목 규명 → payment 1→2 scale-out)
- **단계**: execute
- **활성 태스크**: Task 10 (측정 리포트 종합 — REPORT 연장 SSOT). 측정·분석(T1~9) 완료, REPORT 사이클 3~7 기록됨 — 남은 건 결론/후속 트리거 정리 + 최종 검토
- **이슈/브랜치**: #104
- **산출물**: `docs/topics/CAPACITY-AND-SCALEOUT-REPORT.md` (측정 SSOT, 사이클 7까지) + `.md`(설계) + `-RESEARCH.md` + `scripts/usl-fit.py`(USL 피팅 도구)

## 재개 메모

- **execute 진행 중 (2026-06-19 세션)**: ✅ T1~9 완료. 남은: **Task 10(REPORT 종합)** 만. Task 10 후 execute 완료 → ship.
- **Task 10 범위**: REPORT 사이클 3~7이 이미 기록됨(페이즈 1 자원 sweep·폴링 전략·scale-out·USL). Task 10은 **REPORT 전체 결론 섹션 + 페이즈 3+ 후속 트리거 정리 + 최종 일관성 검토**. 새 측정 없음.
- **전체 측정 결론 요약**: 페이즈 1 = 1 인스턴스 병목 Hikari 풀(knee 450, DB 미천장)·폴링 backoff win-win. 페이즈 2 = **scale-out 기각**(confirm 1.0×/e2e 1.3×), 병목이 Hikari 풀→**공유 DB 경합**으로 이동(CPU 여유). fencing 고유화 정상·rebalance 안전·정합 완벽. USL N≤2 한계로 제약식만.
- **후속 트리거(REPORT 기록)**: ① payment DB 스케일(읽기복제/샤딩) ② events.confirmed 파티션 수=인스턴스 배수(2:1 편향 제거) ③ 인스턴스 restart 가용성 갭 16%(graceful shutdown+gateway retry) ④ 충돌 시 재고 미세 갭 3건(abort 보상 INCR) ⑤ Kafka EOS commit 오버헤드 프로파일링.
- **환경 현황**: 측정 스택 **down 완료**(메모리 회수). Task 10은 문서 작업이라 스택 불필요. 재기동 필요 시 Task 8 절차(워밍업 필수).
- **측정 자산**: `docker-compose.benchmark.yml` payment ports 동적화 + `scripts/usl-fit.py` + `scripts/usl-data/*.csv`(커밋됨). raw 측정 로그·충돌 override는 `/tmp/cap-bench/`(미커밋, REPORT가 SSOT).

## 최근 완료

- **K6-ASYNC-BENCHMARK** (비동기 결제 경로 k6 부하 측정 자산 + 병목 분석 — 비동기 흡수 입증 + 동기 confirm Hikari 풀 병목 처방(knee 150→300), DLT suffix 갭(C-12) 발견, 2026-06-15, 이슈/브랜치 #102) — `docs/archive/k6-async-benchmark/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-D** (빌드·테스트 위생 정리 — 통합테스트 Flyway 경합 flaky DB명 분리 + build.gradle deprecated 문법 + 상품 서비스 청소 스케줄러 운영 활성화 + 스케줄러 정책 문서화, 2026-06-14, 이슈/브랜치 #100) — `docs/archive/cleanup-batch-d/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
