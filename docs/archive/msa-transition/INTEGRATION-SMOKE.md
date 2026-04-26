# 로컬 통합 스모크 가이드 (Phase 3 기준)

> Phase 3 완료 후 정상·실패·보상 플로우를 **로컬에서 수동으로** 확인하기 위한 가이드. Phase 4(Toxiproxy 장애주입) 진입 전, 멀티모듈 배선이 실제로 작동하는지 검증한다.

**전제**: `docs/MSA-TRANSITION-PLAN.md` T3-Gate 통과 상태. 전체 `./gradlew test` 516건 이상 PASS.

---

## 1. 토폴로지

| 컴포넌트 | 포트 (호스트) | 비고 |
|---|---|---|
| Gateway | 8090 (호스트) → 8080 (컨테이너) | 외부 진입점. Eureka `lb://` 라우팅 |
| payment-service | 8080 (컨테이너 내부) | docker 프로필 전용. 호스트 노출 없음(Gateway 경유) |
| pg-service | 8082 | Kafka consumer(`pg-service`, `pg-service-dlq` group) |
| product-service | 8083 | StockCommit/StockRestore consumer |
| user-service | 8084 | 조회 전용(Kafka 미소비) |
| Eureka | 8761 | 서비스 디스커버리 |
| Kafka | 29092 (호스트) / 9092 (컨테이너) | KRaft 단일 브로커 |
| Redis 공유 | 6379 | `idem:{key}`, `stock:{productId}` |
| Redis payment-dedicated | 6380 (호스트) → 6379 (컨테이너) | AOF on — 멱등·재고 캐시 |
| MySQL payment-platform | 3306 | 모놀리스 기존 DB |
| MySQL payment | 3307 | `payment` 전용(ADR-23, T1-18 이후 전환 대상) |
| MySQL pg | 3308 | `pg` 전용(ADR-23) |
| MySQL product | 3309 | `product` 전용(ADR-23) |
| MySQL user | 3310 | `user` 전용(ADR-23) |
| Toxiproxy | 8474 | Phase 4 용(본 스모크에선 미사용) |

---

## 2. 기동 순서 (전체 docker-compose 방식)

### 2-1. 앱 bootJar 선행 빌드
```bash
./gradlew :payment-service:bootJar :pg-service:bootJar :product-service:bootJar :user-service:bootJar :gateway:bootJar :eureka-server:bootJar
```

### 2-2. 필수 환경변수 (.env 또는 export)
```bash
export TOSS_SECRET_KEY=test_sk_xxxxxxxxxxxxxxxxxxxxxxxx  # Toss 샌드박스 키 (payment-service)
# 선택: NICEPAY_CLIENT_KEY, NICEPAY_SECRET_KEY (pg-service NicePay 전략 사용 시)
```

### 2-3. 컨테이너 일괄 기동
```bash
# 원-커맨드 스크립트 (빌드 + 인프라 + 토픽 + 앱 + Eureka 확인)
bash scripts/compose-up.sh
```

스크립트는 다음을 수행:
1. Docker 데몬 확인
2. `./gradlew :*:bootJar` 6개 모듈 빌드 (`--skip-build`로 생략 가능)
3. 인프라(`docker/docker-compose.infra.yml`) 기동 + 9개 healthy 대기 (<120s)
4. `scripts/phase-gate/create-topics.sh` 호출로 Kafka 토픽 생성(멱등)
5. 앱(`docker/docker-compose.apps.yml`) 기동 + 5개 healthy 대기 (<180s)
6. Eureka 등록 확인 + 접속 URL 출력

수동 실행이 필요하면 동일 효과:
```bash
docker compose -f docker/docker-compose.infra.yml -f docker/docker-compose.apps.yml up -d --build
```

필요 시 관측 스택도 함께:
```bash
docker compose -f docker/docker-compose.observability.yml up -d
# Grafana: http://localhost:3000 (admin/admin)
# Prometheus: http://localhost:9090
```

### 2-4. Kafka 토픽 생성
```bash
bash docs/phase-gate/kafka-topic-config.sh  # 파티션 수 동일성·retry 토픽 부재 검증 포함
```

