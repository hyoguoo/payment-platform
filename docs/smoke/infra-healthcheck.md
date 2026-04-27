# Infra Healthcheck Smoke

> 영구 가이드 — 시점에 의존하지 않는 인프라+서비스 살아있음 검사.
> 스크립트: `scripts/smoke/infra-healthcheck.sh`

## 목적

payment-platform 의 docker compose 스택이 정상 기동되었는지를 한 번에 확인한다. 각 서비스의 비즈니스 로직 검증(예: confirm 시나리오)은 본 스크립트의 비범위 — 그건 통합 테스트 또는 phase-N 게이트가 담당한다. 본 스크립트는 다음에 답한다:

> "지금 모든 컨테이너가 살아있고, 호스트에서 접근 가능하며, 서로 등록되어 있는가?"

## 검사 항목

| 카테고리 | 대상 | 방법 |
|---|---|---|
| Docker compose health | 13개 서비스 컨테이너 | `docker inspect -f '{{.State.Health.Status}}'` — compose 가 정의한 healthcheck 결과 그대로 |
| 호스트 포트 — 진입점 | gateway(8090), eureka(8761) | `curl /actuator/health` |
| 호스트 포트 — DB | mysql×4 (3306·3308·3309·3310) | `mysqladmin ping` (docker exec) |
| 호스트 포트 — Cache | redis dedupe(6379), redis stock(6380) | `redis-cli ping` (docker exec) |
| 호스트 포트 — Broker | kafka(9092) | `kafka-topics --list` (docker exec) |
| Eureka 등록 | 5개 앱 (PAYMENT-SERVICE / PG-SERVICE / PRODUCT-SERVICE / USER-SERVICE / GATEWAY) | `GET /eureka/apps` 의 `<name>` 매칭 (`spring.application.name` 대문자) |

총 ~25 항목. PASS/FAIL 한 줄씩, 종합 exit code.

## 사용법

```bash
# 1. 스택 기동
docker compose -f docker/docker-compose.infra.yml \
               -f docker/docker-compose.apps.yml \
               -f docker/docker-compose.observability.yml up -d

# 2. start_period(60s) 통과 후 검사
sleep 60
./scripts/smoke/infra-healthcheck.sh

# 종료 코드
# 0 → 전 항목 PASS, 후속 작업 진행 가능
# 1 → 하나라도 FAIL, FAIL 라인 보고 원인 트리아지
```

## 실패 케이스 해석

| FAIL 로그 | 원인 | 조치 |
|---|---|---|
| `… (still starting — wait longer)` | start_period 안에 healthcheck 미통과. JVM 초기화 + Flyway 마이그레이션이 시간 소요 | 30~60초 더 기다린 후 재실행 |
| `… (unhealthy)` | healthcheck 실패. 컨테이너는 떠 있으나 `/actuator/health` 가 503 반환 | `docker logs <svc>` 로 stack trace 확인. 흔히 DB 연결 / Eureka 등록 실패 |
| `… (container not found)` | 해당 이름의 컨테이너 없음. compose up 누락 또는 다른 프로젝트 이름 사용 | `docker compose ps` 확인 후 누락 서비스 up |
| `mysql-{x} ping` FAIL | MySQL 컨테이너 죽었거나 `mysqladmin` 미존재 | 컨테이너 로그 확인. MySQL 8.0 이미지 표준이라 거의 컨테이너 down |
| `kafka 토픽 list` FAIL | Kafka 미기동 또는 broker 미준비 | Kafka 의 start_period(보통 90s) 통과 후 재실행 |
| `<APP> 미등록` | 서비스가 Eureka 에 heartbeat 보내기 전 | 서비스 부팅 후 30~60초 추가 대기. 지속 시 `docker logs <svc>` |

## 비범위

- **confirm 시나리오** — 결제 플로우 정합성 검증은 통합 테스트(`@SpringBootTest` + `Testcontainers`) 또는 phase-N-gate (시점 의존) 가 담당
- **컬럼·스키마 검증** — Flyway baseline 적용은 부팅 시 자동, JPA `ddl-auto: validate` 가 컬럼 검증을 부팅 시 강제하므로 healthy 상태 자체가 검증을 함의
- **부하 테스트** — k6 가 담당
- **보안 검사** — 별도 스캐너

## 영구성

본 스크립트는 시점 의존 phase-N-gate 와 달리 **현재와 미래 양쪽에서 그대로 사용**한다. Phase 4 진입 후에도 동일한 토폴로지(4서비스 + Eureka + Gateway + 인프라) 가 유지되는 한 검사 항목 변경 없이 동작한다. 새 서비스 추가 시 `EXPECTED_SERVICES` 배열만 갱신.
