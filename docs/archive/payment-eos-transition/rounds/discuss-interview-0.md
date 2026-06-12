# PAYMENT-EOS-TRANSITION — Round 0 Interviewer

> 2026-05-17 · Opus · Round 0 (Interviewer)

## Ambiguity ledger (4트랙)

| 트랙 | 항목 | 상태 | 근거 |
|---|---|---|---|
| scope | 위키 정합 방향 | ✅ EOS 전환 | 사용자 답변: 위키 알대로 EOS 로 |
| scope | 마이그레이션 전략 | ✅ 빅뱅 (1 PR) | 사용자 답변: stock_outbox 묶음 전체 drop + EOS 도입 한 PR |
| scope | downstream (product-service) 포함 여부 | ✅ 포함 | 사용자 답변: product-service consumer `isolation.level=read_committed` 본 PR 포함. EOS 의 강제 전제 |
| scope | non-goals | ✅ 명시됨 | TTL 정리 스케줄러 (TC-11 이관), payment-service 다중 인스턴스 동시 운영 검증 (Phase 5 이관), payment-service Flyway db/schema+db/seed 분리 (별 토픽 — FLYWAY-USER-SEED-GAP 라인) |
| constraints | 가용성 결 트레이드오프 | ✅ 수용 | 사용자 답변: 학습용 + 도메인 정합성 우선. Kafka tx coordinator 죽으면 처리 멈춤 OK |
| constraints | transactional.id 부여 정책 | ✅ appname + HOSTNAME | 사용자 답변: `${spring.application.name}-${HOSTNAME}` 형태. 재시작 후 동일 + 다중 인스턴스 확장 가능 |
| constraints | 운영 환경 가정 | ✅ Docker / k8s 환경의 HOSTNAME 환경변수 존재 가정 | 코드 조사: 현재 `application.yml` `eureka.instance.instance-id` 가 `spring.application.name + random.uuid + port` 패턴 → EOS 부적합. HOSTNAME 으로 대체 결정 |
| outputs | `payment_event_dedupe` 스키마 | ✅ stock_commit_dedupe 패턴 차용 | 위키 시퀀스에 컬럼 정의 없음 확인 (`message-delivery-and-dedupe.md:113, 128` + `architecture.md:209` 가 전부). 사용자 답변: `event_uuid PK + order_id + status + received_at + expires_at + INDEX idx_expires_at` |
| outputs | 위키 동기화 | ✅ 본 토픽 verify 단계에서 위키도 동기화 | `message-delivery-and-dedupe.md` + `outbox-pattern.md` 의 "Phase 6 작업 중" 마커 제거 + 최신화 |
| verification | 테스트 계층 | ✅ 단위 + 통합 (Testcontainers Kafka + MySQL) | 사용자 답변: 트랜잭션 롤백 / abort 재배달 / 중복 INSERT 시나리오 커버. k6 부하 측정 제외 |
| verification | 성공 관찰 지표 | ✅ 합의됨 (다음 라운드 구체화) | `./gradlew test` 전체 PASS, payment-service 측 신규 통합 테스트 ≥ 3 (EOS commit / EOS abort 재배달 / 중복 INSERT IGNORE), 위키 시퀀스와 코드 시그니처 1:1 매핑 |

## 결정 사항 요약

### A. 위키 정합 방향
위키 `message-delivery-and-dedupe.md` 의 EOS 안 (Kafka tx + INSERT IGNORE `payment_event_dedupe`) 으로 코드를 전환한다. 위키 자체는 본질에 가깝고, "Phase 6 작업 중" 마커는 코드 미반영 표시일 뿐이라는 사용자 판단.

### B. 마이그레이션 전략
빅뱅 — 1 PR 안에 다음을 모두 수행:
- `KafkaTransactionManager` 빈 + producer EOS 설정 (`transactional.id` / `enable.idempotence=true`)
- `payment_event_dedupe` 테이블 신규 (Flyway V2)
- `PaymentConfirmResultUseCase.handleApproved` 의 `stock_outbox INSERT` + `StockOutboxReadyEvent` publish 를 **직접 `producer.send(stock-committed)`** 로 교체
- `handleApproved` 에 `isTerminal` 가드 추가
- `StockOutbox` 도메인 + `StockOutboxRepository` + `StockOutboxFactory` + `StockOutboxReadyEvent` + `StockOutboxImmediateEventHandler` + `StockOutboxWorker` + `StockOutboxKafkaPublisher` + `StockOutboxPublisherPort` + `StockOutboxEntity` + `JpaStockOutboxRepository` + `StockOutboxRepositoryImpl` + `FakeStockOutboxRepository` 전체 삭제 (테스트 포함)
- `payment_stock_outbox` 테이블 drop (Flyway V3)
- `ConfirmedEventConsumer` 가 `KafkaTransactionManager` 통합된 `kafkaListenerContainerFactory` 사용
- product-service `StockCommitConsumer` 에 `spring.kafka.consumer.isolation-level=read_committed` 적용

### C. 가용성 트레이드오프
수용. 학습용 프로젝트 + 위키 정합성 우선. broker / tx coordinator 죽으면 처리 멈춤 → `CONCERNS.md` 에 등재 (변경 후 한계 명시).

