# discuss-interview-0 — PG-CONFIRM-LISTENER-SPLIT Round 0 Interviewer

> stage: discuss
> round: 0
> persona: Interviewer (Main thread)
> 작성일: 2026-05-09

---

## 사용자 사전 허가 컨텍스트

사용자가 STOCK-COMPENSATION-RECOVERY 봉인 직후 "다음 작업도 알아서 계속" 명시. 본 토픽은 위키 정합 작업이라 위키 봉인된 결정을 자연 가정으로 따른다. Path 2 (사용자 직접 질문) 는 핵심 스코프 분기 1건만 수행.

---

## Ambiguity Ledger (4트랙)

### scope (범위)

| 항목 | 상태 | 근거 |
|---|---|---|
| 코드 변경 영역 | 확정 | pg-service 만 — `PgConfirmService` / `PgVendorCallService` / `PaymentConfirmConsumer` / `PgInbox` / `DuplicateApprovalHandler` / scheduler 패키지 |
| 위키 갱신 영역 | 확정 (Path 2) | **사용자 결정**: 코드 + 위키 동시 갱신. `pg-confirm-flow.md` (이미 분리 안 봉인) + `outbox-channel-dispatch.md` (작업 큐 + 발행 큐 2개 채널로 본문 갱신) 본 토픽 plan 범위 |
| 외부 서비스 | 확정 | payment-service / product-service / user-service 변경 0 |
| 외부 PG (Toss/NicePay/Fake) | 확정 | 벤더 어댑터 정책 변경 0 — 호출 진입점만 워커 VT 로 이동 |
| DB 스키마 | 확정 | `pg_inbox` status enum 에 `PENDING` 추가 (Flyway migration 1건) |

### constraints (제약)

| 항목 | 상태 | 근거 |
|---|---|---|
| 위키 = 진실원 | 확정 | 위키 `pg-confirm-flow.md` 가 분리 안으로 봉인된 SoT. 코드를 위키 정합. PENDING 상태 추가 / 인메모리 채널 / 좀비 폴링 모두 위키 인용 |
| hexagonal layer 룰 | 확정 | port (application) → adapter (infrastructure) 의존 방향 유지. 채널 / 워커는 infrastructure |
| 좀비 회수 임계 baseline | 가정 | 위키 인용 값 채택 — PENDING 수십 초 / IN_PROGRESS 60s (벤더 timeout × 2). 측정 없는 baseline 인정. PHASE2 부하 측정 후 정밀화 |
| 워커 VT 풀 크기 / 채널 capacity | 가정 | yml 설정 키 도입 + 기본값 baseline 채택. PHASE2 측정 정밀화. 본 토픽은 baseline 도입까지 |
| 호환성 | 확정 | 운영 인프라 (Kafka / MySQL / Redis) 변경 0 |

### outputs (산출물)

| 항목 | 상태 |
|---|---|
| 코드 변경 | listener 책임 축소 + 작업 큐 인메모리 채널 + 워커 VT (TX_A → 벤더 → TX_B) + 좀비 폴링 |
| Flyway migration | `pg_inbox` status enum 에 PENDING 추가 |
| 테스트 | 단위 (listener INSERT 짧음 / 워커 TX_A → 벤더 → TX_B / 좀비 회수 / 채널 가득 폴백) + 통합 (벤더 latency 격리 검증 / DuplicateApprovalHandler 좀비 재진입 보정) |
| 위키 갱신 | `pg-confirm-flow.md` (현재 본문 그대로 정합 — `🚧 일부 합쳐져 있음` noti 제거 가능 시점은 verify) + `outbox-channel-dispatch.md` (작업 큐 + 발행 큐 2개 채널로 본문 갱신) |
| context 문서 | ARCHITECTURE / CONFIRM-FLOW (pg 측 인용) / STRUCTURE / TODOS (TC-14 봉인 표기) 등 verify 단계에서 갱신 |

### verification (검증)

| 항목 | 상태 |
|---|---|
| 단위 테스트 | RED → GREEN 사이클 — `PaymentConfirmConsumer` listener TX 짧음 / 워커 VT TX 분리 / 좀비 폴링 회수 |
| 통합 테스트 | Embedded Kafka + Testcontainers MySQL — listener 가 벤더 latency 에 묶이지 않음 검증 + 좀비 회수 시 `DuplicateApprovalHandler` 보정 시나리오 |
| 회귀 | 기존 pg-service 단위 + 통합 테스트 모두 PASS 유지 (현재 207 PASS 기준) |
| 위키-코드 정합 | verify 단계에서 `pg-confirm-flow.md` 의 분리 안 다이어그램과 코드 일치 확인. `🚧` noti 제거 가능 |

