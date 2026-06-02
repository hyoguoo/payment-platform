# review-domain-1

**Topic**: TIME-MODEL-AND-EXPIRY
**Round**: 1
**Persona**: Domain Expert

## Reasoning
돈 앵커(D8 approvedAt offset 정규화)와 멱등 윈도우(D7 raw-JDBC UTC Calendar 바인딩), 만료/복원 가드(D6 READY·IN_PROGRESS 가드 유지), 정합 스캐너 cutoff(executed_at Instant vs Instant) 는 소스 검증 결과 의미 보존이 확인됐다. 그러나 **만료 판정의 입력 컬럼이 `created_at`(BaseEntity LocalDateTime, auditing) 인데 cutoff 만 UTC `Instant` 로 전환**되어, 비-UTC JVM 에서 READY 결제가 JVM offset(예: KST 9시간) 만큼 조기/지연 만료될 수 있다. 토픽이 닫겠다고 한 F1 위험(비-UTC 만료 오판)이 만료 경로에서 실질적으로 열려 있고, 이를 막을 회귀 테스트(`PaymentEventRepositoryImplTest`)는 실제 auditing 경로를 우회하고 JVM TZ 도 고정하지 않아 가드가 되지 못한다. 토픽이 1차 방어로 명시한 컨테이너/JVM TZ=UTC 고정도 diff 에 부재.

## Domain risk checklist
- [yes] paymentKey/orderId/카드번호 plaintext 로그 노출 — 본 작업은 시각 데이터만 다룸. 신규 PII 없음.
- [yes] 보상/취소 멱등성 가드 — 보상 경로(`compensateAtomic` Lua dedup token) 무변경. D7 은 윈도우 시각 소스만 UTC 로 고정.
- [yes] PG "이미 처리됨" 특수응답 정당성 검증 — 본 작업 범위 밖, 무변경.
- [yes] 상태 전이 불변식 — `expire()` READY 가드(NG2), `resetToReady()` IN_PROGRESS 가드, `done()` approvedAt null 가드, terminal 재진입 가드 모두 보존(`PaymentEvent.java` diff 확인). 새 상태값 없음(NG6).
- [partial] race window/격리 — 정합 스캐너 cutoff 는 `executed_at`(Instant) vs `Instant` 로 UTC 일관(F3 닫힘). **만료 cutoff 는 `created_at`(LocalDateTime auditing) vs `Instant` 로 TZ 기반 불일치** — 비-UTC JVM 에서 조기/지연 만료(major-1 아래).
- 추가 점검:
  - [yes] D8 approvedAt 정규화: `parseApprovedAt` `.toInstant()`, `done(Instant)` 까지 타입 강제 전파 — 9시간 오차 구조적 차단.
  - [yes] D7 payment dedupe: `received_at`/`expires_at` 모두 명시 UTC Calendar 바인딩 — connectionTimeZone 누락 환경에서도 방어.
  - [partial] D7 product dedupe: 쓰기(`recordIfAbsent`)는 UTC Calendar 명시, 그러나 `existsValid`/`SQL_DELETE_EXPIRED_BY_UUID` 의 DB `NOW()` 비교는 `connectionTimeZone=UTC` 단일 의존 — default 프로필 URL 에 해당 파라미터 부재(major-2 아래).
  - [yes] D5 스케줄러 fallback 체인: `${payment-expiration:${payment-status-sync:300000}}` — 운영 1시간 오버라이드 보존.

