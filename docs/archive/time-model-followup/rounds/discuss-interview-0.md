# discuss-interview-0 — TIME-MODEL-FOLLOWUP

> Round 0 (Interviewer). 사용자 사전 확정 5건은 RESOLVED 고정. 잔여 모호점만 4트랙 ledger 화.
> 환경 주의: 이 워크스페이스에 `AskUserQuestion` 툴 미제공 → Path 2 질문은 채팅 본문으로 직접 제기.

## 코드 사실 검증 (Round 0 직접 조사)

- **existsValid 라이브 호출 0건 확인.** main 참조는 포트(`EventDedupeStore`)·구현(`JdbcEventDedupeStore`)뿐, 호출처는 전부 test
  (`FakeEventDedupeStore` / `EventDedupeStoreContractTest` / `JdbcEventDedupeStoreRoundTripTest` AC8 / `JdbcEventDedupeStoreCleanupTest`).
  → 결정 2(제거) 안전. StockCommitUseCase·DedupeCleanupWorker는 existsValid 미참조.
- **BaseEntity는 payment-service 단독.** 서브클래스 4종: `PaymentEventEntity` / `PaymentOutboxEntity` / `PaymentHistoryEntity` / `PaymentOrderEntity`.
  pg/user는 자체 도메인이 `Instant` 보유(BaseEntity 무관), product는 auditing superclass 없음. → 일원화 추가 대상 없음.
- **admin DTO 응답 타입은 이미 `Instant`.** `PaymentEventResult.createdAt`·`PaymentEventResponse.createdAt` 둘 다 `Instant`.
  현재 `PaymentEventEntity.toResult()`가 `getCreatedAt().toInstant(UTC)`로 메워줌. BaseEntity→Instant 전환은 이 변환을 **제거**할 뿐, presentation/외부 API 응답 타입은 불변.
  → "외부 API 계약 변경" 우려는 해소(RESOLVED).
- **main 연쇄 사용처(타입 전환 시 동반 수정):**
  - `PaymentEventEntity.toResult()` — `getCreatedAt().toInstant(UTC)` 제거.
  - `PaymentOutboxEntity.toDomain()` — `toInstant(getCreatedAt())` / `toInstant(getUpdatedAt())` 헬퍼 제거.
  - `OutboxPendingAgeMetrics` — `getCreatedAt()` null 체크 + `ChronoUnit.SECONDS.between(getCreatedAt(), now)`. now 타입과 정합 필요.
  - test 사용처: FakePaymentEventRepository(2곳), PaymentEventRepositoryImplTest, OutboxPendingAgeMetricsTest, PaymentOutboxMetricsTest, PaymentOutboxInstantTest.
- **hibernate.jdbc.time_zone=UTC**: payment `application.yml`·`application-docker.yml`에 **이미 설정됨**. product엔 없음(JdbcTemplate raw 경로라 무관, NOW 통일로 무의존화).
- **Flyway**: payment-service에 마이그레이션 디렉토리 존재(V1~V3). audit 컬럼 DDL = `DATETIME`(서브초 없음),
  도메인 시각 컬럼(executed_at/available_at 등)은 `DATETIME(6)`. → 전환 시 정밀도 정책이 plan 영역 OPEN.
- **Dockerfile 6개**: 모두 `ENV TZ` 미지정. compose `docker-compose.apps.yml`에 `environment:` 블록 존재(현재 TZ 키 없음).

## Ambiguity Ledger (4트랙)

### Track 1 — Scope

| ID | 상태 | 내용 |
|----|------|------|
| S1 | RESOLVED | 세 항목(NOW 통일 / TZ backstop / BaseEntity Instant) 한 PR. (사용자 확정) |
| S2 | RESOLVED | NOW 통일 대상 = product `JdbcEventDedupeStore`의 라이브 SQL `SQL_DELETE_EXPIRED_BY_UUID`(`recordIfAbsent`). payment 측은 NOW 미사용으로 비대상. (사용자 확정 + 코드 검증) |
| S3 | RESOLVED | existsValid 제거 = 포트+구현+Fake+Contract+RoundTrip(AC8)+Cleanup 검증까지. 라이브 0건 코드 검증 완료. (사용자 확정 + 검증) |
| S4 | RESOLVED | BaseEntity Instant 전환 = payment 단독, 서브클래스 4종. 타 서비스 일원화 대상 없음. (코드 검증) |
| S5 | RESOLVED | admin/presentation 응답 타입 변경 없음 — 이미 Instant. 동반 수정은 엔티티 매핑의 toInstant 변환 제거 + metrics 정합으로 한정. (코드 검증) |
| S6 | RESOLVED | TZ backstop = **3겹 전부** — Dockerfile `ENV TZ=UTC` + JVM `-Duser.timezone=UTC` + compose `environment.TZ`. (사용자 확정) |

