# Archive

> **⚠️ AI 에이전트는 이 디렉토리를 읽지 않는다. (`docs/context/`만 참조할 것)**
> 완료된 작업 뭉치의 기록 보존용 아카이브입니다.

---

## 작업 뭉치 목록

| 폴더 | 작업 내용 | 기간 |
|------|---------|------|
| `async-payment-migration/` | 비동기 결제 처리 마이그레이션 (Sync / Outbox / Kafka 전략 구현) | 2026-03-14 ~ 2026-03-18 |
| `checkout-idempotency/` | Checkout 멱등성 구현 (Caffeine 캐시 기반 중복 요청 감지, 201/200 분기) | 2026-03-22 |
| `async-benchmark-improvement/` | k6 벤치마크 개선 — ramping-arrival-rate 전환, cAdvisor/Prometheus remote write 연동, 전략 자동 전환 | 2026-03-27 |
| `k6-benchmark-measurement-improvement/` | k6 측정 개선 — e2e_under_load/e2e_idle 분리, outbox-parallel 케이스 추가, 3케이스 자동화 | 2026-03-27 |
| `k6-benchmark-methodology-fix/` | k6 측정 방법론 수정 — e2e_idle 별도 run 분리, TIMEOUT 명시 집계, confirm_requests Counter, sync 부하 상향 | 2026-03-27 |
| `outbox-immediate-dispatch/` | Outbox 즉시 처리 — TX 커밋 후 @Async+@TransactionalEventListener로 Toss API 즉시 호출, OutboxWorker recovery 전용 전환 | 2026-03-28 |
| `k6-benchmark-redesign/` | k6 벤치마크 재설계 — idle 제거, e2e_completion_ms 메트릭 통일, 부하 단계 100→200→400 통일 | 2026-03-28 |
