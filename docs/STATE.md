# 현재 작업 상태

> 최종 수정: 2026-06-19

## 활성 작업

- **주제**: CAPACITY-AND-SCALEOUT (결제 처리량 부하 측정 2페이즈 — 단일 인스턴스 자원 병목 규명 → payment 1→2 scale-out)
- **단계**: execute
- **활성 태스크**: Task 8 (페이즈 2-A/2-B — scale-out 1→2 처리율 선형성 + 재고 정합 게이트). 준비(T1~4)·페이즈 1(T5~6)·페이즈 2-0(T7) 완료, 결과는 `docs/topics/CAPACITY-AND-SCALEOUT-REPORT.md` 사이클 3~5
- **이슈/브랜치**: #104
- **산출물**: `docs/topics/CAPACITY-AND-SCALEOUT.md` (설계 D1~D6) + `-RESEARCH.md` (USL·Kafka EOS·HikariCP·가상스레드) + `-REPORT.md` (측정 SSOT, 사이클 5까지 기록)

## 재개 메모

- **execute 진행 중 (2026-06-19 세션)**: ✅ T1~7 완료. Task 7(페이즈 2-0) = REPORT 사이클 5. 남은: **Task 8(scale-out 1→2 처리율) → 9(USL 회귀) → 10(REPORT 종합)**.
- **⚠️ 환경 현황 — Task 8 전 정상 복귀 필수**: 현재 payment 2인스턴스가 **충돌 실증 override로 기동 중**(prefix 고정 `payment-collision-fixed` + reconciler 30s, `/tmp/cap-bench/collision-override.yml`). Task 8은 정상 고유 id + reconciler 600s 필요 → override 빼고 재기동:
  `HIKARI_MAX_POOL=80 RECONCILER_TIMEOUT=600 RECONCILER_SCAN_MS=60000 docker compose -f docker/docker-compose.infra.yml -f docker/docker-compose.apps.yml -f docker/docker-compose.benchmark.yml up -d --no-build --wait --scale payment-service=2 payment-service`
- **Task 7 결론**: baseline 1인스턴스 처리 한계 ≈ **rate 450**(gateway 경유, 풀 80) = scale-out 1× 기준점. fencing 고유화 정상(fenced 0·분산 0.69%·중복 0), rebalance 안전(fenced 0), 의도적 충돌 재현(ProducerFenced 9·재고 차이 3=0.12%). 통찰: txn.id=`prefix+group+topic+partition`이라 정상 배타 파티션 무탈·**rebalance overlap만 fencing** → D3 가치=전환 안전성.
- **Task 8 합격 기준**: 2인스턴스 처리율비 **≥1.6×**(~rate 720) & 부하 분산 편차 ≤10% & silent loss 0 & 재고 정합(Task 3 정합식 AND 결합). consumer events.confirmed 파티션 점유(3 vs 인스턴스 2 = 2:1 편향)를 측정 메타로 기록.
- **측정 환경**: payment `ports: "8080"`(host 동적 할당, scale 충돌 회피) → 부하는 gateway:8090 lb 분산, actuator는 `docker compose … port --index N payment-service 8080`로 인스턴스별 동적 포트 수집. Hikari 80·MySQL max_conn 300·재고 1천만.
- **측정 위생**: 동기 sweep 후 e2e 전 events.confirmed lag 0 소진. **payment_event 누적 주의**(재시드는 stock만) → silent loss 판정은 오늘/구간 격리 또는 run-benchmark JSON 교차. 변수 격리(튜닝↔scale-out 분리). **로컬 7.65GB에 2인스턴스 idle ~6GB(heap 700m 상한 보호) — 부하 시 heap 모니터**.
- **부가 후속**: ① 인스턴스 restart 가용성 갭 16%(graceful shutdown + gateway retry) ② 재고 미세 갭 3건(abort 보상 INCR 경로 정밀 검증).

## 최근 완료

- **K6-ASYNC-BENCHMARK** (비동기 결제 경로 k6 부하 측정 자산 + 병목 분석 — 비동기 흡수 입증 + 동기 confirm Hikari 풀 병목 처방(knee 150→300), DLT suffix 갭(C-12) 발견, 2026-06-15, 이슈/브랜치 #102) — `docs/archive/k6-async-benchmark/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-D** (빌드·테스트 위생 정리 — 통합테스트 Flyway 경합 flaky DB명 분리 + build.gradle deprecated 문법 + 상품 서비스 청소 스케줄러 운영 활성화 + 스케줄러 정책 문서화, 2026-06-14, 이슈/브랜치 #100) — `docs/archive/cleanup-batch-d/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
