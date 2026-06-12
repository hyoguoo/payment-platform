# review-domain-2

**Topic**: TIME-MODEL-AND-EXPIRY
**Round**: 2
**Persona**: Domain Expert

## Reasoning
1라운드 major 2건의 **운영 리스크(돈/정합)는 둘 다 실질 해소**됐다. DM1 은 `JpaConfig.clockDateTimeProvider`(Clock.systemUTC) 가 `created_at` 을 JVM TZ 무관 UTC wall-clock 으로 쓰고, 읽기 측 `:before(Instant)` 는 `hibernate.jdbc.time_zone=UTC`+`connectionTimeZone=UTC` 로 바인딩되어 두 operand 가 동일 UTC 기준이 됐다 — 비-UTC JVM 조기/지연 만료가 구조적으로 차단된다. DM2 는 product default 프로필 URL 에 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` 가 추가돼 `NOW()` 읽기와 UTC Calendar 쓰기가 default/docker 양쪽에서 같은 UTC 세션을 본다. 다만 **DM1 회귀 가드가 후퇴**했다: RED 에서 KST 를 강제하던 `auditing_nonUtcJvm_createdAtIsUtcBased` 가 GREEN 에서 UTC-JVM 전용 `auditing_createdAt_isFilledByClockDateTimeProvider` 로 교체돼, CI(UTC JVM)에서는 `dateTimeProviderRef` 누락 회귀를 탐지하지 못한다(기본 CurrentDateTimeProvider 도 UTC JVM 에선 동일 값 산출). 운영 정합은 닫혔으나 가드 효력이 약화된 test-coverage 결함 1건(major)이 남는다.

## Domain risk checklist
- [yes] paymentKey/orderId/카드번호 plaintext 로그 노출 — 시각/설정 변경만. 신규 PII 없음.
- [yes] 보상/취소 멱등성 가드 — 무변경. DM2 는 product dedupe 윈도우 시각 기준만 UTC 로 통일.
- [yes] PG "이미 처리됨" 특수응답 정당성 — 범위 밖, 무변경.
- [yes] 상태 전이 불변식 — `expire()` READY 가드, terminal 재진입 가드, enum 불변 모두 무변경(NG6). DM1 은 auditing 공급원만 교체.
- [yes] race window/시각 비교 결정성 (만료 경로) — DM1 으로 `created_at`(UTC wall-clock) vs `:before`(UTC Instant) 동일 기준 확보. 비-UTC JVM 조기/지연 만료 차단(F1 닫힘). `executed_at` 기반 정합 스캐너/Reconciler 쿼리는 이미 Instant vs Instant 라 영향 없음.
- [yes] 멱등성 윈도우(dedupe TTL) UTC 일관 (DM2) — product `existsValid`/`SQL_DELETE_EXPIRED_BY_UUID` 의 `NOW()` 와 `recordIfAbsent` UTC Calendar 쓰기가 default/docker 양쪽 동일 UTC 세션. NG3(TTL P8D 의미) 보존.
- 추가 점검:
  - [yes] DM1 side-effect 검토: `created_at` UTC화가 다른 시각 의존 로직에 부작용 없음 — `PaymentEventEntity.toDomain` 이 `createdAt.toInstant(UTC)` 로 변환(쓰기 기준과 일관), `updated_at`/`deleted_at` 도 동일 provider 라 created_at 표시·정렬 일관. 별도 만료/정리 쿼리(`executed_at`)는 이미 Instant.
  - [yes] NG4(BaseEntity 컬럼 미변경) — `BaseEntity.createdAt` 여전히 `LocalDateTime @Column datetime`. 공급원만 Clock 기반으로 교체.
  - [no] DM1 회귀 가드 효력 — KST 강제 RED 테스트 제거, UTC-JVM 전용 단정으로 후퇴. `dateTimeProviderRef` 누락 회귀를 CI 에서 미탐지(아래 검토 1).
  - [yes] DM2 회귀 가드 효력 — `JdbcEventDedupeStoreRoundTripTest` AC8 이 `TimeZone.setDefault(Asia/Seoul)` 강제 + `existsValid` NOW() split-brain 단정(L147-167) 보유. DM1 과 달리 비-UTC 경로가 실제 검증됨.

## 도메인 관점 추가 검토
1. **[major] DM1 회귀 가드 후퇴 — KST 강제 RED 제거, UTC-JVM 전용 GREEN 으로 교체돼 `dateTimeProviderRef` 누락 회귀 미탐지**
   - 1라운드 후속 RED 커밋 `0458262f` 는 `auditing_nonUtcJvm_createdAtIsUtcBased` 에서 `TimeZone.setDefault("Asia/Seoul")` 로 비-UTC JVM 을 강제하고 DB `created_at` 을 직접 읽어 UTC 범위 단정 → 회귀를 실제로 재현했다.
   - GREEN 커밋 `54a6d947` 는 이 테스트의 KST 강제(`@BeforeEach`/`@AfterEach` TimeZone 저장·복원 + `setDefault`)를 **제거**하고 `auditing_createdAt_isFilledByClockDateTimeProvider`(UTC-JVM 전용)로 교체했다(`PaymentEventRepositoryImplTest.java:99-140`). 이제 ambient JVM TZ(CI=UTC)에서만 돈다.
   - 결과: UTC JVM 에서는 `@EnableJpaAuditing(dateTimeProviderRef=...)` 를 떼어내도 Spring 기본 `CurrentDateTimeProvider`(`LocalDateTime.now()` = UTC wall-clock)가 동일 값을 산출하므로 **테스트가 그대로 통과** → 가드가 회귀를 못 잡는다. 호출자가 명시 요구한 "회귀 가드가 `dateTimeProviderRef` 누락을 잡는지"는 **미충족**.
   - Implementer 절충(비-UTC JVM 단정이 Hibernate LocalDateTime 바인딩 + connectionTimeZone 상호작용으로 불안정)은 **운영 정합 자체에는 영향 없음**(쓰기·읽기 모두 UTC 기준이라 비-UTC JVM 에서도 결과 정합) — 하지만 그 불안정성 회피가 곧 "비-UTC 가드 부재"를 의미한다. 비-UTC 운영 보호를 컨테이너 TZ=UTC(F6)에 의존한다면, 그 F6 게이트가 diff/배포 산출물에 명시돼야 가드 공백이 메워진다(아래 검토 3 과 연동).
   - 처방: (a) 도메인 단정을 JVM TZ 가 아니라 **provider 자체 동작**으로 옮긴다 — `clockDateTimeProvider()` 빈을 직접 호출해 `Clock.fixed(KST-offset 무관 instant)` 주입 시 반환 LocalDateTime 이 UTC wall-clock 인지 단정(컨테이너/Hibernate 바인딩 비의존, 안정적). 또는 (b) `JpaConfig` 의 `dateTimeProviderRef` 가 실제로 `clockDateTimeProvider` 로 연결됐는지 ApplicationContext 빈 단정으로 가드.

2. **[minor] product NOW() 읽기 경로는 여전히 connectionTimeZone 단일 의존 — Calendar 로 못 박을 수 없는 구조적 약점(해소됐으나 잔존 가정)**
   - DM2 fix 로 default/docker 양 프로필 URL 에 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` 가 적용돼 현 시점 split-brain 은 닫혔다(검증 완료). 다만 `existsValid`(`expires_at >= NOW()`)·`SQL_DELETE_EXPIRED_BY_UUID`(`expires_at < NOW()`)는 DB 세션 `NOW()` 기준이라, payment 처럼 양쪽을 UTC Calendar 로 박는 방어가 불가능하다. 즉 멱등 윈도우 정합이 **connection 규약 1줄에 단일 의존**한다.
   - 폭발 반경: 운영자가 URL 파라미터를 누락/오버라이드(`PRODUCT_DATASOURCE_URL` env)하면 회귀가 즉시 재발하나 테스트는 컨테이너 URL 에 항상 param 을 박아 누락 케이스를 재현하지 못한다. PITFALLS §9/§16 의 재고 이중 차감 위험과 연결. 본 토픽에서 PLAN 이 "앱 주입 Instant 비교로 통일"을 D7 이연 항목으로 명시했으므로 비차단. 운영 게이트(URL 파라미터 불변)로 기록 권고.