생성 대상:
- `payment.commands.confirm` (3 partitions)
- `payment.commands.confirm.dlq` (3 partitions)
- `payment.events.confirmed` (3 partitions)
- `payment.events.stock-committed`
- `stock.events.restore`
- `product.events.stock-snapshot`

### 2-5. 기동 확인
- Eureka 대시보드: http://localhost:8761 → payment-service / pg-service / product-service / user-service / gateway 5개 인스턴스 UP
- Gateway health: `curl http://localhost:8090/actuator/health`
- 각 서비스 health는 Eureka 대시보드로 확인(호스트 포트 노출 안 함)

> **T1-18 재발 방지**: payment-service 환경변수 `SPRING_PROFILES_ACTIVE=docker`·`PRODUCT_ADAPTER_TYPE=http`·`USER_ADAPTER_TYPE=http`는 `docker/docker-compose.apps.yml`에 하드코딩되어 있어 누락 위험 낮음. 다만 `TOSS_SECRET_KEY`는 shell env 의존 — 미설정 시 placeholder로 들어가 실제 Toss 호출은 403 실패.

### 2-6. 로컬 bootRun 대안 (디버깅 시)

특정 서비스만 IDE에서 디버깅하려면 해당 서비스 컨테이너만 중지 후 bootRun:
```bash
docker compose -f docker/docker-compose.apps.yml stop payment-service
EUREKA_CLIENT_ENABLED=true EUREKA_DEFAULT_ZONE=http://localhost:8761/eureka/ \
SPRING_PROFILES_ACTIVE=docker PRODUCT_ADAPTER_TYPE=http USER_ADAPTER_TYPE=http \
SPRING_DATA_REDIS_HOST=localhost SPRING_DATA_REDIS_PORT=6379 TOSS_SECRET_KEY=test_sk_... \
./gradlew :payment-service:bootRun
```

---

## 3. 정상 결제 플로우 (Success Happy Path)

### 3-1. 사전 데이터 삽입 (MySQL `payment-platform`)
```sql
-- product(예: 1, 1000원), stock(초기 재고 100)
INSERT INTO product (id, name, price, description, seller_id) VALUES (1, 'Smoke Product', 1000, 'smoke', 1);
INSERT INTO stock (product_id, quantity) VALUES (1, 100);
INSERT INTO user (id, email) VALUES (1, 'smoke@test.com');
```

> product-service / user-service 전용 DB(3307 / 3308은 pg 용)에도 같은 테이블 초기화가 필요할 수 있다. 현 단계에서는 결제 DB의 product가 조회 기준임(T3-07 이후 HTTP 경로 사용 시 product-service DB 사용).

### 3-2. checkout
```bash
curl -i -X POST http://localhost:8090/api/v1/payments/checkout \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: smoke-001' \
  -d '{
    "userId": 1,
    "gatewayType": "TOSS",
    "orderedProductList": [{ "productId": 1, "quantity": 1 }]
  }'
```

**기대**:
- HTTP 201 + `orderId`, `amount` 응답
- `payment_event` 테이블에 IN_PROGRESS row 생성
- Redis `idem:smoke-001` SETNX 성공
- Redis `stock:1` Lua atomic DECR 성공(100 → 99)

### 3-3. confirm
```bash
ORDER_ID='<위에서 받은 orderId>'
curl -i -X POST http://localhost:8090/api/v1/payments/confirm \
  -H 'Content-Type: application/json' \
  -d "{
    \"userId\": 1,
    \"orderId\": \"$ORDER_ID\",
    \"amount\": 1000,
    \"paymentKey\": \"test_payment_key\",
    \"gatewayType\": \"TOSS\"
  }"
```

