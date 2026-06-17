# 현재 작업 상태

> 최종 수정: 2026-06-17

## 활성 작업

- **주제**: CAPACITY-AND-SCALEOUT (결제 처리량 부하 측정 2페이즈 — 단일 인스턴스 자원 병목 규명 → payment 1→2 scale-out)
- **단계**: execute
- **활성 태스크**: Task 2 (k6 계측 — confirm·폴링 응답 시각 기록 + 폴링 전략 백오프+지터)
- **이슈/브랜치**: #104
- **산출물**: `docs/topics/CAPACITY-AND-SCALEOUT.md` (설계, discuss 완료 — 사전/요약 브리핑 + D1~D6 + acceptance + 명시 가정 + 측정 위생) + `docs/topics/CAPACITY-AND-SCALEOUT-RESEARCH.md` (서칭 지식: USL·Kafka EOS·HikariCP·가상스레드)

## 재개 메모

- plan 완료(`docs/CAPACITY-AND-SCALEOUT-PLAN.md` 10 태스크). 게이트 라운드1 reviewer·domain-expert revise→전건 반영. 라운드2(서버 회복 후 재개): domain-expert pass(재고 정합식 QUARANTINED 단서 minor 반영), reviewer fail→**DLT 목적지 `.DLT`→`payment.events.confirmed-dlt`(소문자) 정정**(라운드1의 `.DLT` 표기 오류를 jar 디컴파일로 바로잡음). 정정 완료. 다음은 execute Task 1부터.
- **핵심 결정**: D1 공유자원 설정 튜닝까지(갯수 확장 후속) / D2 로컬 2 인스턴스 / D3 hostname 제거 고유화(정상 2인스턴스 한정 가정) / D4 폴링 ON·OFF + 체감(폴링 응답)·처리(`payment_history` 최초 DONE) 이원 계측 + 백오프·지터 / D5 DLT `.dlq` 정합(첫 태스크) / D6 REPORT 연장 + USL 스크립트.
- **plan 시 반드시 태스크화할 게이트 산물**: (a) reconciler 600s를 payment-service 기동에 실제 주입(run-benchmark 현재 pg만 주입) (b) verify-settlement `SETTLE_WAIT_SECONDS` 자동 추종(60 상수 제거) — 둘 다 정상 IN_PROGRESS의 silent loss 오판 차단.
- **측정 위생**: 변수 격리(튜닝↔scale-out 분리), 워밍업, 재고·orderId·dedup token 보존(FLUSHALL 금지), 부하 분산 검증, scale-out 재고 정합 게이트(redis 잔여 vs RDB).
- **측정 순서**: 0 준비 → 1-A 폴링 OFF sweep → 1-B 폴링 ON → 2-0 고유화+fencing 실증+튜닝 → 2-A OFF 선형성 → 2-B ON 종합 → 2-C USL.

## 최근 완료

- **K6-ASYNC-BENCHMARK** (비동기 결제 경로 k6 부하 측정 자산 + 병목 분석 — 비동기 흡수 입증 + 동기 confirm Hikari 풀 병목 처방(knee 150→300), 비동기 파이프라인 병목 없음 확인 + DLT suffix 갭(C-12) 발견, 2026-06-15, 이슈/브랜치 #102) — `docs/archive/k6-async-benchmark/COMPLETION-BRIEFING.md`
- **CLEANUP-BATCH-D** (빌드·테스트 위생 정리 — 통합테스트 Flyway 경합 flaky DB명 분리 + build.gradle deprecated 문법 + 상품 서비스 청소 스케줄러 운영 활성화 + 스케줄러 정책 문서화, 2026-06-14, 이슈/브랜치 #100) — `docs/archive/cleanup-batch-d/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