3. **[minor] 컨테이너/JVM TZ=UTC 1차 방어(F6)가 여전히 diff 에 부재 — DM1 가드 공백의 backstop 미명시**
   - 검토 1 에서 DM1 의 비-UTC 보호가 "코드 정합 + 컨테이너 TZ=UTC" 2중 구조인데, 회귀 테스트가 비-UTC 를 더 이상 검증하지 않으므로 F6(컨테이너 TZ=UTC / `-Duser.timezone=UTC`) 명시가 backstop 으로서 중요해졌다. 그러나 `docker-compose*.yml` TZ= 부재, `build.gradle` `-Duser.timezone` 부재(grep 0건, 1라운드와 동일). 암묵 베이스 이미지 UTC 기본값 의존 지속. 1라운드 minor 유지(회귀 아님).

4. **[확인, 차단 아님] DM1 운영 정합은 실제 닫힘** — `clockDateTimeProvider` = `LocalDateTime.ofInstant(clock.instant(), UTC)`, Clock 빈 = `Clock.systemUTC()`(`ClockConfig`). 쓰기 UTC wall-clock + 읽기 `Instant` UTC 바인딩(`time_zone=UTC`+`connectionTimeZone=UTC`) 으로 비-UTC JVM 에서도 만료 경계가 정확. `PaymentEventEntity.toDomain` 의 `createdAt.toInstant(UTC)` 도 쓰기 기준과 일관. F1(비-UTC 만료 오판) 구조적 차단 확인. 부작용 없음(NG3/NG4 보존).

