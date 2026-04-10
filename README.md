# Payments Platform

결제 연동을 환경에서 발생하는 문제들(위변조 방지, 멱등성 보장, 비동기 결제 처리, 자동 복구 등)을 직접 설계하고 구현한 프로젝트입니다.

<br>

## 🚀 주요 해결 과제

- **동기 → 비동기 아키텍처 전환 및 성능 측정**: Toss API 지연이 HTTP 스레드를 직접 블로킹하는 동기 구조에서 비동기 + Outbox 채널 전략으로 전환
- **정합성 오류 및 위변조 요청 방지**: 클라이언트·서버·PG 응답값을 교차 검증하고 Checkout 멱등성(Caffeine 캐시 + TOCTOU 해결)을 보장하여 중복 주문 및 금액 위변조를 차단
- **장애 내성 복구 체계 설계**: `RecoveryDecision` 값 객체로 복구 결정을 집중하고, 스케줄링·재고 복구 가드·격리 전 최종 확인으로 외부 장애 시에도 재고·결제 상태의 일관성 유지

<br>

## 🗺️ 개발 과정

|  Phase  | 목표                 | 구현 내용                                                                                                                                                                                                                                     |
|:-------:|:-------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Phase 1 | 데이터 정합성 확립         | [교차 검증 연동](https://github.com/hyoguoo/payment-platform/wiki/cross-validation)                                                                                                                                                             |
| Phase 2 | 결합도 해소 및 자가 복구력    | [트랜잭션 범위 최소화](https://github.com/hyoguoo/payment-platform/wiki/tx-scope) · [상태 기반 복구 모델 및 재시도 로직](https://github.com/hyoguoo/payment-platform/wiki/retry-recovery)                                                                        |
| Phase 3 | 운영 가시성 및 안정성       | [시나리오 테스트](https://github.com/hyoguoo/payment-platform/wiki/scenario-test) · [구조화된 로깅](https://github.com/hyoguoo/payment-platform/wiki/structured-logging) · [결제 이력 추적 및 모니터링](https://github.com/hyoguoo/payment-platform/wiki/metrics) |
| Phase 4 | 데이터 정합성 심화 및 중복 제어 | [보상 TX 실패 대응](https://github.com/hyoguoo/payment-platform/wiki/compensation-tx) · [Checkout 멱등성 보장](https://github.com/hyoguoo/payment-platform/wiki/idempotency)                                                                         |
| Phase 5 | 비동기 결제 아키텍처 전환     | [비동기 가상 스레드 기반 결제 플로우](https://github.com/hyoguoo/payment-platform/wiki/async-outbox) · [도메인 상태 머신과 장애 내성 복구 체계](https://github.com/hyoguoo/payment-platform/wiki/state-management)                                                       |
|   ETC   | 설계 유연성             | [전략 패턴 기반 PG 독립성 확보](https://github.com/hyoguoo/payment-platform/wiki/pg-strategy)                                                                                                                                                        |

<br>

## 🔑 핵심 구현 및 주요 기능

> 각 항목 제목을 클릭하면 상세 설계 내용이 담긴 Wiki 페이지로 이동합니다.

### [비동기 결제 확인 플로우 — Outbox 채널 기반 비동기 아키텍처 전환 및 벤치마크](https://github.com/hyoguoo/payment-platform/wiki/async-outbox)

- 동기(Sync) 전략에서 Toss API 지연이 HTTP 스레드를 직접 블로킹해 고부하 시 TPS 급락·스레드 고갈 문제가 발생
- `LinkedBlockingQueue` + VT 워커 구조(채널 기반)로 PG 요청을 비동기로 처리하여 네트워크 지연 병목 해결
- 포스팅: [비동기 결제 처리 플로우 구현 — Outbox 패턴부터 LinkedBlockingQueue Worker까지](https://hyoguoo.github.io/blog/async-payment-flow)

```mermaid
flowchart TD
%% 클래스 정의 (가독성 및 역할 구분)
    classDef client fill: #FFFFFF, stroke: #333, color: #000
    classDef process fill: #E1F5FF, stroke: #0078D4, color: #000
    classDef tx fill: #FFF2CC, stroke: #D79B00, color: #000
    classDef worker fill: #E8F5E9, stroke: #2E7D32, color: #000
    classDef fallback fill: #FADAD8, stroke: #B85450, color: #000
    classDef response fill: #F5F5F5, stroke: #333, color: #000
    Client(["클라이언트"]):::client

    subgraph Sync["Sync 전략 (spring.payment.async-strategy=sync)"]
        S1["confirm() 호출"]:::process
        S2["TX: 재고 감소 + PaymentProcess 생성"]:::tx
        S3["TX: READY → IN_PROGRESS"]:::tx
        S4["Toss API 동기 호출\n⏳ 100ms ~ 3,500ms 블로킹"]:::process
        S5["TX: DONE 처리"]:::tx
        S6(["200 OK"]):::response
        S1 --> S2 --> S3 --> S4 --> S5 --> S6
    end

    subgraph Outbox["Async 전략 (outbox, 기본값)"]
        O1["confirm() 호출"]:::process
        O2["단일 TX: IN_PROGRESS + 재고 감소\n+ PaymentOutbox(PENDING)"]:::tx
        O3(["202 Accepted\n← HTTP 스레드 즉시 해방"]):::response
        O4["AFTER_COMMIT 이벤트 발행"]:::process
        O5["channel.offer(orderId)\n비블로킹"]:::process

        subgraph Workers["OutboxImmediateWorker (고성능/실시간)"]
            W1["channel.take()\nblocking wait"]:::worker
            W2["claimToInFlight\nREQUIRES_NEW TX"]:::tx
            W3["Toss API 호출\n(HTTP 스레드와 분리)"]:::worker
            W4["TX: PaymentEvent DONE\nPaymentOutbox DONE"]:::tx
        end

        OFB["OutboxWorker\n폴링 폴백\n큐 오버플로우 / 서버 재시작 복구"]:::fallback
        O1 --> O2 --> O3
        O2 --> O4 --> O5
        O5 -->|" offer() true "| W1
        O5 -->|" offer() false\n큐 가득 참 "| OFB
        W1 --> W2 --> W3 --> W4
    end

    Client --> S1
    Client --> O1
%% 스타일 보정 (연결선 가독성)
    linkStyle 7,12,13 stroke: #333, stroke-width: 2px, color: #000
```

#### k6 부하 테스트 결과 (Round 9 — 최종):

|     네트워크 지연 환경     |    전략     |       TPS       | Confirm 응답 (med) | E2E Latency (med) |     요청 유실     |
|:------------------:|:---------:|:---------------:|:----------------:|:-----------------:|:-------------:|
| **고지연** (2.0~3.5s) |   Sync    |      54.1       |     6,157ms      |      3,190ms      |     1,945     |
| **고지연** (2.0~3.5s) | **Async** | **79.8 (+47%)** |    **5.3ms**     |    **2,820ms**    | **0 (-100%)** |
| **저지연** (0.1~0.3s) | **Sync**  |      106.4      |      210ms       |       211ms       |       0       |
| **저지연** (0.1~0.3s) |   Async   |      93.5       |      6.3ms       |       305ms       |       0       |

- 고지연 환경에서 Outbox 전략이 TPS 47% 상승, 요청 유실 100% 감소 기록
- **이상적 자원 할당(Sweet Spot)**: 무작정 커넥션 풀을 늘리기보다 시스템 한계에 맞는 최적의 수치(HikariCP 30 등)를 도출하여 안정성과 성능의 균형 확보
- 상세 보고서: [Benchmark-Report](https://github.com/hyoguoo/payment-platform/wiki/Benchmark-Report)

### [결제 상태 관리 — 도메인 상태 머신과 장애 내성 복구 체계](https://github.com/hyoguoo/payment-platform/wiki/state-management)

- PG 상태 조회 후 `RecoveryDecision` 객체가 종결/재시도/격리 결정
- 결제 재시도 한도 소진 시 격리 전 최종 확인(getStatus 1회 재호출)으로 성공 건의 오격리 방지, `QUARANTINED` 상태로 관리자 개입 유도
- 보상 트랜잭션 실행 전 이중 조건 가드(Outbox IN_FLIGHT + Event 비종결)로 동시성 경합 시 재고 이중 복원 차단
- 포스팅: [결제 복구 상태 전이 설계](https://hyoguoo.github.io/blog/payment-recovery-state-design)

```mermaid
flowchart TD
    classDef success fill: #D5F5E3, color: #0E6251, stroke: #28B463
    classDef retryable fill: #FEF5E7, color: #7E5109, stroke: #F39C12
    classDef failure fill: #FADBD8, color: #7B241C, stroke: #E74C3C
    classDef action fill: #EBF5FB, color: #21618C, stroke: #3498DB
    classDef quarantine fill: #F3E5F5, color: #4A148C, stroke: #7B1FA2
    classDef check fill: #FEF9E7, color: #7D6608, stroke: #F1C40F
    classDef skip fill: #F5F5F5, color: #616161, stroke: #9E9E9E
    CL["claimToInFlight\n원자 선점"]:::action
    CL -->|" 선점 성공 "| GS["PG 상태 선행 조회\ngetStatusByOrderId"]:::action
    CL -->|" 선점 실패 "| SKIP["다른 Worker 처리 중\n→ 포기"]:::skip
    GS -->|" DONE "| SUCCESS["COMPLETE_SUCCESS"]:::success
    GS -->|" PG 종결 실패 "| FAILURE["COMPLETE_FAILURE"]:::failure
    GS -->|" PG 기록 없음 "| CONFIRM["ATTEMPT_CONFIRM\nPG 승인 요청"]:::action
    GS -->|" 일시 오류 + 한도 미소진 "| RETRY["RETRY_LATER\n백오프 대기"]:::retryable
    GS -->|" 한도 소진 "| FINAL["격리 전 최종 확인\ngetStatus 1회 재호출"]:::action
    FINAL -->|" DONE "| SUCCESS
    FINAL -->|" PG 종결 실패 "| FAILURE
    FINAL -->|" 판단 불가 "| QU["QUARANTINE\n관리자 개입 대기"]:::quarantine
    FAILURE --> GUARD{"재고 복구 가드\nOutbox IN_FLIGHT?\nEvent 비종결?"}:::check
    GUARD -->|" 조건 충족 "| COMP["재고 복원 + FAILED"]:::failure
    GUARD -->|" 조건 미충족 "| GSKIP["재고 복원 skip"]:::skip
```

### [Checkout API 멱등성 보장 — TOCTOU 경쟁 조건 해결](https://github.com/hyoguoo/payment-platform/wiki/idempotency)

- UI 중복 클릭, 네트워크 재시도 등으로 `PaymentEvent`가 복수 생성되어 DB에 유효하지 않은 주문이 누적되는 문제 존재
- 초기 `getIfPresent + put` 구현에서 코드 리뷰 중 TOCTOU 경쟁 조건 발견, `getOrCreate` 단일 원자적 메서드로 포트 계약 재설계
- 포스팅: [Checkout API 멱등성 보장 — Caffeine 캐시와 TOCTOU 경쟁 조건 해결](https://hyoguoo.github.io/blog/checkout-idempotency)

```mermaid
sequenceDiagram
    participant A as Thread A
    participant B as Thread B
    participant Cache as Caffeine Cache
    A ->> Cache: get("key", loader)
    Cache -->> A: (lock acquired, loader 실행 중)
    A ->> A: create() → PaymentEvent#1
    B ->> Cache: get("key", loader)
    Cache -->> B: (동일 키 → lock wait)
    A ->> Cache: 결과 저장 후 lock 해제
    Cache -->> B: hit → PaymentEvent#1 반환 (loader 미실행)
    Note over Cache: ✅ 중복 생성 없음
```

### [전략 패턴을 통한 PG 독립성 확보 및 확장 가능한 구조](https://github.com/hyoguoo/payment-platform/wiki/pg-strategy)

- Application 계층은 특정 PG 구현체에 의존하지 않아 PG 독립성을 확보
- 전략 패턴을 통해 PG사 구현체를 추상화하여 새로운 PG 추가 시 최소한의 변경으로 확장 가능
- 포스팅: [전략 패턴을 통한 결제 게이트웨이 추상화 및 확장성 확보](https://hyoguoo.github.io/blog/payment-gateway-strategy-pattern)

```mermaid
graph TB
    subgraph "Application Layer"
        Service[PaymentConfirmServiceImpl]
        UseCase[PaymentProcessorUseCase]
        Port[PaymentGatewayPort<br/>Interface]
    end

    subgraph "Infrastructure Layer"
        Adapter[PaymentGatewayAdapter<br/>Port 구현체]
        Factory[PaymentGatewayFactory<br/>전략 선택]
        Strategy[PaymentGatewayStrategy<br/>Interface]

        subgraph "Strategy Implementations"
            Toss[TossPaymentGatewayStrategy]
            Future[Other PG Strategy<br/>... 확장 가능]
        end
    end

    subgraph "External Systems"
        TossAPI[Toss Payments API]
    end

    Service -->|사용| UseCase
    UseCase -->|의존| Port
    Port -.->|구현| Adapter
    Adapter -->|위임| Factory
    Factory -->|선택| Strategy
    Strategy -.->|구현| Toss
    Strategy -.->|확장 가능| Future
    Toss -->|호출| TossAPI
    style Port fill: #e1f5ff, color: #000
    style Strategy fill: #e1f5ff, color: #000
    style Adapter fill: #fff4e1, color: #000
    style Factory fill: #fff4e1, color: #000
    style Toss fill: #e8f5e9, color: #000
    style Future fill: #f5f5f5, stroke-dasharray: 5 5, color: #000
```

### [결제 흐름 추적 및 핵심 지표 모니터링 시스템 구현](https://github.com/hyoguoo/payment-platform/wiki/metrics)

- 승인 지연, 재시도 등 복잡한 결제 흐름 추적의 어려움 및 실시간 성능/이상 징후를 파악할 핵심 지표 부재
- 구조화된 로깅 적용 / 결제 정보 변동 저장 및 어드민 페이지 구현 / 커스텀 메트릭 수집을 통한 핵심 지표 모니터링 체계 구축

<img width="80%" alt="image" src="https://github.com/user-attachments/assets/92aee152-fa7a-4570-b1d7-ad3191e9a121">
<img width="80%" alt="image" src="https://github.com/user-attachments/assets/0bf123ea-0b32-4a89-8368-34734e40c8b6">

### [결제 데이터 검증을 통한 데이터 정합성 보장](https://github.com/hyoguoo/payment-platform/wiki/cross-validation)

- 클라이언트가 주문 생성부터 승인까지 처리하는 방식으로, 중간 값 조작 같은 위변조 가능성 존재
- 서버 주도의 흐름으로 전환하고, 클라이언트·서버·PG 응답값을 교차 검증하여 불일치 시 결제를 거부하도록 설계

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant S as Server
    participant T as PG
    Note over C, T: 결제 시퀀스 흐름
    C ->> S: 주문 번호 생성 요청
    S ->> S: 구매 요청 검증 및 DB 저장
    S -->> C: 주문 번호 반환
    C ->> T: 결제 요청
    T ->> T: 카드사 결제 인증
    T -->> C: 성공 리다이렉트
    C ->> S: 결제 승인 요청
    S ->> S: 결제 / 주문 정보 양방향 검증
    S ->> T: 결제 승인
    T -->> S: 승인 성공 반환
    S ->> S: DB 업데이트
    S -->> C: 성공 내역 반환
    C ->> C: 결제완료
```

### [트랜잭션 범위 최소화를 통한 성능 및 응답 시간 최적화](https://github.com/hyoguoo/payment-platform/wiki/tx-scope)

- 외부 API 호출이 포함된 단일 트랜잭션 구조로 인해 커넥션 점유와 응답 지연 문제가 발생
- 외부 호출을 트랜잭션 외부로 분리하고 보상 트랜잭션을 적용해 안정성과 성능을 함께 확보
- 포스팅: [트랜잭션 범위 최소화를 통한 성능 및 안정성 향상](https://hyoguoo.github.io/blog/minimize-transaction-scope)

<img width="80%" alt="image" src="https://github.com/user-attachments/assets/ff19dac9-a717-4b5d-96e9-de60d199e10a">

### [외부 의존성을 제어한 테스트 환경에서의 시나리오 검증](https://github.com/hyoguoo/payment-platform/wiki/scenario-test)

- 외부 API에 의존하는 구조로 인해 다양한 예외 상황에 대한 테스트가 어려움 존재
- Fake 객체 기반의 테스트 환경을 구성하여 승인 실패, 지연, 중복 요청 등 다양한 시나리오를 유연하게 검증
- 포스팅: [외부 의존성 제어를 통한 결제 프로세스 다양한 시나리오 검증](https://hyoguoo.github.io/blog/payment-system-test)

<img width="80%" alt="image" src="https://github.com/user-attachments/assets/3bb72ac9-b8ae-4629-b799-6546a7ee9640">

<br>

### 🛠 사용 기술 스택

- Java 21
- Spring Boot 3.4.4
- MySQL 8.0.33
- JUnit 5
- k6 (부하 테스트)

<br>

## 🏗 [프로젝트 구조](https://github.com/hyoguoo/payment-platform/wiki/architecture)

헥사고날 아키텍처(포트/어댑터) 기반으로 도메인을 분리하고, 도메인 간 협력은 Internal Receiver 패턴을 통해 결합도를 낮췄습니다.

<img width="100%" alt="architecture" src="https://github.com/user-attachments/assets/26cb69e5-6c89-479e-8181-4dd6a13c5eb5">

<br>

## ▶️ Quick Start

### 서비스 구성

|  포트  |      서비스      |    설명     |
|:----:|:-------------:|:---------:|
| 8080 |  Spring App   | 애플리케이션 서버 |
| 3306 |     MySQL     |  데이터베이스   |
| 9200 | Elasticsearch |  로그 저장소   |
| 5050 |   Logstash    |   로그 수집   |
| 5601 |    Kibana     |  로그 시각화   |
| 9090 |  Prometheus   |  메트릭 수집   |
| 3000 |    Grafana    |  메트릭 시각화  |

#### 시크릿 설정

```bash
cp .env.secret.example .env.secret # 루트 디렉토리
cd docker/compose
cp .env.secret.example .env.secret # docker/compose 디렉토리
# TOSS_SECRET_KEY 입력
```

### 실행 방법

#### 애플리케이션 실행

```bash
./scripts/run.sh
```

실행 후 http://localhost:8080 에서 전체 페이지를 탐색할 수 있습니다.

| URL                                          | 설명                                     |
|:---------------------------------------------|:---------------------------------------|
| http://localhost:8080                        | 홈 — 결제 흐름 · 어드민 · 모니터링 링크 모음           |
| http://localhost:8080/payment/checkout.html  | 결제하기 — 토스페이먼츠 결제창 호출                   |
| http://localhost:8080/admin/payments/events  | 결제 이벤트 목록 조회 / 검색                      |
| http://localhost:8080/admin/payments/history | 결제 히스토리 — 상태 변경 이력 조회                  |
| http://localhost:5601                        | Kibana — 로그 시각화                        |
| http://localhost:3000                        | Grafana — 메트릭 대시보드 (admin / admin123!) |
