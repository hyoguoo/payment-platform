# 현재 작업 상태

> 최종 수정: 2026-06-19

## 활성 작업

- **주제**: CAPACITY-AND-SCALEOUT (결제 처리량 부하 측정 2페이즈 — 단일 인스턴스 자원 병목 규명 → payment 1→2 scale-out)
- **단계**: execute
- **활성 태스크**: Task 9 (페이즈 2-C — USL 회귀 분석 + 피팅 스크립트). 준비(T1~4)·페이즈 1(T5~6)·페이즈 2(T7~8) 완료, 결과는 `docs/topics/CAPACITY-AND-SCALEOUT-REPORT.md` 사이클 3~6
- **이슈/브랜치**: #104
- **산출물**: `docs/topics/CAPACITY-AND-SCALEOUT.md` (설계 D1~D6) + `-RESEARCH.md` (USL·Kafka EOS·HikariCP·가상스레드) + `-REPORT.md` (측정 SSOT, 사이클 6까지)

## 재개 메모

- **execute 진행 중 (2026-06-19 세션)**: ✅ T1~8 완료. Task 8(scale-out) = REPORT 사이클 6. 남은: **Task 9(USL 회귀) → 10(REPORT 종합)**.
- **Task 8 결론(기각)**: confirm 처리율비 ~1.0×(2 인스턴스 knee 450 = 1 인스턴스), e2e ~1.3×(75→100). **병목 = 공유 DB 경합**(MySQL 147% 정체+lock/IO, Kafka EOS commit 직렬화 — CPU는 5.5/10 여유). 정합 완벽(redis==RDB 차이 0, silent loss 0). 파티션 2:1 편향 → 고발행 시 consumer 백로그 비대칭.
- **⚠️ Task 9 핵심 한계**: **N≤2(로컬 메모리)라 USL 3파라미터(α·β·γ) 다점 회귀 underdetermined**. 측정점 2개(N=1,2)뿐 → Nmax 추정 불가. Task 9는 (a) USL 피팅 스크립트 작성(입력 CSV→파라미터, 재현 가능) + (b) **N≤2 한계를 정직히 기록**하고 가용 2점으로 contention 방향성만(α 하한) 제시하는 방향으로 조정 필요. 사용자와 범위 확인 권장.
- **Task 9 입력 측정점**: confirm 동기 — N=1 처리율 한계 rate 450(active 62), N=2 rate ~450(active 합 160, throughput 정체). e2e — N=1 흡수 75, N=2 흡수 100~125. (REPORT 사이클 5/6 표.)
- **환경 현황**: 측정 스택 **기동 중**(payment 2 인스턴스 정상, 동적 포트). Task 9는 분석/스크립트 작성이라 부하 측정 불필요 → **스택 down 가능**: `docker compose -f docker/docker-compose.infra.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.benchmark.yml down`. 재기동 시 Task 8 절차(워밍업 필수).
- **측정 자산**: `docker-compose.benchmark.yml` payment ports 동적화(커밋됨). 충돌 override·raw 측정 로그는 `/tmp/cap-bench/`(미커밋, REPORT가 SSOT).
- **부가 후속(REPORT에 기록)**: ① 인스턴스 restart 가용성 갭 16%(graceful shutdown+gateway retry) ② 충돌 시 재고 미세 갭 3건(abort 보상 INCR) ③ DB 스케일(읽기복제/샤딩) ④ events.confirmed 파티션 수=인스턴스 배수(편향 제거).

## 최근 완료

- **K6-ASYNC-BENCHMARK** (비동기 결제 경로 k6 부하 측정 자산 + 병목 분석 — 비동기 흡수 입증 + 동기 confirm Hikari 풀 병목 처방(knee 150→300), DLT suffix 갭(C-12) 발견, 2026-06-15, 이슈/브랜치 #102) — `docs/archive/k6-async-benchmark/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-D** (빌드·테스트 위생 정리 — 통합테스트 Flyway 경합 flaky DB명 분리 + build.gradle deprecated 문법 + 상품 서비스 청소 스케줄러 운영 활성화 + 스케줄러 정책 문서화, 2026-06-14, 이슈/브랜치 #100) — `docs/archive/cleanup-batch-d/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
