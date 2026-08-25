# 공유 자원 동반 스케일아웃 측정 구현 플랜

> 작성일: 2026-08-25

## 요약 브리핑

### Task 목록

| # | 태스크 | 한 줄 |
|:---:|:---|:---|
| 1 | 폴링 상태 조회 포트와 Fake | 폴링이 쓸 조회 두 개를 전용 포트로 선언하고 테스트용 대역을 만든다 |
| 2 | 폴링이 전용 포트를 쓰도록 교체 | 상태 조회 서비스가 판정 경로와 같은 조회를 더 이상 쓰지 않게 한다 |
| 3 | 복제본 데이터소스 설정 | 복제본 연결을 설정값 하나로 켜고 끈다. 끄면 지금과 똑같이 동작한다 |
| 4 | 폴링 조회 어댑터 | 복제본을 질의로만 읽는다. 영속성 컨텍스트를 태우지 않는다 |
| 5 | 복제본 격리 계약 테스트 | 복제본을 주입받는 빈이 폴링 어댑터 하나뿐임을 못박는다 |
| 6 | 캐시 연결의 클러스터 전환 | 재고 캐시와 멱등 저장소를 노드 목록만으로 클러스터에 붙인다 |
| 7 | payment DB 복제본 인프라 | 비동기 복제본 1대를 띄우고 복제를 시작한다 |
| 8 | 캐시 클러스터 인프라 | 마스터 1 / 2 / 4 를 만들고 슬롯 커버리지 요구를 끈다 |
| 9 | 부하 프로필 상품 다중화 | 상품 100종을 심고 부하가 고르게 고르게 한다 |
| 10 | 사이클 재구성 절차 스크립트 | 다섯 단계를 밟아야만 캐시를 비운다 |
| 11 | 정합 검증 상품별 확장 | 상품마다 대조하고, 판정을 기계가 읽게 낸다 |
| 12 | 클러스터 라이브 점검 | 스크립트 다섯 경로와 키 슬롯 배치를 실측한다 |
| 13 | 사이클 러너와 복제 지연 계측 | 사이클 하나를 인자만 주고 끝까지 돌린다 |
| 14 | 재고 캐시 대수 축 측정 | 마스터 1 / 2 / 4, 나머지 고정 |
| 15 | 인스턴스 수 축 측정 | 1 / 2 / 3 / 4, 합격선은 이 축의 1 에서 2 구간뿐 |
| 16 | 나머지 세 축 측정 | 읽기 복제 껐다 켬, 주문당 상품 3개, 고지연 벤더 |
| 17 | 측정 리포트 | 처리량 체감지연 정합성 세 축으로 결론을 낸다 |

### 변경 후 전체 플로우차트

```mermaid
flowchart TB
    subgraph Code["코드 - Task 1 to 6"]
        Port["폴링 전용 조회 포트<br/>application port out"]
        Adp["질의 어댑터<br/>infrastructure"]
        Iso["복제본 격리 계약 테스트"]
        Cl["캐시 연결 클러스터 전환"]
        Port --> Adp --> Iso
    end

    subgraph Infra["인프라 - Task 7 to 9"]
        Rep["payment DB 복제본"]
        RC["재고 캐시 클러스터<br/>마스터 1 / 2 / 4"]
        IC["멱등 저장소 클러스터<br/>대수 고정"]
        Seed["상품 100종 시드와 부하"]
    end

    subgraph Tool["사이클 도구 - Task 10 to 13"]
        Reset["재구성 다섯 단계"]
        Ver["상품별 정합 검증"]
        Chk["클러스터 라이브 점검"]
        Run["사이클 러너"]
        Chk --> Run
        Run --> Ver
        Ver -->|"통과"| Reset
        Ver -->|"판단 보류 - 유한 재시도"| Ver
        Ver -->|"불일치 또는 보류 소진"| Stop["중단 - 캐시를 비우지 않는다"]
    end

    subgraph Meas["측정 - Task 14 to 17"]
        A1["재고 대수 축 3"]
        A2["인스턴스 축 3"]
        A3["나머지 세 축 3"]
        Rpt["리포트"]
        A1 --> A2 --> A3 --> Rpt
    end

    Adp -.->|"폴링만"| Rep
    Cl -.-> RC
    Cl -.-> IC
    Seed -.-> Ver
    Reset --> A1
```

### 핵심 결정과 Task 매핑

| 설계 결정 | Task |
|:---|:---|
| 복제본 읽기 대상을 폴링 전용 포트로 한정 | 1, 2, 4, 5 |
| 복제본은 질의로만 읽는다 | 4 |
| 재고 캐시는 클러스터, 마스터 1 / 2 / 4 | 6, 8, 14 |
| 슬롯 커버리지 요구를 끈다 | 8 |
| 멱등 저장소는 클러스터 전환, 대수 고정 | 6, 8 |
| 사이클 사이 재구성 다섯 단계 | 10 |
| 부하 프로필 상품 100종 | 9, 16 |
| 인스턴스 1 / 2 / 3 / 4, 합격선 1 에서 2 구간 1.6배 | 15, 17 |
| 지연은 백분위 셋 + 복제 지연 병행 | 13, 17 |
| 캐시 내구성은 그대로 재되 효과가 없으면 확인 사이클 | 14 |
| 관측 스택 유지, 첫 사이클 전 기동과 트레이스 점검 | 13, 14 |
| 벤더 지연은 나머지를 고정한 별도 축 | 16 |
| 복제는 비동기 1대 | 7 |
| 포트는 application, 어댑터와 데이터소스 설정은 infrastructure | 1, 3, 4 |
| 메시지 브로커는 단일 유지, 천장으로 관측되면 기록만 | 17 |
| 결론은 처리량 체감지연 정합성 세 축 | 17 |

### 트레이드오프와 후속

- **코드 여섯 개 중 다섯이 폴링 경로 하나를 위한 것이다.** 돈 경로 판정 일곱과 같은 조회를 쓰던 것을 떼어내는 값이다. 떼어내지 않으면 복제 지연 중 격리 종결이 낡은 상태를 읽는다.
- **측정 태스크 넷은 벽시계 시간이 길다.** 사이클 아홉 개에 기동과 재구성이 붙는다. 코드 태스크의 두 시간 감각과 다르지만 직전 측정 토픽도 같은 결로 나눴다.
- **정합 판정을 종료 코드로 낸다.** 지금 검증 스크립트는 불일치가 나도 0 으로 끝나 사람이 눈으로 읽는 전제다. 무인 사이클에 그대로 쓰면 재구성이 그냥 진행돼 증거가 지워진다.
- **장애 전환 검증은 별도 토픽** — ship 에서 미해결 항목 대장에 등재한다.
- **선행 조건 하나가 남아 있다** — Docker 메모리 20GB. Task 14 가 측정 전에 확인한다.

---

## 목표

payment DB 읽기 복제본과 재고 캐시·멱등 저장소 클러스터를 붙인 뒤, 축별로 변수를 하나만 남긴 사이클 아홉 개를 돌려 처리량·체감 지연·정합성 세 축의 수치를 리포트로 남긴다.

## 컨텍스트

- 설계 문서: `docs/topics/SHARED-RESOURCE-SCALEOUT.md` (결정 사항 표가 분해의 원천)
- 이슈 / 브랜치: #146
- 주요 변경 파일
  - `payment-service/.../application/port/out/` — 폴링 전용 조회 포트 신설
  - `payment-service/.../application/PaymentStatusServiceImpl.java` — 폴링이 전용 포트를 쓰도록 교체
  - `payment-service/.../infrastructure/config/{RedisConfig,StockRedisConfig}.java` — 클러스터 연결
  - `payment-service/.../infrastructure/repository/` — 복제본 질의 어댑터
  - `docker/docker-compose.scaleout.yml`(신규) — 복제본 + 캐시 클러스터
  - `scripts/bench-seed-stock.sh`, `scripts/k6/{helpers.js,verify-settlement.sh}`, 신규 사이클 스크립트

### 선행 조건 (측정 시작 전)

- **Docker 메모리 20GB 상향** — 현재 8.2GB. 인스턴스 4대 구간에 복제본과 캐시 노드를 얹으면 컨테이너가 29개가 되고, 호스트가 스왑을 타면 측정이 오염된다. Task 14 가 시작 전에 확인한다.
- CPU 10 코어는 고정. 인스턴스 4대 구간에서 앱 CPU 가 먼저 천장이 될 수 있고, 그 경우 그 사실 자체가 결론이 된다.

## 진행 상황