---

## 3-Path Routing

| 질문 / 가정 | Path | 결과 |
|---|---|---|
| 위키 다른 페이지 (`outbox-channel-dispatch.md`) 갱신 범위 | **Path 2 (사용자)** | "코드 + 위키 동시 갱신" — 본 토픽 plan 에 포함 |
| `pg_inbox` status enum 에 PENDING 추가 (위키 봉인) | Path 1 (코드 + 위키) | 자연 가정 — 코드 측 `pg-service/.../domain/PgInboxStatus.java` (또는 동등) 에 PENDING 추가 |
| 인메모리 채널 vs 다른 핸드오프 | Path 1 (위키) | 위키 봉인 — 인메모리 채널 (cap=1024) 그대로. 기존 `PgOutboxChannel` 패턴 재사용 |
| 좀비 회수 임계 baseline | Path 1 (위키) | 위키 인용 — PENDING 수십 초 / IN_PROGRESS 60s |
| 워커 VT 풀 크기 / 채널 capacity | Path 1 (코드 패턴) | 기존 `PgOutboxImmediateWorker` 패턴 — yml 설정 + 기본값 baseline |
| `DuplicateApprovalHandler` 보정 정합 | Path 1 (코드) | 워커 크래시 후 좀비 회수 시 벤더 재호출 → ALREADY_PROCESSED → 보정 — 분리 안에서도 그대로 작동 (벤더 idempotency-key=orderId 가 매번 같음) |

**Dialectic Rhythm Guard**: Path 1 5건 + Path 2 1건 — 균형 OK. (Interviewer 룰 "Path 2 없이 결론 금지" 충족)

---

## 확정된 가정 리스트

1. **위키 = 진실원**, 본 토픽은 코드를 위키에 정합 + 위키 다른 페이지 (`outbox-channel-dispatch.md`) 도 분리 안 적용 시 작업 큐 + 발행 큐 2개 채널로 본문 갱신 (사용자 결정).
2. **`pg_inbox` status enum 에 PENDING 신규 추가** (Flyway migration 1건). 기존 `NONE → IN_PROGRESS → APPROVED/FAILED/QUARANTINED` 전이에 PENDING 진입 추가.
3. **인메모리 채널 패턴 재사용** — 기존 `PgOutboxChannel` + `PgOutboxImmediateWorker` 와 같은 패턴으로 작업 큐 도입 (`PgInboxChannel` + `PgInboxWorker` 같은 신규).
4. **좀비 회수 baseline** — PENDING 수십 초 / IN_PROGRESS 60s (벤더 timeout × 2). 측정 없는 위키 인용 값.
5. **워커 VT 풀 크기 + 채널 capacity** — yml 설정 키 도입, 기본값 baseline (channel cap=1024, worker-count 5 정도). PHASE2 측정 정밀화.
6. **벤더 어댑터 정책 변경 0** — Toss / NicePay / Fake 전략 그대로. 호출 진입점만 워커 VT 로 이동.
7. **`DuplicateApprovalHandler` 보정 경로** — 분리 안에서도 그대로 작동 (벤더 idempotency-key 매번 같음). 워커 크래시 후 좀비 회수 시 벤더 재호출 → ALREADY_PROCESSED → 보정.
8. **payment-service / product-service / user-service 변경 0**.

## 종료 조건 충족

- [x] 4트랙 (scope/constraints/outputs/verification) 모두 최소 1회 커버
- [x] 핵심 가정 8건 명시 (위키 봉인 + 사용자 답변 1건 기반)
- [x] Path 2 1건 — 사용자 명시적 답변 받음
- [x] Dialectic Rhythm Guard — Path 1 / Path 2 균형

## 다음 라운드

Round 1 — Architect dispatch. 본 ledger 의 가정 8건 + 위키 분리 안 + 코드 사실을 기반으로 `docs/topics/PG-CONFIRM-LISTENER-SPLIT.md` 본문 (§4 결정 + §5 to-be 다이어그램 + §6 컴포넌트 인벤토리 등) 작성.
