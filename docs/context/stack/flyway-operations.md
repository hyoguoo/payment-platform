# Flyway 운영 가이드 — DB 마이그레이션

> 4서비스 공통 Flyway 패턴, profile 별 locations, named volume 재사용 시 MissingMigration 대응, Testcontainers 격리.

**모델**: 4서비스 모두 동일 패턴.

```
<service>/src/main/resources/
├── db/schema/
│   └── V1__<bounded>_schema.sql   # 단일 schema baseline
└── db/seed/
    └── V2__seed_*.sql             # (필요 시) seed 데이터 — INSERT IGNORE 멱등
```

**디렉토리 분리 룰**:
- schema SQL 은 `db/schema/` 에, seed SQL 은 `db/seed/` 에 배치한다.
- V3 이후 신규 schema migration 은 `db/schema/` 에 추가한다.
- `db/migration/` 은 더 이상 사용하지 않는다.

**profile 별 locations 설정**:
```yaml
# default / test profile — application.yml
spring:
  flyway:
    enabled: true
    locations: classpath:db/schema,classpath:db/seed
  jpa:
    hibernate:
      ddl-auto: validate    # Flyway 가 baseline 적용 후 JPA 가 컬럼 검증

# docker profile — application-docker.yml (override)
spring:
  flyway:
    locations: classpath:db/schema   # seed 제외 — 운영 DB 에 초기 데이터 삽입 방지
```

- `default` / `test` profile: `db/schema` + `db/seed` 모두 적용 (테스트 픽스처 포함)
- `docker` profile (`SPRING_PROFILES_ACTIVE: docker`): `db/schema` 만 적용 → V2 seed row 차단
- `docker-compose.apps.yml` 의 4 비즈니스 서비스가 모두 `SPRING_PROFILES_ACTIVE: docker` 로 기동하므로 이 override 가 운영에서 실제 활성화됨.

**부팅 시 동작**:
1. DataSource 준비 → Flyway `migrate()` 자동 호출
2. `flyway_schema_history` 없으면 V1 부터 적용 + history row 추가
3. JPA EntityManager 가 `@Entity` 와 실제 schema 일치 검증

**버전 추적**: 각 DB(`payment-platform`, `pg`, `product`, `user`) 안 `flyway_schema_history` 테이블.
```sql
SELECT version, script, installed_on, success FROM flyway_schema_history ORDER BY installed_rank;
```

**불변성**: 이미 적용된 V 파일 내용은 절대 변경 금지 (checksum 충돌). 변경 필요 시 새 V 번호 추가.

**운영 적용 시**:
- 신규 DB → `migrate()` 가 V1 부터 자동 적용
- 기존 DB 에 Flyway 도입 → `spring.flyway.baseline-on-migrate: true` + `baseline-version: 0` 옵션으로 기존 schema 를 baseline 으로 잡고 그 위에 V1+ 적용. 본 프로젝트는 default(false) 로 깨끗한 시작 가정

**named volume 재사용 시 MissingMigrationException 대응 (3-step 가이드)**:

시나리오: `docker-compose down` 만 실행 시 `mysql-product-data` / `mysql-user-data` named volume 이 살아남는다 (`docker-compose.infra.yml` 에 `external: true` 없음). schema/seed 디렉토리 분리 적용 후 재기동 시 기존 볼륨의 `flyway_schema_history` 에 V2 record 가 `db/seed` 경로로 기록돼 있는데, `docker` profile 은 `db/schema` 만 스캔 → **`MissingMigrationException`** 으로 부팅 실패.

`spring.flyway.ignore-migration-patterns` 기본값: `*:future` 만 ignore, `*:missing` 은 **fail** (기본 동작). V2 record 가 볼륨에 남아있으면 수동 처리 없이는 부팅 불가.

**Step 1 (권장 — 학습 환경)**: named volume 전체 또는 개별 삭제 후 재기동.
```bash
# 전체 미사용 볼륨 정리
docker volume prune

# 또는 특정 볼륨만 삭제
docker volume rm payment-platform_mysql-product-data
docker volume rm payment-platform_mysql-user-data
```

**Step 2 (볼륨 보존 필요 시)**: `flyway_schema_history` V2 record 수동 삭제 후 재기동.
```sql
-- mysql-product 또는 mysql-user 컨테이너 접속 후 실행
DELETE FROM flyway_schema_history WHERE version = '2';
```

**Step 3 (일시 우회 옵션 — 운영 비권장)**: `spring.flyway.ignore-migration-patterns: '*:missing'` 임시 설정 후 재기동.
- `db/schema` 에 V2 파일이 없으므로 missing check 를 skip 해 부팅 허용.
- 운영 누적 DB 에 적용 시 실제 누락된 migration 도 무시될 수 있어 **운영 환경에서는 비권장**.

**운영 학습용 가정**: 본 프로젝트는 학습 / 데모 단계로 운영 누적 DB 가 없다. 운영 도입 시 위 가이드 외에 추가 절차 (DB 백업 / migration 사전 검증 / `baseline-on-migrate` 전략 등) 가 필요하다.

**Testcontainers**: 매 테스트마다 새 MySQL 컨테이너 → V1 부터 자동 적용. `ddl-auto: validate` 로 테스트 schema 검증. `product-service` 의 `FlywayDockerProfileTest` 는 `@ActiveProfiles("docker")` 로 docker profile 의 seed 차단 동작을 통합 검증한다.

**통합 테스트 환경 격리**: `@Tag("integration")` 통합 테스트는 `application-test.yml` 의 `spring.flyway.enabled: false` + `jpa.hibernate.ddl-auto: create-drop` 으로 운영하며, `BaseIntegrationTest` 의 Testcontainers MySQL 위에서 JPA 가 `@Entity` 기반 schema 를 생성한다. 의도: Flyway ↔ JPA 순환 의존(`Circular depends-on relationship between 'flyway' and 'entityManagerFactory'`) 회피 + 테스트 격리.
- `@Sql("/data-test.sql")` 시드: 현재 NOOP(`SELECT 1`). MSA 분리 후 user/product 데이터는 별도 서비스 책임이라 본 시드는 빈 자리만 유지
