---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
stopped_at: 버그 수정 완료 — 벤치마크 실행 대기 중 (BENCH-02/03)
last_updated: "2026-03-18"
last_activity: 2026-03-18 — 비동기 전략 버그 수정 4종 완료 (existsByOrderId, 재고 실패, validateCompletionStatus)
progress:
  total_phases: 8
  completed_phases: 8
  total_plans: 18
  completed_plans: 18
  percent: 95
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-14)

**Core value:** 어떤 비동기 전략을 쓰든 Spring Bean 설정만으로 교체 가능하고, k6로 성능 차이를 즉시 측정할 수 있어야 한다
**Current focus:** 벤치마크 실행 (BENCH-02/03 미완료) — application-benchmark.yml scheduler.enabled 수정 후 k6 실행 필요

## Current Position

Phase: 8/8 완료 (모든 구현 완료)
Status: 벤치마크 실행 대기 중
Last activity: 2026-03-18 — 비동기 전략 버그 수정 4종 완료

Progress: [██████████] 95% (벤치마크 실측값 기록만 남음)

## Performance Metrics

*Updated after each plan completion*
| Phase 01-port-contract-status-endpoint P01 | 2m | 2 tasks | 3 files |
| Phase 01-port-contract-status-endpoint P02 | 12m | 2 tasks | 8 files |
| Phase 01-port-contract-status-endpoint P03 | 6m | 2 tasks | 7 files |
| Phase 02-sync-adapter P01 | 3m | 2 tasks | 2 files |
| Phase 03-db-outbox-adapter P01 | 6m | 2 tasks | 16 files |
| Phase 03-db-outbox-adapter P02 | 6m | 2 tasks | 4 files |
| Phase 03-db-outbox-adapter P03 | 3m | 1 tasks | 3 files |
| Phase 04-kafka-adapter P01 | 4m | 2 tasks | 7 files |
| Phase 04-kafka-adapter P02 | 4m | 2 tasks | 5 files |
| Phase 04-kafka-adapter P03 | 8 | 1 tasks | 4 files |
| Phase 04-kafka-adapter P04 | 15 | 1 tasks | 1 files |
| Phase 04-kafka-adapter P05 | 2 | 1 tasks | 1 files |
| Phase 05-k6-benchmark P02 | 2 | 2 tasks | 4 files |
| Phase 05-k6-benchmark P03 | 3 | 1 tasks | 1 files |
| Phase 08-refactor-confirm-adapters P01 | 15m | 2 tasks | 6 files |
| Phase 08-refactor-confirm-adapters P02 | 5m | 2 tasks | 2 files |
| Phase 08-refactor-confirm-adapters P03 | 3m | 2 tasks | 8 files |

## Accumulated Context

### Roadmap Evolution