## 도메인 관점 추가 검토
1. **[major] 만료 판정 입력 `created_at` 이 cutoff(Instant)와 TZ 기준 불일치 — 비-UTC JVM 조기/지연 만료**
   - `JpaPaymentEventRepository.findReadyPaymentsOlderThan` (L20) 은 `WHERE created_at < :before` 로 만료 후보를 고른다. `:before` 는 `PaymentLoadUseCase.getReadyPaymentsOlder` 가 `clock.instant().minus(...)` 로 만든 UTC `Instant`(Hibernate `time_zone=UTC` 로 UTC wall-clock 바인딩).
   - `created_at` 은 `BaseEntity`(`core/common/infrastructure/BaseEntity.java:18-20`)의 `@CreatedDate LocalDateTime`. `JpaConfig.java` 에 `@EnableJpaAuditing` 만 있고 `dateTimeProviderRef` 가 없어(grep 0건) Spring 기본 `CurrentDateTimeProvider` 가 **JVM 기본 TZ 의 `LocalDateTime.now()`** 로 채운다. 비-UTC JVM(예: KST) 에서는 KST wall-clock 숫자가 컬럼에 저장된다.
   - 결과: 같은 행에 대해 `created_at`(KST 숫자) vs `:before`(UTC 숫자) 가 JVM offset 만큼 어긋나 `< ` 비교가 9시간 틀어진다 → READY 결제가 임계 도달 전 조기 만료되거나(돈/주문 소실), 임계 초과 후에도 미만료(누적). 토픽이 NG4/R2 로 BaseEntity 를 "관측용, 돈 앵커 아님"으로 이연했으나, **`created_at` 은 만료 결정의 직접 operand** 라 그 전제가 만료 경로에서는 성립하지 않는다. F1(비-UTC 만료 오판)을 닫겠다는 설계 목표(§5 F1)와 실제 코드가 어긋난다.
   - 회귀 가드 부재: `PaymentEventRepositoryImplTest.findReadyPaymentsOlderThan_withInstantCutoff_returnsOnlyOlderPayments`(L41-79)는 `created_at` 을 raw SQL + `LocalDateTime.now(ZoneOffset.UTC)` 로 직접 주입해 **실제 auditing 경로를 우회**하고, JVM TZ 도 비-UTC 로 강제하지 않는다(`setDefault(TimeZone...)` 부재 grep 확인). dedupe round-trip 테스트만 KST 를 강제할 뿐 만료 경로는 비-UTC 검증이 없다.