**기대**:
- HTTP 202 ACCEPTED (비동기 — 최종 상태는 폴링)
- `payment_outbox`에 PENDING row INSERT(topic=`payment.commands.confirm`)
- Channel+Worker(AFTER_COMMIT → Kafka produce) 경로로 produce 완료 → `payment_outbox.processed_at` 갱신
- pg-service가 `payment.commands.confirm` consume → `pg_inbox` NONE→IN_PROGRESS CAS → Toss API 호출 → APPROVED/FAILED 전이 → `pg_outbox` INSERT → Kafka `payment.events.confirmed` 발행
- payment-service가 `payment.events.confirmed` consume → `PaymentConfirmResultUseCase` → APPROVED 분기 → `payment_event` DONE + StockCommit 발행
- product-service가 `payment.events.stock-committed` consume → RDB UPDATE + Redis SET "stock:1" TTL 24h

### 3-4. 상태 조회
```bash
curl http://localhost:8090/api/v1/payments/$ORDER_ID/status
```

**기대**: `status=DONE`. 3~5초 이내 도달(PG 호출 포함). 장시간 IN_PROGRESS 머물면 **경로 누락**(아래 4-1 체크).

---

## 4. 관찰 지점 (구간별 디버깅)

| 구간 | 확인 지점 | 기대 상태 |
|---|---|---|
| **A. payment checkout**  | `SELECT * FROM payment-platform.payment_event WHERE order_id = ?` | status=IN_PROGRESS |
| **B. payment outbox 적재** | `SELECT * FROM payment_outbox WHERE aggregate_id = ? ORDER BY id` | PENDING 1건(topic=payment.commands.confirm) |
| **C. outbox → Kafka produce** | 같은 row가 `processed_at IS NOT NULL` 로 갱신 | 1~2초 내 |
| **D. Kafka console consume** | `kafka-console-consumer --bootstrap-server localhost:29092 --topic payment.commands.confirm --from-beginning` | 1메시지 |
| **E. pg-service inbox** | `SELECT * FROM pg.pg_inbox WHERE order_id = ?` | NONE→IN_PROGRESS→APPROVED |
| **F. pg-service outbox** | `SELECT * FROM pg.pg_outbox WHERE aggregate_id = ? ORDER BY id` | APPROVED 이벤트 PENDING→PROCESSED |
| **G. events.confirmed**  | `kafka-console-consumer ... --topic payment.events.confirmed` | 1메시지 |
| **H. payment 최종 전이** | payment_event status | DONE |
| **I. Redis 재고 SET** | `redis-cli -p 6379 GET stock:1` (또는 6380) | 최신 재고 숫자 |
| **J. product-service RDB**  | product-service DB의 stock 테이블 | qty 감소 반영 |

로그 필드로 추적 가능(LogFmt): `order_id={orderId}` 키로 각 서비스 로그 grep.

---

## 5. 실패 경로 스모크

### 5-1. 재고 부족 (checkout 단계 실패)
- 3-1의 stock.quantity=0 으로 초기화 → checkout 호출
- **기대**: HTTP 4xx + `PaymentOrderedProductStockException`, Redis `stock:1` DECR이 0 미만 차단, payment_event 미생성

### 5-2. PG 호출 실패 (confirm 후 FAILED 전이)
- 잘못된 `paymentKey`("force-fail" 등 Toss 샌드박스가 에러 반환하는 값)로 confirm
- **기대**:
  - `pg_inbox`: NONE→IN_PROGRESS→재시도(available_at 지수 백오프 base=2s multiplier=3 attempts=4)→FAILED
  - Kafka `payment.events.confirmed`에 FAILED 이벤트 1건
  - payment-service가 consume → `payment_event` FAIL
  - **보상 발행**: `FailureCompensationService` → `payment_outbox`에 topic=`stock.events.restore` row INSERT(결정론적 UUID)
  - `StockRestoreConsumer`(product-service) → EventDedupeStore.existsValid → stock qty 복원 → recordIfAbsent

### 5-3. 보상 멱등성 검증
같은 orderId로 FAILED 전이가 두 번 발생했다고 가정 — UUID v3(orderId+productId 기반)가 같으므로 `event_dedupe.existsValid`에서 차단. 재고는 1회만 복원됨(불변식 14).