- Phase 8 added: Refactor Confirm Adapters — KafkaConfirmAdapter/OutboxConfirmAdapter 서비스 오케스트레이션 로직을 application 레이어로 이동, KafkaTemplate 포트 추상화

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Pending: Outbox storage — dedicated `payment_outbox` table vs reuse `PaymentProcess.PENDING` (Phase 3 blocker — resolve during Phase 3 planning via `PaymentRecoverServiceImpl` query review)
- Pending: Kafka 어댑터 시 202 / Sync 어댑터 시 200 유지 여부 (Phase 1에서 `PaymentConfirmAsyncResult.status` 설계로 확정)
- [Phase 01-port-contract-status-endpoint]: ResponseType enum을 PaymentConfirmAsyncResult 내부 enum으로 정의 (SYNC_200/ASYNC_202) — 컨트롤러가 Spring Bean 설정 없이 HTTP 상태 코드 결정 가능
- [Phase 01-port-contract-status-endpoint]: PaymentConfirmResult.java 유지 — PaymentConfirmServiceImpl 내부 DTO로 계속 사용
- [Phase 01-port-contract-status-endpoint]: PaymentControllerMvcTest(@WebMvcTest) 추가: Docker API 버전 불일치 환경에서 PORT-02 검증을 위해 단위 테스트로 대체
- [Phase 01-port-contract-status-endpoint]: SyncConfirmAdapter @ConditionalOnProperty(sync, matchIfMissing=true)로 등록 — spring.payment.async-strategy 미설정 시 자동 활성화
- [Phase 01-port-contract-status-endpoint]: PaymentLoadUseCase를 PaymentController에 직접 DI: 포트 인터페이스 없이 사용 — 플랜 명세에 따라 usecase 직접 주입
- [Phase 01-port-contract-status-endpoint]: PaymentControllerMvcTest에 STATUS 테스트 3개 추가: Docker API 불일치 환경에서 @WebMvcTest로 STATUS-01~03 자동 검증 달성
- [Phase 02-sync-adapter]: SYNC-01/02/03 공식화 완료 — SyncConfirmAdapter + 테스트는 Phase 1에서 완료됨, Phase 2에서 요구사항 체크마크만 업데이트
- [Phase 02-sync-adapter]: SYNC-01/02/03 공식화 완료 — SyncConfirmAdapter + 테스트는 Phase 1에서 완료됨, Phase 2에서 요구사항 체크마크만 업데이트
- [Phase 03-db-outbox-adapter]: PaymentConfirmService 포트에 throws PaymentOrderedProductStockException 선언: OutboxConfirmAdapter가 재고 부족 checked exception 전파 필요
- [Phase 03-db-outbox-adapter]: Outbox storage: 전용 payment_outbox 테이블 사용 — PaymentProcess 재사용 없음, Race condition 방지를 위해 독립 테이블 선택
- [Phase 03-db-outbox-adapter]: claimToInFlight()는 PaymentOutbox 객체를 파라미터로 받음: findPendingBatch로 이미 로드된 객체 재사용, 중복 DB 조회 불필요
- [Phase 03-db-outbox-adapter]: OutboxWorker 3단계 트랜잭션 패턴 확립: claimToInFlight(REQUIRES_NEW) → Toss API(트랜잭션 밖) → 결과 저장(별도 트랜잭션)
- [Phase 03-db-outbox-adapter]: PaymentController에 PaymentOutboxUseCase 직접 DI — usecase 직접 주입 패턴 유지
- [Phase 03-db-outbox-adapter]: mapOutboxStatusToPaymentStatusResponse() default→PROCESSING: findActiveOutboxStatus가 DONE/FAILED 필터링하므로 방어적 처리
- [Phase 04-kafka-adapter]: BaseKafkaIntegrationTest는 BaseIntegrationTest 독립 추상 클래스로 설계: Kafka 전용 async-strategy=kafka 오버라이드 필요
- [Phase 04-kafka-adapter]: kafkaTemplate.send()는 executeStockDecreaseOnly() 리턴 이후(트랜잭션 커밋 이후) 호출: 소비자 타이밍 레이스 방지
- [Phase 04-kafka-adapter]: KafkaConfirmListener 컴파일 스텁 Plan 02에서 추가(Plan 01 누락 보완): consume()/handleDlt() 시그니처만, Plan 03에서 완성 예정
- [Phase 04-kafka-adapter]: KafkaConfirmListener.consume()는 PaymentTossRetryableException을 throws 선언으로 re-throw — @RetryableTopic include 설정이 retry 토픽 라우팅 담당
- [Phase 04-kafka-adapter]: KafkaConfirmAdapter.TOPIC = payment-confirm (기존 payment-confirm-requests 변경) — DLT 토픽 payment-confirm-dlq로 KAFKA-06 충족
- [Phase 04-kafka-adapter]: FakeTossHttpOperator를 @TestConfiguration으로 등록: PaymentControllerTest 패턴 동일 적용 — Toss HTTP 호출 모킹으로 실제 외부 API 없이 통합 테스트 실행
- [Phase 04-kafka-adapter]: REQUIREMENTS.md KAFKA-02: 토픽명 payment-confirm-requests → payment-confirm 으로 정정 (Plan 03 변경 소급 반영)
- [Phase 04-kafka-adapter]: REQUIREMENTS.md KAFKA-05: existsByOrderId 가드 명세 → Toss 멱등키 위임 방식으로 정정 (CONTEXT.md 결정 반영)
- [Phase 05-k6-benchmark]: setup() 반환값으로 orderId 풀 전달 — k6 ES6 모듈 환경에서 SharedArray import 대신 단순 패턴 채택
- [Phase 05-k6-benchmark]: getOrderIndex() 소수 97 곱수 패턴: 200 VU × 다수 ITER 조합에서 orderId 인덱스 충돌 최소화
- [Phase 05-k6-benchmark]: FakeTossHttpOperator를 src/main으로 이동: benchmark 프로파일이 프로덕션 컨텍스트에서 Fake 빈 활성화 필요
- [Phase 05-k6-benchmark]: application-benchmark.yml에 read-timeout-millis 포함: FakeTossHttpOperator @Value 바인딩 필수 — docker 프로파일에만 정의되어 있어 benchmark에도 추가
- [Phase 05-k6-benchmark]: BENCHMARK.md를 프로젝트 루트에 위치 — 포트폴리오 리뷰어가 최상위 레벨에서 즉시 접근 가능
- [Phase 05-k6-benchmark]: 비동기 전략의 e2e 레이턴시 열을 별도 분리 — 단순 HTTP 응답 시간과 end-to-end 처리 완료 시간의 차이를 명확히 표현
- [Phase 08-refactor-confirm-adapters]: application/port/out/ 서브패키지 사용: CONTEXT.md 명시 따라 out/ 서브패키지에 outbound 포트 배치
- [Phase 08-refactor-confirm-adapters]: .gitignore out/ 패턴이 src/**/out/ 소스 디렉토리를 무시하는 문제 수정: !src/**/out/ 예외 규칙 추가
- [Phase 08-refactor-confirm-adapters]: 두 서비스를 단일 커밋으로 처리: 동일한 Wave 0 스텁 GREEN 달성 목표를 공유하므로 논리적 단위로 묶음
- [Phase 08-refactor-confirm-adapters]: 어댑터 삭제를 Task 1 컴파일 블로커 해결 과정에서 선행 처리: SyncConfirmAdapter 반환 타입 충돌로 즉시 선행 삭제

