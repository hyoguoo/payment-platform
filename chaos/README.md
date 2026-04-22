# Toxiproxy 장애 주입 도구

ADR-29 결정에 따라 Kafka·MySQL·Redis 앞단에 Toxiproxy를 배치하여 네트워크 장애를 시뮬레이션한다.
실제 장애 시나리오(latency, timeout, down) 실행은 Phase 4 T4-01에서 k6/smoke-test와 연동하여 수행한다.

## proxy 구성 (`chaos/toxiproxy-config.json`)

| proxy 이름            | 컨테이너 listen  | 호스트 포트 | upstream                 | 용도                         |
|----------------------|------------------|-------------|--------------------------|------------------------------|
| kafka-proxy          | 0.0.0.0:29092    | 29093       | kafka:9092               | Kafka 브로커 장애 주입         |
| mysql-payment-proxy  | 0.0.0.0:23306    | 23306       | mysql-payment:3306       | payment-service DB 장애 주입   |
| redis-stock-proxy    | 0.0.0.0:26380    | 26380       | redis-stock:6379         | 재고 캐시 Redis 장애 주입      |

> kafka-proxy 호스트 포트가 29093인 이유: kafka 서비스가 PLAINTEXT_HOST 리스너로 29092를 선점하고 있어 충돌 방지.
> T4-01에서 애플리케이션을 proxy 경유로 전환할 때 Spring 설정의 bootstrap-servers 포트를 29093으로 바꿀 것.

> 애플리케이션 컨테이너는 이 단계에서 직접 mysql/kafka를 가리킨다.
> T4-01에서 Spring profile 전환으로 proxy 경유로 바꿀 예정이다.

## Toxiproxy 시작

```bash
# toxiproxy 서비스만 단독 기동 (chaos compose, opt-in)
docker compose -f docker-compose.chaos.yml up toxiproxy

# 전체 인프라 + chaos 함께 기동
docker compose -f docker-compose.infra.yml -f docker-compose.chaos.yml up
```

## proxy 목록 확인

```bash
curl http://localhost:8474/proxies
```

## toxic 적용 예시 — Kafka 500ms latency 주입

```bash
curl -X POST http://localhost:8474/proxies/kafka-proxy/toxics \
  -H "Content-Type: application/json" \
  -d '{"name":"kafka-latency","type":"latency","stream":"downstream","attributes":{"latency":500,"jitter":50}}'
```

## toxic 제거

```bash
curl -X DELETE http://localhost:8474/proxies/kafka-proxy/toxics/kafka-latency
```

## Toxiproxy API 참고

- `GET  /version` — 버전 확인 (healthcheck 엔드포인트)
- `GET  /proxies` — 등록된 proxy 목록
- `POST /proxies/{name}/toxics` — toxic 추가
- `DELETE /proxies/{name}/toxics/{toxicName}` — toxic 제거

## Phase 4 연동 안내

Phase 4 T4-01에서 k6 부하 테스트 및 smoke-test 시나리오와 연동될 예정이다.
Toxiproxy proxy 경유 전환은 Spring profile(`chaos`) 활성화로 수행하며, 현재 단계에서는 proxy 엔드포인트만 선언한다.

---

**참조**: ADR-29 (Toxiproxy 장애 주입 도구 선택)