수동 재현:
```sql
-- product-service DB
SELECT * FROM product_event_dedupe WHERE event_uuid = '<UUID>';  -- 1 row
SELECT quantity FROM stock WHERE product_id = 1;                  -- 1회만 +qty
```

---

## 6. Gateway 라우팅 검증

| 경로 | 기대 |
|---|---|
| `GET  http://localhost:8090/api/v1/users/1` | user-service 200 |
| `GET  http://localhost:8090/api/v1/products/1` | product-service 200 (엔드포인트 구현 여부 확인. 현재 ProductController는 payment-service에만 존재 — T3-07은 경로만 뚫어놓은 상태) |
| `GET  http://localhost:8090/internal/pg/anything` | 403 (InternalOnlyGatewayFilter, T2d-03) |
| `POST http://localhost:8090/api/v1/payments/checkout` | payment-service 201 |

---

## 7. 알려진 이슈 / 주의

1. **monolith-fallback 라우트**: Gateway `application.yml`에 `/**` → `http://localhost:8081`(payment-service 로컬 포트) fallback이 있다. 이 fallback 때문에 잘못된 경로도 payment-service로 떨어질 수 있다. T3-07 신규 라우트와 충돌 시 Gateway 라우트 순서 확인.
2. **product-service 단독 엔드포인트 미구현**: T3-01에서 모듈/스키마/Kafka 배선만 완성. REST API(`/api/v1/products/{id}`) 는 본 스모크 시점에 없다 — T3-07 라우트는 "뚫려있으나 대상 없음". 검증 대상은 `StockCommitConsumer` / `StockRestoreConsumer` / `StockSnapshotPublisher` 3종.
3. **PRODUCT/USER_ADAPTER_TYPE**: 기본 `internal`(InternalProductAdapter/InternalUserAdapter), `http`로 바꿔야 T3-06 HTTP 경로가 탄다. 단 user-service 조회 엔드포인트는 있지만 product-service는 위 2번 이슈로 실패할 수 있음.
4. **Eureka 등록 지연**: bootRun 직후 30초 정도 Eureka 등록이 안 되어 `lb://` 라우팅이 503을 낼 수 있다. Eureka 대시보드에서 UP 확인 후 트래픽.
5. **T1-18 silent failure**: `SPRING_PROFILES_ACTIVE=docker` / `PAYMENT_MONOLITH_CONFIRM_ENABLED=true` 둘 다 누락 시 PG 호출이 0건이 된다. 기동 후 1건 checkout+confirm 해보고 `payment_outbox.processed_at`이 갱신되는지 30초 내에 확인하는 것을 권장.
6. **모놀리스 경로 병행**: Phase 2 cutover로 payment-service는 Kafka publish만 수행. PG 직접 호출 코드는 T2c-02에서 삭제됨. 혹시 잔재로 돈 경로 이상 발견 시 `PgStatusAbsenceContractTest`(불변식 19) 재확인.

---

## 8. 종료 / 정리

```bash
# 앱 컨테이너 종료 (OutboxImmediateWorker SmartLifecycle drain 관찰 — graceful shutdown)
docker compose -f docker/docker-compose.apps.yml stop

# 전체 종료 (볼륨 유지)
docker compose -f docker/docker-compose.infra.yml -f docker/docker-compose.apps.yml down

# 볼륨까지 제거(초기화)
docker compose -f docker/docker-compose.infra.yml -f docker/docker-compose.apps.yml down -v
```

---

## 9. 결과 기록

스모크 통과/실패 결과는 여기 또는 `docs/phase-gate/phase-3-smoke-<날짜>.md`에 다음 포맷으로 기록:

```
- 일시: YYYY-MM-DD HH:MM
- 경로(성공/실패): [checkout, confirm, status, saga 보상, gateway 라우팅]
- 관찰 포인트 A~J: PASS/FAIL
- 발견된 이상: (있으면)
- 다음 액션: T4-01 진입 / 재수정 필요
```

Phase 4(T4-01 Toxiproxy) 진입은 본 가이드 3·5·6절이 모두 PASS여야 의미 있다. 실패 발생 시 해당 Phase 태스크로 되돌아가 root-cause를 먼저 수정.
