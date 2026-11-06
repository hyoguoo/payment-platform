# Trace Continuity Smoke

> 영구 가이드 — HTTP → Kafka → Kafka → HTTP 다중 홉에서 traceId 가 끊기지 않는지 검증.
> 스크립트: `scripts/smoke/trace-continuity-check.sh`

## 목적

분산 트랜잭션의 사고 재구성은 traceId 로부터 시작한다. 서비스 경계(HTTP → Kafka producer → Kafka consumer → 다음 서비스 HTTP) 를 넘을 때 traceparent 헤더가 끊기면 사후 분석이 불가능하다. 본 스크립트는 고정 traceparent 를 붙여 user 조회, checkout, confirm 을 각각 한 번씩 보내고 status 가 DONE 이 될 때까지 폴링한 뒤, 다섯 개 서비스(gateway, payment, pg, product, user) 컨테이너의 최근 5분 로그에서 같은 traceId 가 나오는지 grep 한다. 결제를 미리 만들어 둘 필요는 없다.

## 검사 항목 (개념)

```
브라우저 → gateway(traceparent 생성)
        → payment-service(receive + Kafka producer 헤더 주입)
            → kafka-topics: payment.commands.confirm, payment.events.confirmed, payment.events.stock-committed
                → pg-service / product-service(consumer 헤더 → MDC 복원)
                    → 외부 PG / 내부 HTTP 호출(traceparent 재주입)
                        → user-service / 벤더(헤더 수신)
```

각 hop 의 로그에 동일 traceId(또는 그 부모 trace) 가 찍혀야 한다.

## 사용법

pg-service 가 `PG_GATEWAY_TYPE=fake` 로 떠 있어야 confirm 이 DONE 까지 간다. `--mode fake` 로 기동하면 smoke override 가 적용된다.

```bash
bash scripts/compose-up.sh --mode fake
./scripts/smoke/trace-continuity-check.sh
```

`--mode fake` 가 아니라 기본 모드로 띄웠다면 pg-service 에만 smoke override 를 얹는다.

```bash
docker compose -f docker/docker-compose.infra.yml \
               -f docker/docker-compose.apps.yml \
               -f docker/docker-compose.smoke.yml \
               up -d --force-recreate --no-deps pg-service
sleep 15
./scripts/smoke/trace-continuity-check.sh
```

옵션:
- `--auto-compose-up` — gateway 가 응답하지 않으면 `scripts/compose-up.sh` 와 pg-service smoke override 재기동을 먼저 실행한다.
- `--verbose` — 서비스별로 매칭된 로그 라인을 일부 출력한다.

종료 코드:
- 0 — 모든 hop 에서 traceId 연속성 확인
- 1 — 한 곳이라도 traceId 누락. FAIL 라인이 누락된 hop 을 가리킴

## 실패 케이스 해석

| 누락 위치 | 원인 후보 | 조치 |
|---|---|---|
| Kafka producer → consumer | producer 측 KafkaTemplate 가 `ObservationRegistry` 미주입 | `KafkaProducerConfig` 의 자체 생성 ProducerFactory 들이 ObservationRegistry 명시 wiring 됐는지 + `spring.kafka.template.observation-enabled=true` 확인 |
| Consumer → 다음 hop | `spring.kafka.listener.observation-enabled=true` 누락 또는 `MdcContextPropagationConfig` 의 `Slf4jMdcThreadLocalAccessor` 등록 누락 | 두 설정 모두 활성 + `ContextRegistry` 에 MDC accessor 등록 확인 |
| HTTP 어댑터 hop | Feign / WebClient / RestClient 가 `traceparent` 헤더 자동 주입 안 함 | 클라이언트 빌더가 Spring Boot auto-config 의 ObservationRegistry 를 상속받는지 확인 |
| Virtual Thread 경계 | VT executor 가 OTel + MDC 두 컨텍스트를 캡처·복원 안 함 | `ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor()` 사용 확인 |
| in-memory channel 경계 (pg-service) | offer 시점 컨텍스트 캡처 누락 또는 take 시점 set 누락 | `PgOutboxChannel.offerNow` 가 `Context.current()` + `captureAll()` 로 컨텍스트를 담는지, `AbstractImmediateWorker` 의 worker 루프가 take 한 job 의 두 컨텍스트(MDC snapshot, OTel Context)를 relay 전에 복원하는지 확인 |

## 검사 대상 바꾸기

검사 대상 서비스가 늘어나면 스크립트의 `SCALABLE_SERVICES` 배열에 추가한다.

## 비범위

- **payment confirm 시나리오 정합성** — 별도 통합 테스트
- **traceId 외 메트릭(p95 latency 등)** — Prometheus + Grafana 별도

## 관련 자료

- 과거 트리아지 기록: `docs/archive/pre-phase-4-hardening/phase-gate/trace-continuity-smoke.md`