## Findings
- **major** — DM1 회귀 가드 후퇴: KST 강제 RED(`auditing_nonUtcJvm_createdAtIsUtcBased`)가 UTC-JVM 전용 GREEN(`auditing_createdAt_isFilledByClockDateTimeProvider`)으로 교체돼 `dateTimeProviderRef` 누락 회귀를 CI(UTC JVM)에서 미탐지. 운영 정합은 닫혔으나 가드 효력 미충족. (검토 1)
- **minor** — product NOW() 읽기 경로가 connectionTimeZone 단일 의존(DM2 로 현 시점 닫혔으나 URL 파라미터 1줄 의존, 누락 케이스 미재현). D7 이연. (검토 2)
- **minor** — 컨테이너/JVM TZ=UTC(F6) 1차 방어 diff 부재 — DM1 가드 공백의 backstop 미명시. (검토 3)

## JSON
```json
{
  "stage": "review",
  "persona": "domain-expert",
  "round": 2,
  "task_id": null,

  "decision": "revise",
  "reason_summary": "1라운드 major 2건의 운영 리스크(돈/정합)는 모두 실질 해소 — DM1 clockDateTimeProvider(UTC)+읽기 Instant UTC 바인딩으로 비-UTC JVM 만료 오판 구조적 차단, DM2 default 프로필 connectionTimeZone=UTC 추가로 NOW() 읽기/UTC Calendar 쓰기 동일 세션. 그러나 DM1 회귀 가드가 KST 강제 RED에서 UTC-JVM 전용 GREEN으로 후퇴해 dateTimeProviderRef 누락 회귀를 CI에서 미탐지(major, test-coverage). critical 없음 → revise.",

  "checklist": {
    "source": "_shared/checklists/code-ready.md",
    "items": [
      {
        "section": "domain risk",
        "item": "race window가 있는 경로(만료)에 시각 비교 결정성 — created_at vs cutoff 단일 UTC 기준",
        "status": "yes",
        "evidence": "JpaConfig.java:47-49 clockDateTimeProvider = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), @EnableJpaAuditing(dateTimeProviderRef=clockDateTimeProvider). Clock 빈 = ClockConfig Clock.systemUTC(). 읽기 JpaPaymentEventRepository.java:20 nativeQuery :before(Instant) under application.yml:41 hibernate.jdbc.time_zone=UTC + BaseIntegrationTest connectionTimeZone=UTC. 두 operand UTC wall-clock 일치 → 비-UTC JVM 조기/지연 만료 차단. PaymentEventEntity.java:119-120 toDomain createdAt.toInstant(UTC) 쓰기 기준 일관."
      },
      {
        "section": "domain risk",
        "item": "멱등성 윈도우(dedupe TTL) NOW() 읽기/UTC Calendar 쓰기 단일 UTC 기준(NG3, DM2)",
        "status": "yes",
        "evidence": "product application.yml:16 default 프로필 URL 에 connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true 추가(application-docker.yml:9 와 동일 규약). JdbcEventDedupeStore.java:47/53 existsValid·SQL_DELETE_EXPIRED_BY_UUID NOW() 와 L107 recordIfAbsent UTC_CALENDAR 쓰기가 동일 UTC 세션. JdbcEventDedupeStoreRoundTripTest.java:105-107 KST 강제 + L147-167 existsValid NOW() split-brain 단정으로 비-UTC 경로 검증."
      },
      {
        "section": "domain risk",
        "item": "상태 전이 불변식 보존 — expire() READY 가드, terminal 재진입 가드(NG2/NG6)",
        "status": "yes",
        "evidence": "PaymentEvent 상태 가드·enum 무변경(DM1/DM2 는 auditing 공급원·datasource 설정만 변경). BaseEntity.createdAt LocalDateTime @Column datetime 유지(NG4)."
      },
      {
        "section": "domain risk",
        "item": "만료 경로 회귀 가드가 dateTimeProviderRef 누락을 탐지",
        "status": "no",
        "evidence": "RED 0458262f 의 KST 강제 auditing_nonUtcJvm_createdAtIsUtcBased 가 GREEN 54a6d947 에서 UTC-JVM 전용 auditing_createdAt_isFilledByClockDateTimeProvider 로 교체(PaymentEventRepositoryImplTest.java:99-140, TimeZone.setDefault 제거). UTC JVM 에서는 기본 CurrentDateTimeProvider 도 동일 UTC wall-clock 산출 → dateTimeProviderRef 제거해도 테스트 통과 → 회귀 미탐지."
      },
      {
        "section": "domain risk",
        "item": "혼재 배포/비-UTC 환경 1차 방어(컨테이너·JVM TZ=UTC) 명시",
        "status": "no",
        "evidence": "docker-compose TZ= 부재, build.gradle -Duser.timezone 부재(grep 0건). DM1 회귀 가드가 비-UTC 미검증으로 후퇴한 상황에서 F6 backstop 명시가 더 중요해졌으나 여전히 암묵 UTC 의존."
      }
    ],
    "total": 5,
    "passed": 3,
    "failed": 2,
    "not_applicable": 0
  },

  "scores": {
    "correctness": 0.92,
    "conventions": 0.9,
    "discipline": 0.85,
    "test-coverage": 0.72,
    "domain": 0.84,
    "mean": 0.846
  },

  "findings": [
    {
      "severity": "major",
      "checklist_item": "만료 경로 회귀 가드가 dateTimeProviderRef 누락을 탐지",
      "location": "payment-service/src/test/java/com/hyoguoo/paymentplatform/payment/infrastructure/PaymentEventRepositoryImplTest.java:99-140 (vs RED 0458262f auditing_nonUtcJvm_createdAtIsUtcBased)",
      "problem": "DM1 운영 정합(비-UTC JVM 만료 오판 차단)은 clockDateTimeProvider+읽기 Instant UTC 바인딩으로 실제 닫혔으나, 회귀 가드가 후퇴했다. RED 에서 TimeZone.setDefault(Asia/Seoul) 로 비-UTC JVM 을 강제하던 테스트가 GREEN 에서 UTC-JVM 전용 단정으로 교체됨. CI(UTC JVM)에서는 @EnableJpaAuditing 의 dateTimeProviderRef 를 떼어내도 Spring 기본 CurrentDateTimeProvider 가 동일 UTC wall-clock 을 산출하므로 테스트가 통과 → dateTimeProviderRef 누락 회귀를 탐지하지 못한다. 호출자가 명시 요구한 '회귀 가드가 dateTimeProviderRef 누락을 잡는지'가 미충족.",
      "evidence": "git show 54a6d947 -- PaymentEventRepositoryImplTest.java: @BeforeEach/@AfterEach TimeZone 저장·복원 + setDefault(Asia/Seoul) 제거, DisplayName 'DM1 회귀 — 비-UTC JVM TZ...' → 'Clock 기반 DateTimeProvider...' 로 변경, DB 직접조회 UTC 범위 단정 → findById(loaded).getCreatedAt() 범위 단정으로 교체. 현재 테스트는 ambient JVM TZ 의존. Implementer 보고: 비-UTC JVM 단정이 Hibernate LocalDateTime 바인딩+connectionTimeZone 상호작용으로 불안정 → UTC JVM 검증으로 절충, 비-UTC 보호는 F6(컨테이너 TZ=UTC) 의존.",
      "suggestion": "가드를 JVM TZ 변조가 아니라 provider 동작에 직접 묶는다: (a) clockDateTimeProvider() 빈을 Clock.fixed(임의 instant) 로 단위 테스트해 반환 LocalDateTime 이 ZoneOffset.UTC wall-clock 과 동치인지 단정(컨테이너/Hibernate 바인딩 비의존, 안정적), 또는 (b) ApplicationContext 에서 @EnableJpaAuditing 의 dateTimeProviderRef 가 clockDateTimeProvider 빈으로 연결됐는지 단정. 둘 중 하나로 누락 회귀를 결정적으로 탐지."
    },
    {
      "severity": "minor",
      "checklist_item": "멱등성 윈도우 NOW() 읽기 경로 방어 구조",
      "location": "product-service/.../infrastructure/idempotency/JdbcEventDedupeStore.java:47,53 + product application.yml:16",
      "problem": "DM2 fix 로 default/docker 양 프로필 URL 에 connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true 가 적용돼 현 시점 split-brain 은 닫혔으나, existsValid/SQL_DELETE_EXPIRED_BY_UUID 는 DB NOW() 기준이라 payment 처럼 UTC Calendar 로 못 박을 수 없어 멱등 윈도우 정합이 connection 규약 1줄에 단일 의존한다. PRODUCT_DATASOURCE_URL env 오버라이드로 파라미터 누락 시 회귀 즉시 재발하나 테스트는 컨테이너 URL 에 항상 param 을 박아 누락 케이스 미재현.",
      "evidence": "JdbcEventDedupeStore.java:81-84 existsValid 가 SQL_EXISTS_VALID(NOW()) 사용, recordIfAbsent 만 UTC_CALENDAR(L107). PLAN 이 '앱 주입 Instant 비교 통일'을 D7 이연 항목으로 명시(TIME-MODEL-AND-EXPIRY-PLAN.md:81).",
      "suggestion": "D7 후속에서 NOW() 비교를 앱 주입 Instant 파라미터(deleteExpired 처럼) 로 통일하거나, 운영 배포 게이트에 product datasource URL 의 connectionTimeZone=UTC 불변을 명시. 비차단(본 토픽 이연 범위)."
    },
    {
      "severity": "minor",
      "checklist_item": "혼재 배포/비-UTC 1차 방어(F6) 명시",
      "location": "docker/docker-compose*.yml, build.gradle, payment-service/build.gradle",
      "problem": "DM1 회귀 가드가 비-UTC 를 더 이상 검증하지 않게 되면서 F6(컨테이너 TZ=UTC / -Duser.timezone=UTC) backstop 명시 중요성이 커졌으나 여전히 diff 부재. 암묵 베이스 이미지 UTC 기본값 의존.",
      "evidence": "docker-compose TZ= grep 0건, build.gradle user.timezone grep 0건(1라운드와 동일, 회귀 아님).",
      "suggestion": "컨테이너 TZ=UTC 또는 JVM -Duser.timezone=UTC 명시 고정 + 운영 배포 체크리스트/PR 본문에 비-UTC 금지 게이트 기록."
    }
  ],

  "previous_round_ref": "review-domain-1.md",
  "delta": {
    "newly_passed": [
      "race window가 있는 경로(만료)에 시각 비교 결정성 — created_at vs cutoff 단일 UTC 기준",
      "멱등성 윈도우(dedupe TTL) NOW() 읽기/UTC Calendar 쓰기 단일 UTC 기준(NG3, DM2)"
    ],
    "newly_failed": [
      "만료 경로 회귀 가드가 dateTimeProviderRef 누락을 탐지"
    ],
    "still_failing": [
      "혼재 배포/비-UTC 환경 1차 방어(컨테이너·JVM TZ=UTC) 명시"
    ]
  },

  "unstuck_suggestion": null
}
```