2. **[major] product `existsValid`/`SQL_DELETE_EXPIRED_BY_UUID` 의 DB `NOW()` 는 connectionTimeZone 단일 의존 — default 프로필 URL 에 파라미터 부재**
   - `JdbcEventDedupeStore.existsValid`(`WHERE ... expires_at >= NOW()`)와 만료 삭제는 DB 세션 `NOW()` 기준이고, 쓰기(`recordIfAbsent`)는 명시 UTC Calendar 기준이다. 둘이 같은 UTC 를 보려면 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` 가 필수다(D7 split-brain 해소 핵심).
   - 그러나 product **default 프로필**(`application.yml:13`)의 datasource URL 에는 해당 파라미터가 없다(docker 프로필만 추가됨). default 프로필이 비-UTC 세션을 타면 `NOW()`(세션 TZ) vs `expires_at`(UTC Calendar 저장) split-brain 이 재발 → 멱등 윈도우의 한 행을 한쪽은 만료/한쪽은 유효로 봐 중복 흡수 실패(재고 이중 차감 가능). payment 는 `received_at`/`expires_at` 양쪽을 UTC Calendar 로 박아 connection 누락에도 방어되지만, product 의 `NOW()` 읽기 경로는 Calendar 로 못 박는 구조라 connection 규약이 유일 방어다.
   - 회귀 테스트(`JdbcEventDedupeStoreRoundTripTest`)는 Testcontainer URL 에 `connectionTimeZone=UTC` 를 항상 박아(L73-74) **파라미터 누락 케이스를 영영 재현하지 못한다** — split-brain 부재 단정이 실제 운영 프로필 구멍을 잡지 못한다.

3. **[minor] 토픽이 F6/F1 1차 방어로 명시한 컨테이너·JVM TZ=UTC 고정이 diff 에 부재**
   - 토픽 §5 F6(a)는 "컨테이너 TZ=UTC / JVM `-Duser.timezone=UTC` 고정"을 가장 떼어내기 쉬운 1차 방어로 제시한다. 그러나 `docker/docker-compose*.yml` 에 `TZ=` 부재, `build.gradle` test JVM args 에 `-Duser.timezone` 부재(grep 확인). 현재는 베이스 이미지/CI 의 암묵 UTC 기본값에만 의존한다. finding 1·2 의 실질 폭발 반경이 이 암묵 가정에 묶여 있어, 비-UTC 환경 배포 시 만료·멱등 동시 오판 가능. 명시 게이트화 권고.

4. **[minor] AC9 단위 테스트가 production `parseApprovedAt` 를 호출하지 않음 — 동어반복 가드**
   - `PaymentConfirmResultUseCaseApprovedAtTest`(L31-104)는 `OffsetDateTime.parse(...).toInstant()` 표현을 인라인 재구성해 JDK 동작만 단정한다. production `parseApprovedAt`(private)이 실제로 `.toInstant()` 를 쓰는지는 컴파일러(타입 `Instant` 전파)가 강제하므로 돈 사고로 번지진 않으나, "approvedAt 경로 회귀 가드"라는 명목상 효력은 약하다. 통합/패키지-프라이빗 호출 단정 권고(비차단).

5. **[확인, 차단 아님] pg Toss/NicePay fallback now() clock 정렬 + raw 문자열 보존** — Toss `LocalDateTime.ofInstant(clock.instant(), UTC)`, NicePay `OffsetDateTime.now(clock)` 로 정렬됐고, 정상 경로의 `approvedAtRaw`(원본 offset 문자열)는 무변환 보존돼 pg→payment contract 무변경(F6). 파싱 실패 예외 경로라 정산 앵커 영향 없음.

## Findings
- **major** — 만료 판정 입력 `created_at`(LocalDateTime auditing, JVM TZ) vs cutoff `Instant`(UTC) TZ 불일치. 비-UTC JVM 에서 READY 결제 조기/지연 만료. 토픽 F1 목표 미달. (검토 1)
- **major** — product `existsValid`/만료삭제 `NOW()` 는 connectionTimeZone 단일 의존인데 default 프로필 URL 에 부재. split-brain 재발 + 멱등 윈도우 오염 가능. 회귀 테스트가 누락 케이스 미재현. (검토 2)
- **minor** — 컨테이너/JVM TZ=UTC 고정(토픽 F6 1차 방어)이 diff 에 부재. (검토 3)
- **minor** — AC9 테스트가 production parseApprovedAt 미호출(동어반복). (검토 4)

## JSON
```json
{
  "stage": "review",
  "persona": "domain-expert",
  "round": 1,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "D8 돈 앵커·D7 payment dedupe·D6 상태 가드·정합 스캐너 cutoff 는 의미 보존 확인. 그러나 만료 판정 operand인 created_at(LocalDateTime auditing, JVM TZ)이 cutoff Instant(UTC)와 TZ 기준 불일치해 비-UTC JVM 조기/지연 만료(major), product NOW() 만료비교가 default 프로필 connectionTimeZone 부재로 split-brain 재발 가능(major). 둘 다 회귀 테스트가 실제 경로/누락 케이스를 재현하지 못함. critical 없음 → revise.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "race window가 있는 경로에 락/트랜잭션 격리 + 시각 비교 결정성 고려됨",
        "status": "no",
        "evidence": "findReadyPaymentsOlderThan(JpaPaymentEventRepository.java:20) created_at < :before 에서 created_at은 BaseEntity LocalDateTime(JpaConfig.java @EnableJpaAuditing dateTimeProviderRef 부재 → JVM TZ now), :before는 UTC Instant. 비-UTC JVM에서 만료 경계가 offset만큼 어긋남. PaymentEventRepositoryImplTest.java:41-79 가 auditing 우회+JVM TZ 미고정."
      },
      {
        "section": "domain risk",
        "item": "멱등성 윈도우(dedupe TTL) 시각 비교가 단일 UTC 기준으로 일관(NG3)",
        "status": "no",
        "evidence": "product JdbcEventDedupeStore.existsValid/SQL_DELETE_EXPIRED_BY_UUID 가 DB NOW() 기준, recordIfAbsent 는 UTC Calendar 기준. connectionTimeZone=UTC 단일 의존인데 product application.yml:13 default 프로필 URL 에 해당 파라미터 부재(docker 프로필만 추가). 비-UTC 세션 시 split-brain 재발. RoundTripTest L73-74 가 컨테이너 URL에 항상 파라미터 박아 누락 케이스 미재현."
      },
      {
        "section": "domain risk",
        "item": "벤더 승인 시각(approvedAt) offset 보존 절대시점 정규화(D8)",
        "status": "yes",
        "evidence": "PaymentConfirmResultUseCase.parseApprovedAt OffsetDateTime.parse().toInstant(), handleApproved Instant 전파, done(Instant) 타입 강제. pg raw 문자열 contract 보존."
      },
      {
        "section": "domain risk",
        "item": "상태 전이 불변식 보존 — expire() READY 가드, terminal 재진입 가드(NG2/NG6)",
        "status": "yes",
        "evidence": "PaymentEvent.java expire()/resetToReady()/done()/quarantine() 가드 무변경, enum 불변, 정합 스캐너 executedAt(Instant) vs Instant UTC 일관(F3 닫힘)."
      },
      {
        "section": "domain risk",
        "item": "혼재 배포/비-UTC 환경 1차 방어(컨테이너·JVM TZ=UTC) 명시",
        "status": "no",
        "evidence": "토픽 §5 F6(a) 가 1차 방어로 명시했으나 docker-compose TZ= 부재, build.gradle -Duser.timezone 부재. 암묵 베이스이미지 UTC 기본값 의존."
      }
    ],
    "total": 5,
    "passed": 2,
    "failed": 3,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.7,
    "conventions": 0.88,
    "discipline": 0.85,
    "test-coverage": 0.68,
    "domain": 0.66,
    "mean": 0.754
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "race window가 있는 경로에 시각 비교 결정성 고려됨",
      "location": "payment-service/.../infrastructure/repository/JpaPaymentEventRepository.java:20 + core/common/infrastructure/BaseEntity.java:18-20 + core/config/JpaConfig.java",
      "problem": "만료 후보 조회가 created_at < :before 인데 created_at 은 BaseEntity LocalDateTime(auditing, JVM 기본 TZ로 채워짐 — dateTimeProviderRef 부재)이고 :before 는 UTC Instant cutoff. 비-UTC JVM에서 두 operand가 JVM offset(예: KST 9h)만큼 다른 TZ 기준이라 READY 결제가 임계 전 조기 만료(주문 소실) 또는 임계 후 미만료(누적). 토픽이 닫겠다고 한 F1(비-UTC 만료 오판)이 만료 경로에서 미해소.",
      "evidence": "JpaConfig.java @EnableJpaAuditing 만 있고 dateTimeProviderRef grep 0건 → Spring 기본 CurrentDateTimeProvider 가 JVM TZ LocalDateTime.now() 사용. cutoff 는 PaymentLoadUseCase.getReadyPaymentsOlder clock.instant().minus(...) UTC. PaymentEventRepositoryImplTest.java:41-79 는 raw SQL+LocalDateTime.now(UTC) 로 auditing 우회, JVM TZ 비-UTC 미강제(setDefault 부재).",
      "suggestion": "만료 판정 operand 를 Instant 컬럼으로 통일(예: executed_at 처럼 created_at 도 Instant 매핑하거나, 만료 기준 시각을 Instant 전용 컬럼으로 분리). 최소한 AuditingDateTimeProvider 를 UTC 고정(dateTimeProviderRef 빈 등록)하고, PaymentEventRepositoryImplTest 에 -Duser.timezone=Asia/Seoul 강제 + 실제 save(auditing) 경로로 만료 경계 round-trip 단정을 추가해 회귀 가드화."
    },
    {
      "severity": "major",
      "checklist_item": "멱등성 윈도우 시각 비교가 단일 UTC 기준으로 일관(NG3)",
      "location": "product-service/.../infrastructure/idempotency/JdbcEventDedupeStore.java (SQL_EXISTS_VALID NOW(), SQL_DELETE_EXPIRED_BY_UUID) + product application.yml:13",
      "problem": "existsValid/만료삭제는 DB NOW()(세션 TZ) 기준, recordIfAbsent 는 명시 UTC Calendar 기준. 두 기준 정합은 connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true 에 단일 의존인데 default 프로필 datasource URL 에는 해당 파라미터가 없다(docker 프로필만 추가). 비-UTC 세션이면 split-brain 재발 → 멱등 행을 한쪽은 만료/한쪽은 유효로 봐 stock-committed 재배달 중복 흡수 실패(재고 이중 차감 가능, PITFALLS §9/§16).",
      "evidence": "product application.yml:13 url 에 connectionTimeZone 부재(docker yml 만 추가됨, diff 확인). JdbcEventDedupeStoreRoundTripTest 컨테이너 L73-74 가 connectionTimeZone=UTC 를 항상 박아 누락 케이스 영구 미재현. payment 측은 received_at/expires_at 둘 다 UTC Calendar 로 박아 connection 누락에도 방어되나 product NOW() 읽기는 Calendar 불가.",
      "suggestion": "product default 프로필(application.yml) 및 모든 프로필 datasource URL 에 connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true 일괄 적용(payment docker 와 정합). 또는 NOW() 비교를 앱 주입 Instant 파라미터 비교로 통일(토픽 D7 더 결정적 옵션). 회귀 테스트는 파라미터 미설정 케이스에서 split-brain 이 노출되도록 별도 케이스 추가."
    },
    {
      "severity": "minor",
      "checklist_item": "혼재 배포/비-UTC 1차 방어 명시",
      "location": "docker/docker-compose*.yml, build.gradle, payment-service/build.gradle",
      "problem": "토픽 §5 F6(a)가 컨테이너/JVM TZ=UTC 고정을 1차 방어로 명시했으나 diff 에 TZ 환경변수·-Duser.timezone 설정이 없다. major-1/2 의 폭발 반경이 베이스 이미지의 암묵 UTC 기본값에만 묶여 있다.",
      "evidence": "docker-compose TZ= grep 0건, build.gradle user.timezone grep 0건.",
      "suggestion": "컨테이너 환경(TZ=UTC) 또는 JVM(-Duser.timezone=UTC)을 명시 고정하고, 운영 배포 체크리스트/PR 본문에 비-UTC 금지 게이트로 기록."
    },
    {
      "severity": "minor",
      "checklist_item": "approvedAt 경로 회귀 가드 실효성",
      "location": "payment-service/src/test/.../PaymentConfirmResultUseCaseApprovedAtTest.java:31-104",
      "problem": "AC9 단위 테스트가 production parseApprovedAt(private)을 호출하지 않고 OffsetDateTime.parse().toInstant() 표현을 인라인 재구성해 JDK 동작만 단정. 회귀 가드 명목 효력 약함(돈 사고로는 미연결 — 타입 전파로 컴파일러가 강제).",
      "evidence": "테스트 본문이 OffsetDateTime.parse(kstApprovedAt).toInstant() 를 직접 계산. parseApprovedAt 호출 없음.",
      "suggestion": "parseApprovedAt 을 package-private 로 노출하거나 handle()/handleApproved() 통합 단정으로 KST approvedAtRaw → done(Instant) 절대시점을 검증."
    }
  ],

  "previous_round_ref": null,
  "delta": null,

  "unstuck_suggestion": null
}
```