- [x] Task 1: 폴링 상태 조회 포트와 Fake
- [x] Task 2: 폴링이 전용 포트를 쓰도록 교체
- [x] Task 3: 복제본 데이터소스 설정
- [x] Task 4: 폴링 조회 어댑터 (질의 전용)
- [x] Task 5: 복제본을 주입받는 빈이 폴링 어댑터 하나임을 고정
- [x] Task 6: 재고 캐시·멱등 저장소 연결의 클러스터 모드 전환
- [x] Task 7: payment DB 복제본 인프라
- [x] Task 8: 재고 캐시·멱등 저장소 클러스터 인프라
- [x] Task 9: 부하 프로필 상품 다중화
- [x] Task 10: 사이클 재구성 절차 스크립트
- [ ] Task 11: 정합 검증 상품별 확장
- [ ] Task 12: 클러스터 라이브 점검 스크립트
- [ ] Task 13: 사이클 러너와 복제 지연 계측
- [ ] Task 14: 재고 캐시 대수 축 측정 (마스터 1 / 2 / 4)
- [ ] Task 15: 인스턴스 수 축 측정 (1 / 2 / 3 / 4)
- [ ] Task 16: 읽기 복제·다중 상품·벤더 지연 축 측정
- [ ] Task 17: 측정 리포트

## 태스크

### Task 1: 폴링 상태 조회 포트와 Fake [tdd=false] [domain_risk=false]

설계 근거: "복제본 읽기 대상 — 폴링 전용 조회 포트를 신설하고 그 어댑터만 복제본에 묶는다", "신규 코드 배치 — 포트는 `payment/application/port/out`".

**구현 (GREEN)**
- `payment/application/port/out/PaymentStatusQueryPort.java`
  - `Optional<PaymentOutboxStatus> findActiveOutboxStatus(String orderId)` — 발행 대기(PENDING) 또는 발행 중(IN_FLIGHT)일 때만 값을 돌려준다
  - `Optional<PaymentStatusSnapshot> findStatusSnapshot(String orderId)`
- `payment/application/port/out/PaymentStatusSnapshot.java` — record(orderId, `PaymentEventStatus` status, `Instant` approvedAt). 같은 패키지의 `StockHoldRecordSnapshot` 과 같은 자리·같은 결
- `payment-service/src/test/.../payment/mock/FakePaymentStatusQueryPort.java` — ConcurrentHashMap 기반. 주문번호별로 발행 상태와 스냅샷을 심어 두고 그대로 돌려준다

**완료 기준**
- 두 파일과 Fake 가 존재하고 `./gradlew :payment-service:compileTestJava` 통과
- 포트가 도메인 타입(`PaymentOutboxStatus` / `PaymentEventStatus`)만 노출하고 JPA·JDBC 타입을 드러내지 않는다

**완료 결과**
- `PaymentStatusQueryPort` / `PaymentStatusSnapshot` / `FakePaymentStatusQueryPort` 3 파일 신설. `compileTestJava` 통과
- 아직 어떤 코드도 이 포트를 참조하지 않는다 — 교체는 Task 2

---

### Task 2: 폴링이 전용 포트를 쓰도록 교체 [tdd=true] [domain_risk=true]

설계 근거: "복제본 읽기 대상" 결정. 결제 이벤트 조회를 돈 경로 판정 일곱이 공유하므로, 폴링만 떼어내야 판정이 원본에 남는다.

**테스트 (RED)**
- `PaymentStatusServiceImplTest` (기존 파일 갱신) — 협력자를 `FakePaymentStatusQueryPort` 로 바꾼다
  - `getPaymentStatus_발행대기면_PENDING`
  - `getPaymentStatus_발행중이면_PROCESSING`
  - `getPaymentStatus_발행기록없고_이벤트가_DONE이면_DONE과_승인시각`
  - `getPaymentStatus_발행기록없고_이벤트가_FAILED면_FAILED`
  - `getPaymentStatus_발행기록없고_이벤트가_진행중이면_PROCESSING` (`@ParameterizedTest @EnumSource` 로 DONE/FAILED 를 뺀 나머지)
  - `getPaymentStatus_이벤트가_없으면_PAYMENT_EVENT_NOT_FOUND` — 교체 전과 같은 예외를 던진다
  - `getPaymentStatus_발행기록이_있으면_스냅샷을_읽지_않는다` — 발행 대기 상태일 때 스냅샷 조회 호출 수가 0

**구현 (GREEN)**
- `PaymentStatusServiceImpl` 이 `PaymentStatusQueryPort` 하나만 의존한다. `PaymentLoadUseCase` / `PaymentOutboxUseCase` 의존을 뺀다
- 스냅샷이 비면 `PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND)` — 기존 `PaymentLoadUseCase.getPaymentEventByOrderId` 와 같은 예외

**완료 기준**
- 위 테스트 전부 pass
- `PaymentStatusServiceImpl` 이 두 유스케이스를 참조하지 않는다
- `./gradlew :payment-service:test` 회귀 없음

**메모**
- 교체 후 `PaymentOutboxUseCase.findActiveOutboxStatus` 의 호출처가 없어진다. 제거 여부는 임의로 판단하지 않고 ship 리뷰에서 사용자 확인 후 정한다

**완료 결과**
- `PaymentStatusServiceImpl` 이 `PaymentStatusQueryPort` 하나만 의존하도록 교체. `PaymentLoadUseCase` / `PaymentOutboxUseCase` 의존 제거
- 스냅샷이 비면 기존과 같은 `PaymentFoundException.of(PAYMENT_EVENT_NOT_FOUND)` 를 던진다
- `PaymentStatusServiceImplTest` 를 `FakePaymentStatusQueryPort` 기반으로 재작성 (7케이스, PROCESSING 분기는 `@ParameterizedTest @EnumSource` 로 DONE/FAILED 제외 전체 커버). 스냅샷 미조회 검증은 Fake 를 `Mockito.spy` 로 감싸 확인
- `./gradlew :payment-service:test` 682건 전체 통과 — `PaymentOutboxUseCase.findActiveOutboxStatus` 호출처는 아직 남아 있어(Task 5 이후 정리 대상) 회귀 없음
- `PaymentStatusServiceImpl` 을 sliced 컨텍스트가 아닌 방식으로 부팅하는 테스트는 없어 프로덕션 `PaymentStatusQueryPort` 빈이 아직 없어도 unit test 범위에서 영향 없음 (구현체는 Task 4)

---

### Task 3: 복제본 데이터소스 설정 [tdd=false] [domain_risk=false]

설계 근거: "복제본 읽기 기술 — 복제본 데이터소스에 얹은 질의 템플릿", "신규 코드 배치 — 데이터소스 설정은 `payment/infrastructure`".

**구현 (GREEN)**
- `payment/infrastructure/config/ReplicaDataSourceConfig.java`
  - `payment.datasource.replica.enabled` 가 참이면 별도 `DataSource` 를 만들어 `paymentReplicaDataSource` 로 등록
  - 거짓이면 기본 데이터소스를 그대로 `paymentReplicaDataSource` 이름으로 노출한다. 읽기 복제 축의 "라우팅 끔" 사이클과 기존 통합 테스트가 설정 하나로 성립한다
  - `@Primary` 를 붙이지 않는다 — 기본 데이터소스 결정은 자동 설정에 그대로 맡긴다
- `application.yml` 에 기본값(`enabled: false`), `application-docker.yml` / 신규 override 에 복제본 접속 정보

**완료 기준**
- `payment.datasource.replica.enabled=false` 로 `./gradlew :payment-service:test :payment-service:integrationTest` 회귀 없음
- 참으로 켠 상태에서 앱이 뜨고 `paymentReplicaDataSource` 가 복제본을 가리킨다 (Task 7 기동 후 확인)
- 복제본 접속 문자열이 원본과 같은 타임존 파라미터(`connectionTimeZone=UTC` + `forceConnectionTimeZoneToSession=true`)를 갖는다 — 원본은 `application-docker.yml` 에서 이걸로 UTC 왕복을 강제하는데, 복제본만 빠지면 폴링이 읽는 승인시각이 세션 타임존만큼 어긋난다