### Track 2 — Constraints

| ID | 상태 | 내용 |
|----|------|------|
| C1 | RESOLVED | 만료 정합은 이미 UTC. TZ backstop은 정합 변경 아님 — 표현/로그 일관 + 혼재 배포 방어 목적. 회귀 위험 낮음. (사용자 가정) |
| C2 | RESOLVED | 학습 프로젝트 — 운영 데이터 마이그레이션 비용 없음. (사용자 가정, 직전 R1 동일 전제) |
| C3 | RESOLVED | BaseEntity audit 컬럼 = **`DATETIME(6)` 승급** — 도메인 시각 컬럼과 동일 마이크로초 정밀도로 통일, Flyway DDL 추가. 서브초 절삭 미허용. (사용자 확정) |
| C4 | RESOLVED | minimal-change 원칙(CLAUDE.md): 태스크 범위 밖 코드 미수정. existsValid 제거가 contract/round-trip test를 건드리는 건 범위 내(NOW 의존 축소 직접 종속). |

### Track 3 — Outputs

| ID | 상태 | 내용 |
|----|------|------|
| O1 | RESOLVED | 산출물 = 단일 PR. 커밋은 TDD 흐름(test RED → feat GREEN) per 항목, PLAN.md/STATE.md 동반. (워크플로우 규약) |
| O2 | RESOLVED | 포트 시그니처 변경: product `EventDedupeStore`에서 existsValid 삭제, recordIfAbsent는 이미 `Instant now` 보유 → DELETE SQL이 NOW() 대신 주입 now 사용하도록 구현부만 수정. 포트 시그니처 추가 변경 불필요. (코드 검증) |
| O3 | RESOLVED | product `connectionTimeZone=UTC` = **유지** — raw-JDBC `Timestamp` 바인딩 안전망(backstop 성격)으로 남긴다. (사용자 확정) |

### Track 4 — Verification

| ID | 상태 | 내용 |
|----|------|------|
| V1 | RESOLVED | 매 태스크 `./gradlew test` 회귀 무. (CLAUDE.md) |
| V2 | RESOLVED | AC8 round-trip 테스트 = **갈아끼움(폐기 아님)** — `recordIfAbsent` 만료행 삭제 경로의 경계 검증으로 전환. NOW 통일 후 주입 now 기반 DELETE 경계를 비-UTC JVM TZ에서 회귀 안전망으로 유지. (사용자 확정) |
| V3 | RESOLVED | BaseEntity 전환 회귀: PaymentOutboxInstantTest 등 기존 Instant 단언 테스트가 안전망. round-trip(저장→조회 UTC 일치)은 PaymentEventRepositoryImplTest로 커버. (코드 검증) |

## 3-Path Routing 결론

- **Path 1 (code) 완료**: S2~S5, C4, O2, V3 — 코드 직접 조사로 RESOLVED.
- **Path 2 (user) 완료**: S6(TZ 3겹 전부) / C3(`DATETIME(6)` 승급) / O3(product connectionTimeZone 유지) / V2(AC8 → `recordIfAbsent` 만료 경계 검증으로 갈아끼움) — 사용자 확정으로 RESOLVED.
- Path 1 연속 2회 후 Path 2로 전환(Dialectic Rhythm Guard 준수, 3연속 미도달).
- Path 3/4 불필요: 외부 리서치·하이브리드 판단 대상 없음(전부 내부 코드/정책 결정).

## 종료 조건 점검

- 4트랙(scope/constraints/outputs/verification) 모두 ≥1 항목 커버 — 충족.
- 핵심 가정(existsValid 데드, admin 응답 타입 불변) 코드로 검증 — 충족.
- 잔여 OPEN 4건(S6/C3/O3/V2) 사용자 확정으로 전건 RESOLVED — ledger 잔여 OPEN 0건.
- **Round 0 마감.** Architect(Round 1)에 인계: TZ 3겹 backstop / audit `DATETIME(6)` Flyway 승급 / product connectionTimeZone 유지 / AC8 → `recordIfAbsent` 만료 경계 테스트.