### D. transactional.id 정책
`${spring.application.name}-${HOSTNAME}` 패턴.
- Kubernetes / Docker compose 의 HOSTNAME 환경변수 사용 (Docker compose 에서는 `hostname: payment-service` 설정으로 단일 인스턴스 가정에서도 안정 id)
- 다중 인스턴스 확장 시 자연스럽게 인스턴스별 유일 id 확보
- 재시작 후 동일 → fencing 정상 (outdated producer 가 동시에 살아있을 때 새 producer 가 우선)
- HOSTNAME 없는 환경 (로컬 IDE 등) 의 fallback 정책은 Architect 가 구체화 (가정: `${spring.application.name}-local` 로 default)

### E. dedupe 테이블 스키마
```sql
CREATE TABLE IF NOT EXISTS payment_event_dedupe
(
    event_uuid  VARCHAR(64)  NOT NULL,
    order_id    BIGINT       NOT NULL,
    status      VARCHAR(32)  NOT NULL,           -- APPROVED / FAILED / QUARANTINED
    received_at TIMESTAMP    NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_uuid),
    INDEX idx_expires_at (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
```
- TTL 정리 스케줄러는 본 토픽 범위 밖 (TC-11 후속). 본 토픽은 INSERT IGNORE 만 적용
- `expires_at` 은 Kafka retention (7일) + 복구 버퍼 (1일) = 8일로 설정 (현재 `STOCK_OUTBOX_TTL` 과 같은 의미)

### F. 검증 계층
- 단위 테스트: `PaymentConfirmResultUseCase` 의 분기 + 멱등 INSERT 결과 처리 + isTerminal 가드
- 통합 테스트 (Testcontainers Kafka + MySQL):
  - EOS commit 정상 흐름 — consumer offset commit + stock-committed 발행이 한 tx 단위
  - EOS abort 흐름 — RDB tx rollback 시 stock-committed 메시지가 product-service 측 (read_committed) 에서 보이지 않음
  - 중복 INSERT 흐름 — 같은 event_uuid 두 번 도달 시 비즈니스 skip + 발행은 진행 (위키 line 141 보장)
- k6 부하 측정은 본 토픽 범위 밖 (Phase 5 자물쇠)

## 3-Path Routing 트래픽

| Round 0 질문 | Path | 비고 |
|---|---|---|
| 위키 정합 방향 | Path 2 (user) | 메타 결정. 토픽 전체 방향 |
| 가용성 트레이드오프 | Path 2 (user) | 메타 결정 |
| 마이그레이션 전략 | Path 2 (user) | 실행 방식 |
| 검증 계층 | Path 2 (user) | verification 트랙 |
| transactional.id | Path 3 (hybrid) | 코드 조사 (random.uuid 부적합) → 사용자 판단 |
| product-service isolation | Path 3 (hybrid) | 코드 조사 (현재 미설정) → 사용자 판단 |
| dedupe 스키마 | Path 3 (hybrid) | 위키 조사 (정의 없음 확인) → stock_commit_dedupe 패턴 사용자 확인 |

Dialectic Rhythm Guard: Path 2 가 4연속 후 Path 3 가 3연속 — 규칙 (Path 1/4 가 3연속) 위반 없음.

## 종료 조건 충족

- ✅ 4트랙 모두 최소 1회 커버
- ✅ 핵심 가정이 사용자 확인을 거침 (6개 메타 결정 모두)

## Architect 에게 넘기는 입력

1. 위 결정 사항 A~F 를 `docs/topics/PAYMENT-EOS-TRANSITION.md` 의 본문 (사전 브리핑 다음) 으로 풀어 작성
2. `discuss-ready.md` Gate checklist 전 항목 yes 가 나오도록 다음을 명시:
   - scope: 모듈/패키지 경계 (payment-service `application/usecase/PaymentConfirmResultUseCase` + `application/port/out/EventDedupeStore` (신규) + `infrastructure/dedupe/JdbcEventDedupeStore` + `infrastructure/config/KafkaProducerConfig` + `infrastructure/messaging/consumer/ConfirmedEventConsumer` + Flyway V2/V3 + product-service `application-*.yml`)
   - hexagonal layer 배치: 포트 위치 (application/port/out) + JDBC 어댑터 위치 (infrastructure/dedupe)
   - 새 상태 없음 (mermaid 상태 전이 다이어그램 불필요) — 단 결제 결과 컨슘의 to-be 시퀀스 다이어그램 (위키 line 105~123 과 정합) 필수
   - 전체 결제 흐름 호환성 검토 (CONFIRM-FLOW.md 와 정합 확인 사항 명시)
   - acceptance criteria: 위 F 항목 그대로
   - verification plan: 위 F 항목 그대로
   - 결정 사항 섹션: A~F
3. Domain Expert 용 추가 명시:
   - 멱등성 전략: event_uuid 소스 (`message.eventUuid()`) / 수명 (8일 expires_at) / 충돌 처리 (INSERT IGNORE → 0 row 면 비즈니스 skip + 발행은 진행)
   - 장애 시나리오 ≥ 3: (a) RDB commit 직후 producer commit 직전 crash → 재배달 시 INSERT IGNORE skip + 발행 재실행 / (b) producer commit 직후 consumer offset commit 직전 crash → 같은 시나리오 / (c) Kafka tx coordinator 응답 불능 → 처리 멈춤 (가용성 한계 등재) / (d) abort 발생 시 product-service 가 read_committed 라 메시지 invisible
   - 재시도 정책: 기존 `DefaultErrorHandler` (FixedBackOff 1초 × 5회 → DLQ) 그대로 유지 — EOS 와 직교
   - PII: 새로 도입되는 PII 없음 (event_uuid 는 비식별)