**완료 결과**
- `payment/infrastructure/config/ReplicaDataSourceConfig.java` 신설 — `dataSource`(기본) / `paymentReplicaDataSource`(복제본) 두 빈을 함께 정의
- `application.yml` 에 `payment.datasource.replica.enabled` 기본값(false), `application-docker.yml` 에 복제본 접속 정보(`mysql-payment-replica` 호스트, D7 과 같은 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`) 추가
- **[Rule 1] 설계 문구("자동 설정에 그대로 맡긴다")와 실제로 구현 가능한 형태가 달라 그 자리에서 조정** — Spring Boot `DataSourceAutoConfiguration` 은 컨텍스트에 `DataSource` 타입 빈이 하나라도 있으면(`@ConditionalOnMissingBean(DataSource.class)`) 자체 기본 빈 생성을 통째로 건너뛴다. `paymentReplicaDataSource` 빈만 추가해도 이 조건에 걸려 자동 설정이 만드는 기본 데이터소스 자체가 사라지는 것을 통합 테스트로 확인했다(코드리뷰 리서치 대상 아님, 부팅 실패로 즉시 드러남). 대응: 이 설정 클래스가 기본 데이터소스도 같은 `spring.datasource.*` 프로퍼티로 직접 등록해 접속 정보·Hikari 풀 설정 등 외부 동작을 그대로 유지한다. 추가로 JPA 자동 설정(`JpaBaseConfiguration`)은 `@ConditionalOnSingleCandidate(DataSource.class)` 로 단일 후보 또는 `@Primary` 지목 빈을 요구해, 복제본 빈이 상시 두 번째 후보로 존재하는 이상 표시가 없으면 JPA 자동 설정 자체가 꺼진다 — 그래서 기본 데이터소스에만 `@Primary` 를 붙였다(복제본 빈에는 미부착, 원 설계 의도인 "이름·Qualifier 없는 자리는 기본을 받는다"는 그대로 보존)
- `payment.datasource.replica.enabled=false` 로 `./gradlew :payment-service:test` 682건 전체 통과
- `./gradlew :payment-service:integrationTest` 는 이번 변경 적용 전/후 모두 660건 중 651건 실패로 **동일** — 원인은 Task 2 가 `PaymentStatusServiceImpl` 을 `PaymentStatusQueryPort` 전용으로 바꾼 뒤 프로덕션 구현체가 아직 없어서(어댑터는 Task 4) 전체 컨텍스트 부팅 테스트가 전부 깨지는 기존 회귀다 — HEAD(Task 3 착수 전)에서 stash 후 재현해 사전 확인. 이번 태스크가 새로 만든 실패는 0건
- "참으로 켠 상태에서 앱이 뜨고 paymentReplicaDataSource 가 복제본을 가리킨다" 확인은 복제본 컨테이너가 없어 이번 태스크에서는 검증 불가 — Task 7(복제본 인프라 기동) 이후로 이월
- **이월 항목 해소** — Task 7 에서 복제본을 띄운 뒤 실제로 확인 완료. 상세는 Task 7 완료 결과 참고

---

### Task 4: 폴링 조회 어댑터 (질의 전용) [tdd=true] [domain_risk=true]

설계 근거: "복제본 읽기 기술 — 영속성 컨텍스트를 태우지 않는다". 폴링이 쓰는 값이 셋뿐이라 매핑이 필요 없고, 기존 데이터소스 구성을 건드리지 않는다.

**테스트 (RED)**
- `payment/infrastructure/repository/PaymentStatusQueryJdbcAdapterTest` — `@Tag("integration")`, Testcontainers MySQL(`withReuse(true)`, static 블록 수동 start), `JdbcTemplate` 으로 행을 직접 심는다
  - Spring 컨텍스트를 띄우지 않는다. 어댑터가 `JdbcTemplate` 하나만 받으므로 컨테이너 접속 정보로 직접 만들어 생성자에 넘긴다 — `@DataJpaTest` 는 JPA 를 안 쓰는 이 어댑터에 맞지 않고, 전체 부팅은 이 검증에 필요 없다
  - 컨테이너 접속 문자열에도 Task 3 과 같은 타임존 파라미터를 붙인다
  - `findActiveOutboxStatus_발행대기_행이면_PENDING`
  - `findActiveOutboxStatus_발행중_행이면_IN_FLIGHT`
  - `findActiveOutboxStatus_종결된_발행기록이면_빈값` (`@ParameterizedTest @EnumSource` 로 PENDING/IN_FLIGHT 를 뺀 나머지)
  - `findActiveOutboxStatus_행이_없으면_빈값`
  - `findStatusSnapshot_주문번호로_상태와_승인시각을_읽는다` — 승인시각이 UTC 기준으로 왕복하는지까지 확인
  - `findStatusSnapshot_행이_없으면_빈값`

**구현 (GREEN)**
- `payment/infrastructure/repository/PaymentStatusQueryJdbcAdapter.java` — `PaymentStatusQueryPort` 구현
  - `paymentReplicaDataSource` 로 만든 `JdbcTemplate` 만 쓴다. `@Transactional` 을 붙이지 않는다
  - `SELECT status FROM payment_outbox WHERE order_id = ?` / `SELECT order_id, status, approved_at FROM payment_event WHERE order_id = ?`
  - 발행 상태 필터(발행 대기 또는 발행 중)는 어댑터에서 도메인 열거형으로 변환한 뒤 판정한다 — SQL 에 상태 문자열을 흩뿌리지 않는다

**완료 기준**
- 위 테스트 전부 pass
- 어댑터가 `EntityManager` / JPA 리포지토리를 참조하지 않는다
- `./gradlew :payment-service:integrationTest --rerun-tasks` 회귀 없음

**완료 결과**
- `payment/infrastructure/repository/PaymentStatusQueryJdbcAdapter.java` 신설 — `payment_outbox`/`payment_event` 를 각각 단건 SELECT 로 읽고, 발행 상태 필터(발행 대기 또는 발행 중)는 SQL 이 아니라 `PaymentOutboxStatus.isClaimable()/isInFlight()` 로 자바 코드에서 판정. `EntityManager`·JPA 리포지토리 미참조, `@Transactional` 미부착
- `payment/infrastructure/config/ReplicaDataSourceConfig.java` 에 `paymentReplicaJdbcTemplate` 빈 추가 — `paymentReplicaDataSource` 를 감싼 `JdbcTemplate` 을 명시 이름으로 등록해 폴링 어댑터만 이 빈을 주입받도록 사슬을 관측 가능하게 만든다 (Task 5 격리 계약 테스트의 전제)
- `PaymentStatusQueryJdbcAdapterTest` (RED 커밋 `629326ad`) — Spring 컨텍스트 없이 Testcontainers MySQL 을 Flyway 로 마이그레이션한 뒤 수동 `JdbcTemplate` 로 어댑터를 직접 생성해 6케이스(발행대기/발행중/종결 2종 파라미터라이즈/미존재 2종/스냅샷 UTC 왕복) 검증, 통합 테스트 태그로 7건 전부 통과
- `./gradlew :payment-service:test` 682건 전체 통과, `./gradlew :payment-service:integrationTest --rerun-tasks` **667건 전체 통과(0 실패)** — Task 2 이후 660건 중 651건이 실패하던 회귀가 이 태스크의 프로덕션 `PaymentStatusQueryPort` 구현체 등록으로 완전히 해소됐다. 이번 태스크가 추가한 신규 테스트 7건을 빼면 기존 660건도 그대로 전부 통과

---

### Task 5: 복제본을 주입받는 빈이 폴링 어댑터 하나임을 고정 [tdd=true] [domain_risk=true]

설계 근거: 검증 전략 — "복제본으로 가는 조회가 폴링 경로 하나뿐임을 구조 계약으로 고정한다". 돈 경로 판정 일곱이 복제본을 읽지 않는 것이 이 설계의 안전 근거라, 테스트로 못박지 않으면 나중에 조용히 무너진다.

**테스트 (RED)**
- `payment/infrastructure/config/ReplicaDataSourceIsolationTest` — `@SpringBootTest`, `@Tag("integration")`
  - `복제본_데이터소스를_주입받는_빈은_폴링_어댑터_하나뿐이다` — `ConfigurableListableBeanFactory.getDependentBeans("paymentReplicaDataSource")` 결과가 폴링 어댑터(와 그것이 쓰는 질의 템플릿 빈)로만 이뤄진다
  - `폴링_어댑터가_아닌_저장소는_기본_데이터소스를_쓴다` — 결제 이벤트 저장소 어댑터의 의존 사슬에 복제본 데이터소스가 없다

**구현 (GREEN)**
- 테스트만으로 성립하면 프로덕션 코드 변경 없음. 빈 이름이 사슬에 안 드러나면 Task 3 에서 복제본 전용 `JdbcTemplate` 빈을 명시 이름으로 등록해 사슬을 관측 가능하게 만든다

**완료 기준**
- 위 테스트 pass
- 다른 빈에 복제본 데이터소스를 주입해 보면 테스트가 깨지고, 되돌리면 다시 통과한다 (한 번 확인 후 원복)

**완료 결과**
- **실제 결함을 테스트로 잡았다** — `paymentReplicaJdbcTemplate` 빈(Task 4)이 생기면서 Spring Boot `JdbcTemplateAutoConfiguration` 이 `@ConditionalOnMissingBean(JdbcOperations.class)` 에 걸려 기본 `JdbcTemplate` 자동 등록을 건너뛰고, 뒤이어 `NamedParameterJdbcTemplate` 자동 설정(`@ConditionalOnSingleCandidate(JdbcTemplate.class)`)이 유일하게 남은 복제본 템플릿을 단일 후보로 골라버렸다. `JdbcPaymentEventDedupeStore`(확정 경로 멱등 판정)가 `NamedParameterJdbcTemplate` 을 `@Qualifier` 없이 타입으로만 주입받아, 돈 경로 멱등 판정이 조용히 복제본을 읽고 쓰게 되는 경로였다. 복제본을 끈 상태(`enabled=false`)에서는 복제본 데이터소스가 기본과 같은 객체라 이 결함이 드러나지 않아 지금까지 통합 테스트가 전부 통과했었다
- `ReplicaDataSourceConfig` 에 기본 데이터소스용 `jdbcTemplate` 빈을 `@Primary` 로 명시 등록해 단일 후보 판정이 항상 기본 쪽으로 수렴하게 고쳤다. `paymentReplicaJdbcTemplate` Javadoc 도 "Spring Boot 가 자동 등록하는 기본 JdbcTemplate" 전제를 걷어내고 새 `jdbcTemplate` 빈을 가리키도록 정정
- `ReplicaDataSourceIsolationTest` — `payment.datasource.replica.enabled=true` 로 복제본을 기본과 별개 빈으로 띄운 구성에서 3케이스 검증
  - `paymentReplicaDataSource` → `paymentReplicaJdbcTemplate` → `paymentStatusQueryJdbcAdapter` 딱 두 단계 의존 사슬만 검사 (전체 전이 폐쇄를 쓰면 폴링 호출 경로의 서비스·컨트롤러까지 끌려 들어와 계약이 성립하지 않는다는 것을 실제로 겪고 depth 2 로 제한)
  - 결제 이벤트 저장소 어댑터가 복제본 의존 사슬에 없다
  - `namedParameterJdbcTemplate`/`jdbcPaymentEventDedupeStore` 가 복제본이 아닌 기본 데이터소스 의존 사슬에 있다 — 이번에 실제로 샜던 경로라 명시 포함
- **원복 확인** — `jdbcTemplate` 빈의 `@Primary` 를 임시로 제거해 돌려보니 3케이스 전부 실패(컨텍스트 로딩 단계에서 단일 후보 판정이 갈려 애플리케이션 부팅 자체가 흔들림), 되돌리자 다시 3케이스 전부 통과. 커밋에는 원복된 상태만 남음
- `./gradlew :payment-service:test` 682건, `:payment-service:integrationTest --rerun-tasks` 670건(기존 667 + 신규 3) 전체 통과 — 회귀 없음

---

### Task 6: 재고 캐시·멱등 저장소 연결의 클러스터 모드 전환 [tdd=false] [domain_risk=false]

설계 근거: "재고 캐시 분산 — 클러스터", "멱등 저장소 — 클러스터로 전환하되 대수는 고정".

**구현 (GREEN)**
- `StockRedisConfig` — `payment.cache.stock-redis.cluster-nodes` 가 비어 있지 않으면 `RedisClusterConfiguration`, 비어 있으면 지금의 단독 노드 설정. 토폴로지 자동 갱신을 켜 대수 변경 뒤 첫 명령이 리다이렉트로 새 배치를 따라가게 한다
- `RedisConfig` — `spring.data.redis.cluster.nodes` 에 대해 같은 분기. `@Primary` 표시와 기존 빈 이름은 그대로 둔다
- 두 설정 모두 명령·연결 제한값은 지금 값을 유지한다

**완료 기준**
- 노드 목록 미설정 시 기존과 동일하게 뜬다 — `./gradlew :payment-service:test :payment-service:integrationTest` 회귀 없음
- 노드 목록을 넣으면 클러스터 연결로 뜬다 (Task 8 기동 후 Task 12 가 실측으로 확인)

**완료 결과**
- `RedisConfig`(redis-dedupe, `spring.data.redis.cluster.nodes`)와 `StockRedisConfig`(redis-stock, `payment.cache.stock-redis.cluster-nodes`) 양쪽에 같은 분기 구조를 넣었다 — 프로퍼티가 비어 있으면 지금과 같은 `RedisStandaloneConfiguration` + 표준 `ClientOptions`, 채워지면 `RedisClusterConfiguration`(콤마 구분 `host:port` 목록 파싱) + `ClusterClientOptions`로 전환한다. 클러스터 쪽은 `ClusterTopologyRefreshOptions`(어댑티브 트리거 전체 + 30초 주기 갱신)를 얹어 대수 변경 뒤 첫 명령이 리다이렉트로 새 배치를 따라가게 했다
- 두 설정 모두 명령 타임아웃(3초)·연결 타임아웃(5초)은 기존 값 그대로 유지. `@Primary`는 `RedisConfig` 쪽 커넥션 팩토리에만 그대로 남아 있고 `StockRedisConfig` 는 미부착 — 직전 태스크(Task 5)에서 잡힌 "단일 후보 판정이 조용히 뒤바뀌는" 함정을 이번에도 점검했다: 두 빈 모두 이름이 명시돼 있고(`stockCacheRedisConnectionFactory` 등) `@ConditionalOnMissingBean`류 자동 설정 간섭 지점이 없어 노드 목록 유무와 무관하게 `@Primary` 쏠림이 재발하지 않는 구조
- `application.yml` 에 `spring.data.redis.cluster.nodes`(env `SPRING_DATA_REDIS_CLUSTER_NODES`)와 `payment.cache.stock-redis.cluster-nodes`(env `REDIS_STOCK_CLUSTER_NODES`) 기본값(빈 문자열)을 추가해 Task 8 인프라가 컨테이너 목록을 env var 로 주입할 자리를 마련했다
- `./gradlew :payment-service:test` 682건, `:payment-service:integrationTest --rerun-tasks` 670건 전체 통과 — 노드 목록 미설정(현재 기본값) 구성에서 회귀 없음
- "노드 목록을 넣으면 클러스터 연결로 뜬다"는 실제 클러스터 컨테이너가 없어 이번 태스크에서는 검증 불가 — Task 8(캐시 클러스터 인프라 기동) 이후 Task 12(클러스터 라이브 점검)에서 실측하는 것으로 이월
- **이월 항목 해소** — Task 8 이 캐시 클러스터 컨테이너를 띄운 뒤, `REDIS_STOCK_CLUSTER_NODES`/`SPRING_DATA_REDIS_CLUSTER_NODES` 를 채운 상태로 payment-service 를 재기동해 두 커넥션 모두 실제 클러스터 노드에 `CLUSTER MYID`(토폴로지 조회)를 던지는 것을 `redis-cli client list` 로 확인 완료. 상세는 Task 8 완료 결과 참고 — 스크립트 다섯 경로가 클러스터에서 도는지의 심층 점검은 여전히 Task 12 의 몫

---

### Task 7: payment DB 복제본 인프라 [tdd=false] [domain_risk=false]

설계 근거: "복제 방식 — 비동기 복제 1대".

**구현 (GREEN)**
- `docker/docker-compose.scaleout.yml`(신규 override) — `mysql-payment` 에 `server-id` / binlog 옵션을 얹고, `mysql-payment-replica` 컨테이너를 추가한다
- `scripts/bench-replica-setup.sh` — 복제 계정 생성, 원본 좌표 확인, 복제 시작, 상태 확인까지 한 번에
- `payment-service` 환경에 복제본 접속 정보와 `payment.datasource.replica.enabled` 주입

**완료 기준**
- 스택 기동 후 `SHOW REPLICA STATUS` 의 IO / SQL 스레드가 둘 다 Yes
- 원본에 넣은 행이 복제본에서 읽힌다 (스크립트가 왕복 한 건으로 확인)
- 스크립트를 두 번 돌려도 같은 결과 (멱등)

**완료 결과**
- `docker/docker-compose.scaleout.yml` 신설(다른 compose 파일 위에 얹는 override) — `mysql-payment` 에 `--server-id=1 --log-bin=mysql-bin --binlog-format=ROW` 를 얹어 복제 소스로 만들고, `mysql-payment-replica`(server-id=2, `--read-only=ON`, 별도 볼륨·3307 포트) 컨테이너를 신설. `payment-service` 에 `PAYMENT_DATASOURCE_REPLICA_ENABLED=true` / `PAYMENT_DB_REPLICA_HOST=mysql-payment-replica` 를 주입하고 `mysql-payment-replica` healthy 를 `depends_on` 에 추가 — compose 의 키 기반 병합으로 기존 `environment`/`depends_on` 을 그대로 두고 항목만 더한다
- `scripts/bench-replica-setup.sh` 신설 — 복제 전용 계정(`repl`, `mysql_native_password`) 생성 → `mysqldump --single-transaction --source-data=2` 로 소스 스냅샷과 binlog 좌표를 함께 뜬 뒤 복제본에 복원 → `CHANGE REPLICATION SOURCE TO` + `START REPLICA` → `SHOW REPLICA STATUS` 로 IO/SQL 스레드 Yes 확인 → 소스에 마커 테이블(`bench_replica_probe`) 행을 쓰고 복제본에서 같은 값이 읽히는지 왕복 확인. 이미 정상 복제 중이면 스냅샷 재동기화를 건너뛰고 왕복 확인만 재실행 (멱등)
- **실측 확인** — `mysql-payment` + `mysql-payment-replica` 를 기동하고 스크립트를 두 번 연속 실행, 둘 다 `SHOW REPLICA STATUS` IO/SQL 스레드 Yes + 왕복 확인 성공으로 종료(exit 0). 두 번째 실행은 초기 동기화를 건너뛰고 왕복 확인만 재실행해 멱등성 확인
- **Task 3 이월 항목 해소** — `:payment-service:bootJar` 빌드 후 `payment.datasource.replica.enabled=true` 로 `payment-service` 를 컨테이너로 기동. 로그에서 `ReplicaDataSourceConfig` 가 "복제본 데이터소스를 사용합니다" 분기를 탔음을 확인, `mysql-payment-replica` 의 `Threads_connected` 가 0 이 아님을 확인. 결정적 검증으로 소스에 `payment_event` 행 하나를 직접 심고(status=DONE) `/api/v1/payments/{orderId}/status` 폴링이 정상 응답하는 것을 확인한 뒤, **복제본의 IO 스레드를 멈추고 소스만 다른 값(status=FAILED)으로 바꿔** 같은 API 를 다시 호출 — 응답이 여전히 DONE(복제본에 남은 옛 값)으로 나와 폴링이 실제로 복제본을 읽고 있음을 확정. 검증에 쓴 임시 행은 소스에서 삭제해 복제본까지 정리, 복제는 재개해 IO/SQL 스레드 Yes 로 원복
- `./gradlew :payment-service:test` 682건 전체 통과 (이 태스크는 Java 코드를 건드리지 않음 — 회귀 없음 재확인 목적)
- 검증에 쓴 payment-service 앱 컨테이너와 인프라(mysql-payment/replica, eureka, kafka, redis-dedupe/stock)는 Task 8 이후 인프라 태스크가 이어서 쓸 수 있도록 내리지 않고 그대로 둔다

---

### Task 8: 재고 캐시·멱등 저장소 클러스터 인프라 [tdd=false] [domain_risk=false]

설계 근거: "재고 캐시 분산 — 마스터 1 / 2 / 4, 복제 노드 없음", "클러스터 슬롯 커버리지 — 기본 동작을 끈다", "대수 비교축 — 전 구간 클러스터 모드로 통일".

**구현 (GREEN)**
- 같은 override 파일에 재고 캐시 노드(최대 4)와 멱등 저장소 노드(고정 대수)를 정의한다. 컨테이너 이름을 고정하지 않아 대수를 실행 옵션으로 바꿀 수 있게 한다
- 모든 캐시 노드에 `--cluster-enabled yes`, `--cluster-require-full-coverage no`. 재고 캐시는 지금의 `appendfsync always` 를 그대로 둔다 (프로덕션 구성 그대로 재는 것이 결정)
- `scripts/bench-redis-cluster.sh --masters N` — 지정 대수로 클러스터를 만들고 슬롯 배정까지 마친다. 마스터 1대도 클러스터로 성립시킨다

**완료 기준**
- 대수 1 / 2 / 4 각각에서 `CLUSTER INFO` 의 state 가 ok 이고 슬롯 16384 가 전부 배정된다
- `CLUSTER COUNT-FAILURE-REPORTS` 기준이 아니라 설정값으로 슬롯 커버리지 요구가 꺼져 있음을 `CONFIG GET cluster-require-full-coverage` 로 확인
- 스크립트를 다시 돌리면 기존 클러스터를 지우고 같은 상태로 다시 만든다

**완료 결과**
- `docker/docker-compose.scaleout.yml` 에 `redis-stock-cluster`(재고 캐시, 최대 4)와 `redis-idempotency-cluster`(멱등 저장소, 고정 3) 두 서비스 신설 — 둘 다 `container_name` 미부착·named volume 없음(에페메럴)이라 `--scale` 실행 옵션만으로 대수를 바꾸고 매번 빈 상태로 새로 시작한다. 재고 캐시는 지금의 단독 `redis-stock`과 같은 `--appendfsync always`를 유지, 멱등 저장소는 `redis-dedupe`와 같은 `--appendonly no`. 두 서비스 모두 `--cluster-enabled yes --cluster-require-full-coverage no`
- `scripts/bench-redis-cluster.sh --store {stock|dedupe} --masters N` 신설 — 기존 컨테이너 제거 → `--scale` 로 N대 재기동 → ping 확인 → `CLUSTER SET-CONFIG-EPOCH`(MEET 이전에 확정) → `CLUSTER MEET` 풀 메시 → `CLUSTER ADDSLOTSRANGE` 로 슬롯 16384개를 N등분 배정 → `cluster_state:ok` + `cluster_slots_assigned:16384` 수렴 대기 → `CONFIG GET cluster-require-full-coverage` 로 no 확인. `redis-cli --cluster create` 헬퍼는 마스터 3대 미만을 자체 가드로 거부해(이 축은 마스터 1대 클러스터도 성립해야 함) 쓰지 않고, MEET+ADDSLOTSRANGE 를 수동으로 밟는 방식을 택했다
- **실측 확인** — `--store stock`을 대수 1 / 2 / 4 각각으로 실행해 매번 `cluster_state:ok` + 슬롯 16384 전부 배정 + `cluster-require-full-coverage:no` 확인. `--store dedupe --masters 3`도 동일 확인. 대수 4 → 2 로 재실행해 이전 컨테이너가 완전히 제거되고(잔여 없이 딱 2개) 새 클러스터가 처음부터 다시 만들어지는 것으로 재실행 멱등성 확인
- **Task 6 이월 항목 해소** — 위 클러스터(재고 2대, 멱등 3대)가 뜬 상태에서 `payment-service` 환경에 `REDIS_STOCK_CLUSTER_NODES`/`SPRING_DATA_REDIS_CLUSTER_NODES`(클러스터 노드 컨테이너명:6379 콤마 목록)를 일시적으로 주입해 `--force-recreate` 재기동, `Started PaymentPlatformApplication` 정상 부팅을 확인한 뒤 `redis-cli client list` 로 재고·멱등 클러스터 노드 양쪽에서 payment-service 컨테이너 IP 가 `cmd=cluster|myid`(Lettuce 의 토폴로지 조회)로 접속해 있음을 확인 — 단독 연결이 아니라 실제 클러스터 연결로 뜬 것을 결정적으로 확인했다. 확인 후 환경 변수를 원복하고 재기동해 이후 태스크가 쓰는 기본 상태(단독 노드 연결)로 되돌렸다
- 클러스터 노드 목록을 compose 파일에 영구 고정하지 않은 이유 — 재고 캐시 대수 자체가 Task 14 의 측정 변수(1/2/4)라 특정 대수를 정적으로 박아 두면 대수가 바뀔 때마다 파일을 고쳐야 한다. 실제 노드 목록 주입은 대수를 정하는 시점(사이클 스크립트, Task 13)에서 계산해 넘기는 것이 맞다고 판단
- 인프라 기동 상태 — 검증 후 재고 캐시 클러스터 2대·멱등 저장소 클러스터 3대를 켠 채로 둔다(다음 태스크가 이어 쓸 수 있게). payment-service 는 단독 Redis 연결(cluster-nodes 미설정)로 원복된 상태
- Java 코드 변경 없음(docker-compose·bash 스크립트만) — `./gradlew :payment-service:test` UP-TO-DATE 로 기존 682건 결과 유지, 회귀 없음

---

### Task 9: 부하 프로필 상품 다중화 [tdd=false] [domain_risk=false]

설계 근거: "부하 프로필 — 상품 종류 100개, 주문당 상품 1개 고정. 다중 상품 축에서만 주문당 3개".

**구현 (GREEN)**
- `scripts/bench-seed-stock.sh` — `PRODUCT_COUNT`(기본 100) 만큼 상품·재고 행을 보장하고(없으면 넣고), 상품별로 원본과 캐시를 같은 상수로 덮는다. 클러스터 대응으로 리다이렉트를 따라가게 호출한다
- `scripts/k6/helpers.js` — `PRODUCT_COUNT`, `ITEMS_PER_ORDER`(기본 1) 를 받고, 반복마다 상품을 고르게 골라 주문 항목을 만든다. 한 주문 안에서 같은 상품이 두 번 들어가지 않게 한다
- `scripts/k6/async-payment.js` — 바뀐 헬퍼를 그대로 쓴다

**완료 기준**
- 시드 후 상품 100개 전부에서 원본 수량 = 캐시 수량
- k6 를 짧게 돌리면 주문이 여러 상품에 퍼진다 (상품별 주문 수를 세어 확인)
- `ITEMS_PER_ORDER=3` 으로 돌리면 한 주문에 서로 다른 상품 3개가 담긴다

**완료 결과**
- `scripts/bench-seed-stock.sh` 전면 재작성 — 상품 id 범위를 `PRODUCT_ID_BASE`(기본 1000) ~ `PRODUCT_ID_BASE+PRODUCT_COUNT-1`(기본 100종 → 1000..1099)로 잡아 스모크/통합 테스트가 쓰는 product id=1 대역과 겹치지 않게 했다. product/stock 행은 `INSERT IGNORE`로 보장하고 quantity는 매번 `BENCH_STOCK`(기본 1000만)으로 UPDATE — 단일 트랜잭션 한 번의 `docker exec`로 100종 전부 처리. redis-stock SET/GET은 컨테이너 안에서 `redis-cli -c`(클러스터 모드)로 상품마다 호출해 MOVED 리다이렉트를 자동으로 따라가게 했다 — 파이프 모드는 응답을 안 읽어 리다이렉트를 못 따라가서 쓰지 않음
- `scripts/k6/helpers.js` — `PRODUCT_COUNT`(기본 1, 하위호환)/`PRODUCT_ID_BASE`(기본 1000)/`ITEMS_PER_ORDER`(기본 1) 세 상수를 추가하고, `doCheckout()` 내부에 `selectOrderProductIds()`를 신설했다. `PRODUCT_COUNT<=1`이면 기존과 동일하게 `PRODUCT_ID` 단일 상품만 쓴다. 그 이상이면 `k6/execution`의 `exec.scenario.iterationInTest`(VU 전역 반복 카운터)를 `PRODUCT_COUNT`로 나눈 나머지를 시작 인덱스로 삼아 상품을 순환시키고, `ITEMS_PER_ORDER`개를 인덱스 연속 슬롯에서 뽑아 한 주문 안에서 상품이 중복되지 않게 했다(전제: `ITEMS_PER_ORDER<=PRODUCT_COUNT`)
- `scripts/k6/async-payment.js`는 변경하지 않음 — `doCheckout()`을 인자 없이 그대로 호출하므로 헬퍼 쪽 상수만으로 동작이 바뀐다
- **알고리즘 검증** — `k6/execution`은 k6 런타임 밖에서 import할 수 없어, `selectOrderProductIds()`와 동일한 산식을 node로 재현해 별도 검증: PRODUCT_COUNT=1일 때 기존과 동일한 단일 상품 반환, 100종 대상 300회 반복에서 상품별 등장 횟수가 정확히 3회씩(완전 균등), ITEMS_PER_ORDER=3에서 100회 반복 전부 중복 없음(경계 랩어라운드 케이스 `[1099,1000,1001]` 포함) 확인
- **실측 확인(라이브)** — mysql-product/product-service/user-service를 기동하고 `bench-seed-stock.sh`로 100종 시드 후, `grafana/k6` 컨테이너로 payment-service에 직접(gateway 우회) 부하를 흘렸다. (1) `ITEMS_PER_ORDER=1`: 76건 checkout 전부 201, `stock_hold_record`에서 상품 1000~1075 각 1건씩 — 완전 균등 분산 확인. (2) `ITEMS_PER_ORDER=3`: 51건 전부 성공, `stock_hold_record`의 `UNIQUE(order_id, product_id)` 제약이 살아있는 상태에서도 전건 성공해 상품 중복이 실제로 발생하지 않았음을 확정 — 표본 조회로 각 주문이 연속된 서로 다른 상품 3개(`1037,1038,1039` 등)를 담은 것도 직접 확인. 검증 후 `stock_hold_record`를 비우고 `bench-seed-stock.sh`를 재실행해 재고를 상수(1000만)로 복원
- **인프라 기동 상태 갱신** — 이번 태스크로 mysql-product/product-service가 새로 필요해져 기동해 뒀다(다음 상품 시드·측정 태스크가 계속 쓸 것으로 판단해 유지). 라이브 검증에만 쓴 user-service는 검증 후 정지. `docs/STATE.md`에 반영
- Java 코드 변경 없음(bash·k6 스크립트만) — `./gradlew :payment-service:test` UP-TO-DATE로 기존 결과 유지, 회귀 없음

---

### Task 10: 사이클 재구성 절차 스크립트 [tdd=false] [domain_risk=true]

설계 근거: "사이클 사이 재구성 절차 — 다섯 단계를 순서대로 밟는다". 확인과 비우기 사이가 열려 있으면 낙오 확정의 캐시 차감분이 지워져 같은 재고 단위가 다음 사이클에서 또 팔린다.

**구현 (GREEN)**
- `scripts/bench-cycle-reset.sh` — 다섯 단계를 순서대로 밟고, 단계마다 통과 못 하면 재시드 없이 종료한다
  1. 부하 도구가 멈춘 것을 확인한다 (이 환경에서 확정 요청의 유일한 출처)
  2. 미종결 결제(진행 중 · 재시도 대기 · 접수 상태) 0, **격리 결제 0**, 미회수 선차감 기록 0 을 짧은 폴링 창으로 안정 확인. 이 프로젝트의 기존 검증 용어에서 미종결과 격리는 별개로 세는 값이라, 미종결만 보면 격리 잔류가 그대로 통과한다 — 바로 다음 줄의 격리 처리가 아예 발동하지 않고 격리가 남은 채로 캐시를 비우게 된다
  3. 재고 확정 메시지의 소비 적체 0 안정 확인 — 소비자 그룹 `product-service-stock-commit`
  4. 회수 주기 작업 정지 — payment 를 **서비스 단위로** 멈춘다(`docker compose stop payment-service`). 회수 워커는 인스턴스마다 독립으로 도는데 끄는 설정값이 없어 프로세스를 멈추는 것 말고는 정지 수단이 없다. 컨테이너 하나만 겨냥하면 인스턴스 2 / 3 / 4대 사이클에서 남은 인스턴스의 회수가 재확인과 비우기 사이에 끼어들어, 방금 닫은 창이 그대로 다시 열린다
  5. **(2)와 (3)을 한 번 더 통과한 직후** 캐시를 비우고 상품별 상수로 재시드
- 잔류가 남으면 무엇이 얼마나 남았는지 출력하고, 종결 가능한 격리는 관리자 종결로 비운 뒤 재확인한다. 반복해도 안 비면 사이클 실패로 종료한다 (무기한 대기 금지)

**완료 기준**
- 다섯 단계가 순서대로 실행되고, (2)/(3) 재확인을 통과하지 못하면 캐시를 비우지 않는다
- 인스턴스 2 / 3 / 4대 사이클에서 정지 직후 실행 중인 payment 컨테이너가 0 인 것을 확인하고, 하나라도 살아 있으면 비우기로 넘어가지 않는다
- 격리 결제를 인위로 하나 남겨 두면 재구성이 멈추고, 그것을 관리자 종결로 비우면 재확인을 통과해 재시드까지 이어진다
- 잔류를 인위로 만들어 두면 0 이 아닌 코드로 끝나고 남은 내역이 출력된다
- 정상 상태에서 돌리면 상품 100개 전부가 상수로 재시드된 채 끝난다

**완료 결과**
- `scripts/bench-cycle-reset.sh` 신설 — 다섯 단계를 순서대로 밟고, 잔류가 안 비면 재시드 없이 종료한다
  - (1) `pgrep -f "k6 run"` + `docker ps` 로 k6 프로세스/컨테이너 부재를 확인 — 실패 시 즉시 exit 1(폴링 없이 선결 조건으로 다룬다, "이 환경에서 확정 요청의 유일한 출처"라 멈추지 않았으면 재구성 자체가 무의미하다)
  - (2) 미종결(`payment_event.status IN (READY,IN_PROGRESS,RETRYING)`) 0, **격리(`QUARANTINED`) 0**, 미회수 선차감 기록(`stock_hold_record.status=NOISE`) 0 을 셋 다 같은 폴링 루프에서 연속 3회(기본) 0 으로 안정 확인 — 미종결과 격리를 별개 카운트로 분리해 미종결만 보고 격리 잔류를 통과시키는 실수를 구조로 막았다. 격리가 남으면 매 폴링마다 살아있는 payment-service 컨테이너에 `docker exec curl`로 관리자 종결(`POST /admin/payments/events/{id}/resolve-quarantine`)을 자동 시도한 뒤 재확인 — gateway 는 `/admin/**` 를 라우팅하지 않아 payment-service 컨테이너 내부에서 로컬 호출한다
  - (3) 재고 확정 소비자 그룹(`product-service-stock-commit`) 을 `kafka-consumer-groups --describe` 로 조회해 LAG 합계 0 을 같은 방식으로 안정 확인
  - (4) `docker compose stop payment-service` 로 **서비스 단위** 정지 — `StockHoldRecoveryWorker`(회수 워커)에 인스턴스별 비활성화 설정이 없어(`@Scheduled` 고정 주기) 컨테이너 하나만 멈추면 남은 인스턴스의 회수가 재확인과 비우기 사이에 끼어드는 것을 코드로 확인하고, 정지 방식을 컨테이너 단위가 아닌 서비스 단위로 못박았다. 정지 직후 `dc ps -q --status running payment-service` 로 실행 중 컨테이너 0 을 확인하고, 하나라도 남으면 exit 3
  - (5) (2)/(3) 을 **단발 재확인**(안정 폴링 재적용 없이 즉시 1회 조회)한 직후에만 `scripts/bench-seed-stock.sh` 를 호출해 비우고 재시드 — payment-service 가 이미 (4)에서 멈춰 새 확정 요청·재고 확정 메시지가 나갈 출처가 없으므로, 다시 3연속 폴링을 요구하면 그 대기 시간만큼 확인-비우기 창을 불필요하게 늘리는 셈이라 즉시 판정으로 좁혔다. 재확인이 걸리면 exit 4 — payment-service 가 이미 멈춘 상태라 관리자 종결 자동 재시도 없이 사람 개입을 요구한다
- 격리 자동 종결 호출은 벤더 상태 조회가 "확인불가(UNKNOWN)"로 나와도 막지 않는다(`PgVendorStatusHttpAdapter` 가 pg-service 불통을 예외로 던지지 않고 UNKNOWN 으로 흡수) — 벤더 승인이 실제로 확인된 건만 유스케이스가 거부한다
- **실측 확인** — 이 topic 검증 과정에서 mysql-payment 에 쌓인 낡은 bench 잔류(READY 128건 · NOISE 153건, Task 9 라이브 검증이 pg-service 없이 checkout 만 흘려 영구 미종결로 남은 건)를 정리해 0 상태로 만든 뒤 세 시나리오를 실제로 돌렸다
  1. **정상 상태** — 미종결/격리/미회수/소비적체 전부 0 인 상태에서 실행 → 다섯 단계 전부 통과, `docker-payment-service-1` 정지 확인, `bench-seed-stock.sh` 로 상품 100종 전부 재시드, exit 0
  2. **잔류를 인위로 만든 경우** — READY 상태 `payment_event` 행 하나를 직접 심고 실행 → (2) 단계가 "잔류 — 미종결=1"을 반복 출력하며 폴링 상한(3회)까지 안정화하지 못하고 exit 2 로 종료. payment-service 컨테이너는 계속 살아 있었고(`docker-payment-service-1 Up`), `stock:{1000}` redis 값도 시드값 그대로(10000000)라 캐시가 전혀 비워지지 않았음을 확인
  3. **격리 결제를 인위로 하나 남긴 경우** — `QUARANTINED` 상태 `payment_event` 행 하나를 직접 심고 실행 → (2) 단계가 격리 1건을 감지해 관리자 종결을 자동 호출, 같은 폴링 루프 안에서 상태가 `FAILED`(사유: `bench-cycle-reset 자동 회수 / 벤더 상태 조회 결과: 확인불가`)로 바뀐 것을 재확인해 안정 통과 → (3)/(4)/(5) 순서대로 이어져 재시드까지 완료, exit 0. `payment_event.status_reason` 을 직접 조회해 관리자 종결이 실제로 반영됐음을 확인
  - 세 시나리오 모두 종료 후 테스트로 심은 행을 지우고 payment-service 를 재기동, `bench-seed-stock.sh` 로 재시드해 다음 태스크가 이어 쓸 기동 상태를 정상으로 복원했다
- Java 코드 변경 없음(bash 스크립트 1개 신설) — `./gradlew :payment-service:test` UP-TO-DATE, 회귀 없음
- 인프라 기동 상태 갱신 — 검증 중 mysql-payment 의 낡은 bench 잔류(payment_event/payment_order/payment_outbox/payment_history/payment_event_dedupe/stock_hold_record)를 정리했다. 이후 태스크는 이 여섯 테이블이 빈 상태에서 시작한다

---

### Task 11: 정합 검증 상품별 확장 [tdd=false] [domain_risk=true]

설계 근거: 검증 전략 — "정합성은 매 사이클 종료 후 상품별로 확인한다… 재고 확정 메시지의 소비 적체가 0 으로 안정된 뒤에 잰다".

**구현 (GREEN)**
- `scripts/k6/verify-settlement.sh` 확장
  - 재고 대조를 상품 하나가 아니라 시드된 상품 전부에 대해 수행하고, 어긋난 상품을 개별로 출력한다
  - 미회수 선차감 기록 0 을 선결 조건에 넣는다
  - 소비 적체 0 안정을 선결 조건에 넣는다 — 남은 채로 재면 정상인데 불일치로 찍힌다
  - 캐시 조회를 클러스터 리다이렉트 대응으로 바꾼다
  - 결제 상태와 재고 차감이 어긋난 건이 없는지 확인한다. 기존 교차식(부하 도구 카운트 대 DB 상태 카운트)이 유실만 잡고 건별 어긋남은 안 잡으므로, 종결된 결제의 선차감 기록과 상태를 대조하는 검사를 더한다
  - **판정을 기계가 읽을 수 있게 낸다** — 지금은 불일치가 나도 종료 코드가 0 이라 사이클 러너가 통과와 구분할 수 없다. 종료 코드를 통과 0 / 판단 보류 2 / 불일치 3 으로 가르고(접속·전제 실패는 지금처럼 1), 같은 판정을 `results/<CASE_NAME>-verdict.json` 에 한 필드로 남긴다. 부하 도구가 쓴 결과 파일은 건드리지 않는다 — 사후에 필드를 끼워 넣으면 그 파일을 읽는 다른 도구와 스키마가 어긋난다. 자동 소비처가 아직 없어 기존 사용을 깨지 않는다
  - **대기로 풀리는 보류와 안 풀리는 보류를 가른다** — 종결이 덜 끝나 판정을 못 내리는 것은 판단 보류(2)다. 격리 결제가 남아 판정을 못 내리는 것은 기다려도 안 풀리므로 불일치(3)로 낸다. 지금 스크립트는 둘을 같은 판단 보류로 묶어 두는데, 그대로 두면 러너가 격리 건에 재시도 횟수만 소모하고 늦게 멈춘다

**완료 기준**
- 상품 하나만 어긋나게 만들어도 불일치로 잡힌다
- 소비 적체가 남은 상태에서는 정합 판정을 내리지 않고 판단 보류로 끝난다
- 세 판정(통과 / 판단 보류 / 불일치)이 각각 다른 종료 코드로 나오고, `results/<CASE_NAME>-verdict.json` 에서도 같은 값을 읽을 수 있다
- 격리 결제가 남은 상태에서는 판단 보류가 아니라 불일치로 끝난다
- 정상 종료 상태에서 상품 100개 전부 통과

**완료 결과**
> (execute에서 채움)

---

### Task 12: 클러스터 라이브 점검 스크립트 [tdd=false] [domain_risk=false]

설계 근거: 검증 전략 — "클러스터 구성에서 스크립트 5종이 전부 정상 실행되는지, 한 상품의 키가 같은 노드에 놓이는지", "상품 종류가 노드에 고르게 안 퍼진다" 장애 시나리오.

**구현 (GREEN)**
- `scripts/bench-cluster-check.sh`
  - 결제 한 건을 정상 경로로 흘려 선차감 · 되돌리기 · 격리 복구 보상 · 주문 선점 획득 · 해제 다섯 경로가 클러스터에서 모두 도는 것을 확인한다
  - 한 상품에 딸린 키(재고 · 선차감 표시 · 되돌리기 표시)가 같은 노드에 놓이는지 `CLUSTER KEYSLOT` 으로 대조한다
  - 노드별 키 개수를 세어 분포 편차를 출력한다. 편차가 크면 상품 수를 늘려 다시 배치하라고 알린다

**완료 기준**
- 마스터 2 / 4 구성에서 다섯 경로 전부 정상 결과
- 임의의 상품에 대해 딸린 키가 전부 같은 슬롯
- 노드별 키 분포가 출력되고 편차 임계를 넘으면 0 이 아닌 코드로 끝난다

**완료 결과**
> (execute에서 채움)

---

### Task 13: 사이클 러너와 복제 지연 계측 [tdd=false] [domain_risk=true]

설계 근거: "사이클을 어떻게 짤 것인가" 표, "폴링의 복제 지연 — 체감 지연으로 관측", "지연 지표 — p50 / p95 / p99. 복제 지연을 함께 계측", "관측 스택 — 전 구간 유지".

**구현 (GREEN)**
- `scripts/bench-scaleout-cycle.sh` — 사이클 하나를 인자(인스턴스 수 / 재고 마스터 수 / 폴링 라우팅 / 주문당 상품 수 / 벤더 지연)로 받아 끝까지 무인 실행한다
  - 스택 기동(관측 스택 포함) → 클러스터 구성 → 시드 → 부하 → 종결 대기 → 정합 검증 → 재구성
  - 사이클 동안 복제본의 지연 초를 주기로 표본화해 사이클 로그에 남긴다
  - 결과를 `results/<CASE_NAME>-cycle.json` 에 남긴다 — 조건 값, 처리율, 백분위 지연, 복제 지연, 정합 판정. 부하 도구가 쓰는 `<CASE_NAME>.json` 과 Task 11 의 판정 파일 어느 쪽도 덮지 않는다. 사이클 이름이 케이스 이름과 같으면 원본 카운트가 사라져 재검증할 근거가 없어진다
  - 정합 판정은 Task 11 이 낸 종료 코드로 받는다. 판단 보류면 정해진 횟수만큼만 기다렸다 다시 재고, 그래도 보류면 그 사이클을 실패로 끝낸다 — 무기한 기다리지 않는다. 불일치면 재구성으로 넘어가지 않는다. 캐시를 비우고 재시드하면 어긋난 값과 선차감 기록이 지워져 원인을 되짚을 수 없다
  - 실패로 멈춘 사이클의 잔류를 치우는 것은 러너가 아니라 사람이다. 다음 사이클을 시작하기 전에 Task 10 을 따로 돌려 잔류를 비운다 — 격리 종결처럼 사람 판단이 필요한 자리가 있어 러너가 알아서 밀고 가면 안 된다
- 지연 백분위는 폴링 응답과 전체 완료를 나눠 p50 / p95 / p99 로 뽑는다

**완료 기준**
- 사이클 하나를 인자만 주고 끝까지 돌릴 수 있다
- 결과 파일에 조건 값과 다섯 지표(확정 처리율 / 전체 완료 처리율 / 폴링 지연 백분위 / 전체 완료 지연 백분위 / 복제 지연)가 남는다
- 정합 검증이 실패하면 그 사이클을 실패로 기록하고 다음으로 넘어가지 않으며, 캐시를 비우지 않고 멈춘다
- 판정을 인위로 불일치·판단 보류로 만들어 두면 러너가 각각 다르게 반응한다 (불일치는 즉시 중단, 보류는 유한 재시도 후 실패)
- 정합 검증이 불일치나 보류 소진으로 끝나면 러너가 재구성 스크립트를 부르지 않는다
- 검증이 접속·전제 실패로 끝나거나 러너가 모르는 코드로 끝나도 정합 검증 실패와 똑같이 다룬다 — 판정을 못 받은 것을 통과로 읽지 않는다

**완료 결과**
> (execute에서 채움)

---

### Task 14: 재고 캐시 대수 축 측정 (마스터 1 / 2 / 4) [tdd=false] [domain_risk=false]

설계 근거: 사이클 표의 첫 축(인스턴스 2, 폴링 라우팅 켬 고정), "캐시 내구성 설정 — 대수 효과가 없게 나오면 fsync 정책을 낮춘 확인 사이클을 추가한다", "라이브 검증 — 첫 사이클 전에 인프라 기동 점검과 트레이스 연속성 점검".

**구현 (GREEN)**
- 측정 시작 전 확인: Docker 메모리 20GB, 호스트 스왑 여유, `docs/smoke/infra-healthcheck.md` 와 `docs/smoke/trace-continuity-check.md` 각 1회
- 사이클 3개 실행 — 재고 마스터 1 / 2 / 4, 나머지 고정
- 대수 효과가 관측되지 않으면 fsync 정책을 낮춘 확인 사이클 1개를 덧붙여 단일 스레드 한계와 디스크 공유를 가른다

**완료 기준**
- 사이클 3개의 결과 파일이 남고 각각 정합 검증 통과
- 시작 전 점검 결과(메모리 여유 · 기동 점검 · 트레이스 연속성 · 노드별 키 분포)가 기록으로 남는다
- 대수별 처리율 비교표가 나오고, 효과가 없으면 확인 사이클 결과까지 함께 남는다

**완료 결과**
> (execute에서 채움)

---

### Task 15: 인스턴스 수 축 측정 (1 / 2 / 3 / 4) [tdd=false] [domain_risk=false]

설계 근거: 사이클 표의 둘째 축, "인스턴스 단계 — payment 1 / 2 / 3 / 4", "합격선 — 인스턴스 1 → 2 확정 처리율 1.6배".

**구현 (GREEN)**
- 재고 마스터를 Task 14 의 최적값으로 고정하고 인스턴스 1 / 3 / 4 사이클 실행 (2대 지점은 앞 축과 조건이 같아 재사용)
- 4대 구간에서 CPU 사용률과 메모리 여유를 함께 기록한다 — 앱 CPU 가 먼저 포화하면 그 사실이 결론이 된다

**완료 기준**
- 사이클 3개의 결과 파일이 남고 각각 정합 검증 통과
- 인스턴스 1대 기준선 대비 2 / 3 / 4 대의 확정 처리율·전체 완료 처리율 상대비가 나온다
- 1 → 2 구간이 1.6배 판정선을 넘었는지 여부가 명시된다

**완료 결과**
> (execute에서 채움)

---

### Task 16: 읽기 복제·다중 상품·벤더 지연 축 측정 [tdd=false] [domain_risk=false]

설계 근거: 사이클 표의 나머지 세 축. "읽기 복제 효과 — 폴링 라우팅 끔 1점", "다중 상품 주문 — 재고 마스터 4 고정, 주문당 상품 3개", "벤더 지연 — 나머지를 전부 고정한 별도 축 1점".

**구현 (GREEN)**
- 폴링 라우팅을 끈 사이클 1개 — 복제본은 띄운 채로 둬 자원 점유를 같게 만든다
- 주문당 상품 3개 사이클 1개 — 재고 마스터는 4로 고정한다. 이 축의 목적이 처리율 비교가 아니라 상품 키가 여러 노드에 걸쳐도 정합이 유지되는지 확인하는 것이라, 노드가 가장 많이 갈린 구성이라야 의미가 있다
- 고지연 벤더 사이클 1개 — 나머지 조건은 전부 고정. 확정 처리율과 함께 미종결 결제 적체를 잰다

**완료 기준**
- 사이클 3개의 결과 파일이 남고 각각 정합 검증 통과
- 라우팅 켬/끔 두 점의 처리율과 지연 차이가 나온다
- 다중 상품 사이클에서 상품별 정합이 전부 맞는다
- 고지연 사이클의 미종결 적체 추이가 기록된다

**완료 결과**
> (execute에서 채움)

---

### Task 17: 측정 리포트 [tdd=false] [domain_risk=false]

설계 근거: "결론 축 — 처리량 / 체감 지연 / 정합성", "메시지 브로커 — 커밋 직렬화가 천장으로 관측되면 그 사실만 기록", 영향 범위의 "신규 (문서) — 측정 리포트".

**구현 (GREEN)**
- `docs/SHARED-RESOURCE-SCALEOUT-REPORT.md` — 직전 측정 리포트(`docs/archive/capacity-and-scaleout/`) 형식을 잇는다
  - 사이클별 조건과 지표 표
  - 축별 해석 — 대수 효과 / 인스턴스 효과 / 읽기 복제의 처리량·지연 교환 / 다중 상품 / 벤더 지연
  - 합격선 판정 — 1 → 2 구간만
  - 해석 범위 명시 — 단일 머신 디스크 공유, 핫 상품 쏠림 미답, CPU 포화 여부, 직전 측정과 부하 프로필이 달라 직접 비교하지 않는다는 점
  - 측정 중 커밋 직렬화가 천장으로 관측됐으면 그 사실만 기록

**완료 기준**
- 사이클 아홉 개(+ 조건부 확인 사이클) 전부가 표에 있다
- 합격선 판정과 해석 범위가 명시돼 있다
- 결론이 처리량·체감 지연·정합성 세 축 모두를 다룬다

**완료 결과**
> (execute에서 채움)

## 리뷰 처리

> (ship 단계에서 채움 — finding별 채택/스킵 + 사유)
