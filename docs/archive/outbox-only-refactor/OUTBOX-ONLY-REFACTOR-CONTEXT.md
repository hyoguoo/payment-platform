# Outbox 전용 아키텍처 정리 및 현대화

> 최종 수정: 2026-04-04

---

## 문제 정의

벤치마크를 통해 Outbox 전략이 유일한 운영 전략으로 확정되었다.
그러나 코드베이스에는 Sync 전략과 Outbox 전략이 공존하며, 이에 따른 불필요한 복잡도가 남아 있다.

- `PaymentConfirmServiceImpl` (Sync 전략)과 `OutboxAsyncConfirmService`가 `@ConditionalOnProperty`로 분기
- Sync 전용 도메인 개념인 `PaymentProcess`가 모든 공유 로직(`PaymentTransactionCoordinator`)에 남아 있음
- HTTP 클라이언트로 `RestTemplate`(블로킹 + 매 요청마다 인스턴스 생성)을 사용 중
- 채널 오버플로우 시 사용자에게 아무런 시그널이 없이 항상 202를 반환
- Tomcat 스레드 수 / 블로킹 큐 현재 크기가 Grafana에서 관측 불가

---

## 영향 범위

### 작업 1: Sync 전략 제거 + PaymentProcess 제거

**삭제:**
- `PaymentConfirmServiceImpl` — Sync 전략 구현체
- `PaymentProcess` 도메인 클래스 및 관련 인프라 전체
  - `domain/PaymentProcess.java`
  - `application/port/PaymentProcessRepository.java`
  - `application/usecase/PaymentProcessUseCase.java`
  - `infrastructure/repository/PaymentProcessRepositoryImpl.java`
  - `infrastructure/repository/JpaPaymentProcessRepository.java`
  - `infrastructure/entity/PaymentProcessEntity.java`
- `PaymentTransactionCoordinator`의 Sync 전용 메서드
  - `executeStockDecreaseWithJobCreation()`
  - `executePaymentSuccessCompletion()` 내 `existsByOrderId` → `completeJob` 분기
  - `executePaymentFailureCompensation()` 내 `existsByOrderId` → `failJob` 분기
- `PaymentFailureUseCase.handleRetryableFailure()` — Sync의 UNKNOWN 상태 처리
- `PaymentEventStatus.UNKNOWN` 관련 코드 (Sync에서만 발생)

**변경:**
- `OutboxAsyncConfirmService` — `@ConditionalOnProperty` 제거 (무조건 활성)
- `application.yml` — `spring.payment.async-strategy` 프로퍼티 제거
- `application-benchmark.yml` — 동일
- `ARCHITECTURE.md`, `CONFIRM-FLOW-ANALYSIS.md`, `STACK.md` 등 관련 문서 갱신

**무관:**
- `OutboxProcessingService`, `OutboxImmediateWorker`, `OutboxWorker`
- `PaymentOutbox` 도메인 및 관련 인프라

---

### 작업 2: RestTemplate → WebClient

**변경:**
- `HttpOperatorImpl` — `RestTemplate` → `WebClient` + `.block()`
  - `requestGet()`, `requestPost()` 내 RestTemplate 제거
  - 매 요청마다 인스턴스를 생성하는 현재 방식 → 싱글톤 `WebClient` Bean으로 교체
- `build.gradle` — `spring-boot-starter-webflux` 의존성 추가

**무관:**
- `HttpOperator` 인터페이스 — 반환 타입 유지 (동기 `<T>`)
- 상위 레이어(`TossPaymentGatewayStrategy` 등) — 변경 없음

> **설계 결정:** `HttpOperator` 인터페이스는 동기 반환 타입을 유지하고 `WebClient.block()`으로 구현한다.
> 이유: VT(가상 스레드) 환경에서 block()은 캐리어 스레드를 점유하지 않으므로 성능 문제가 없다.
> 인터페이스까지 리액티브로 변경하면 영향 범위가 과도하게 커진다.

---

### 작업 3: offer 실패 시 사용자 시그널

**현재 흐름 문제:**
- `OutboxImmediateEventHandler`에서 offer 결과를 처리하지만, 이는 AFTER_COMMIT 이후라 HTTP 응답에 포함 불가
- 사용자는 채널이 가득 차더라도 항상 동일한 202 Accepted를 받음