### Pending Todos

- [2026-03-18] 벤치마크 실행 — application-benchmark.yml에 `scheduler.enabled: true` 추가 후 k6 3종 실행, BENCHMARK.md 실측값 기입 (BENCH-02/03 완료 조건)

### Resolved Todos

- [2026-03-16 → 2026-03-17] Refactor Confirm Adapters into Service Layer — Phase 08로 완료 (KafkaAsyncConfirmService, OutboxAsyncConfirmService application 레이어 이동, PaymentConfirmPublisherPort 추상화)
- [2026-03-17 → 2026-03-18] 비동기 전략 버그 4종 수정 — existsByOrderId guard, 재고 실패 핸들링(Outbox/Kafka), validateCompletionStatus 추가(OutboxWorker/KafkaConfirmListener) 완료

### Blockers/Concerns

- **[ACTIVE]** `application-benchmark.yml`에 `scheduler.enabled: true` 누락 → Outbox 벤치마크 실행 시 OutboxWorker 미동작. 수정 1줄로 해결 가능. (v1.0-MILESTONE-AUDIT.md 참조)
- [Resolved Phase 3]: Outbox schema → 전용 `payment_outbox` 테이블로 결정 (race condition 방지)
- [Resolved Phase 4]: `spring.kafka.listener.ack-mode=RECORD` → Boot 3.3.3 자동설정 확인 완료

## Session Continuity

Last session: 2026-03-18
Stopped at: 버그 수정 완료 — 벤치마크 실행 대기 중
Resume: application-benchmark.yml scheduler.enabled 수정 → k6 벤치마크 3종 실행 → BENCHMARK.md 실측값 기입