**설계 방향:**
- `OutboxAsyncConfirmService.confirm()` 시점에 `PaymentConfirmChannel.remainingCapacity()`를 확인
- 잔여 용량이 임계값(예: 전체 용량의 10%) 이하이면 응답에 힌트 포함
- `PaymentConfirmAsyncResult`에 `queueNearFull: boolean` 필드 추가
- `PaymentController`에서 `queueNearFull=true`이면 응답 body에 안내 메시지 추가 또는 `Warning` 헤더 삽입
- 단순하게: 같은 202이지만 body에 `message: "처리가 지연될 수 있습니다"` 포함

**변경:**
- `PaymentConfirmChannel` — `remainingCapacity()` 메서드 추가
- `PaymentConfirmAsyncResult` — `queueNearFull: boolean` 필드 추가
- `OutboxAsyncConfirmService` — confirm() 내 채널 상태 확인 로직 추가
- `PaymentController` — 응답 처리 분기

---

### 작업 4: Grafana 관측 지표 추가

**추가할 지표:**
1. **Tomcat 스레드 현황** — Micrometer Actuator가 이미 `tomcat.threads.current`, `tomcat.threads.busy`, `tomcat.threads.config.max`를 노출 → Grafana 대시보드 패널만 추가
2. **PaymentConfirmChannel 큐 사이즈** — `PaymentConfirmChannel`에 Micrometer `Gauge` 등록 필요
   - `payment_confirm_channel_queue_size` (현재 크기)
   - `payment_confirm_channel_remaining_capacity` (잔여 용량)

**변경:**
- `PaymentConfirmChannel` — `MeterRegistry` 주입 후 `Gauge` 등록
- `docker/compose/grafana/dashboards/1-system-infrastructure.json` — Tomcat 스레드 패널 추가
- `docker/compose/grafana/dashboards/3-payment-operations.json` — 채널 큐 사이즈 패널 추가

---

### 작업 5: 코드 클렌징

- Sync 제거 후 남은 dead code, 불필요한 주석, 복잡도 검토
- `superpowers:code-reviewer` 스킬 활용
- 작업 1~4 완료 후 진행

---

## 결정 사항

| 항목 | 결정 | 이유 |
|------|------|------|
| Sync 전략 제거 방식 | `PaymentConfirmServiceImpl` + `PaymentProcess` 전체 삭제 | 벤치마크 완료, Outbox만 운영 |
| `OutboxAsyncConfirmService` 어노테이션 | `@ConditionalOnProperty` 제거, 항상 활성 | 단일 전략 유지 |
| `UNKNOWN` 상태 제거 | 제거 | Sync에서만 발생하는 상태 |
| WebClient 반환 타입 | `HttpOperator` 인터페이스 동기 유지, 내부 `.block()` | VT 환경에서 허용, 영향 범위 최소화 |
| offer 실패 시그널 전달 방식 | confirm() 시점에 채널 사전 확인 (사후 AFTER_COMMIT 아님) | HTTP 응답에 포함 가능한 유일한 시점 |
| 큐 상태 임계값 | capacity의 10% 이하 잔여 시 `queueNearFull=true` | 오버플로우 직전 여유 확보 |
| 코드 클렌징 순서 | 작업 1~4 완료 후 마지막 | Sync 제거로 변경될 코드를 먼저 정리 |

---

## 제외 범위

- **Kafka 전략**: 이번 작업에서 추가하지 않음 — 별도 작업
- **`PaymentEventStatus.UNKNOWN` DB 마이그레이션**: 기존 레코드 처리 포함하지 않음 — 운영 데이터 없는 개발/벤치마크 환경 기준
- **`HttpOperator` 인터페이스 리액티브 전환**: 영향 범위 과대 — 이번 작업 제외
- **Tomcat → Undertow / 가상 스레드 WAS 변경**: 관측만 추가, WAS 교체는 별도 검토 필요

---

## 참고

- `docs/context/ARCHITECTURE.md` — PaymentConfirmServiceImpl / OutboxAsyncConfirmService 전략 구조
- `docs/context/CONFIRM-FLOW-ANALYSIS.md` — Sync / Outbox 플로우 상세
- `docs/context/STACK.md` — 현재 기술 스택 및 설정
- `src/main/java/.../core/channel/PaymentConfirmChannel.java` — 큐 구현체 (LinkedBlockingQueue 래퍼)
- `src/main/java/.../core/common/infrastructure/http/HttpOperatorImpl.java` — RestTemplate 현재 구현
- `docker/compose/grafana/dashboards/` — 기존 대시보드 JSON
