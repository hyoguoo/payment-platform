# Trace Continuity Smoke

> 영구 가이드 — HTTP → Kafka → Kafka → HTTP 다중 홉에서 traceId 가 끊기지 않는지 검증.
> 스크립트: `scripts/smoke/trace-continuity-check.sh`

## 목적

분산 트랜잭션의 사고 재구성은 traceId 로부터 시작한다. 서비스 경계(HTTP → Kafka producer → Kafka consumer → 다음 서비스 HTTP) 를 넘을 때 traceparent 헤더가 끊기면 사후 분석이 불가능하다. 본 스크립트는 결제 confirm 한 건을 흘려보내고, 다섯 개 서비스(gateway, payment, pg, product, user) 로그에서 같은 traceId 가 연속 등장하는지 자동 grep 한다.

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

```bash
docker compose -f docker/docker-compose.infra.yml \
               -f docker/docker-compose.apps.yml \
               up -d
sleep 60   # start_period 통과 대기
./scripts/smoke/trace-continuity-check.sh
```

종료 코드:
- 0 — 모든 hop 에서 traceId 연속성 확인
- 1 — 한 곳이라도 traceId 누락. FAIL 라인이 누락된 hop 을 가리킴

## 실패 케이스 해석

| 누락 위치 | 원인 후보 | 조치 |
|---|---|---|
| Kafka producer → consumer | producer 측 KafkaTemplate 가 `ObservationRegistry` 미주입 | `KafkaProducerConfig` 의 자체 생성 ProducerFactory 들이 ObservationRegistry 명시 wiring 됐는지 확인 |
| Consumer → 다음 hop | `OtelMdcMessageInterceptor` 미등록 또는 `@KafkaListener` 측 MDC 복원 누락 | 컨슈머 설정 검증 |
| HTTP 어댑터 hop | WebClient/RestClient 가 `traceparent` 헤더 자동 주입 안 함 | `HttpOperatorImpl` 에 OTel propagation 적용됐는지 |
| Virtual Thread 경계 | VT executor 가 MDC 복사 미수행 | `MdcContextExecutor` 또는 동등 wrapper 사용 확인 |

## 영구성

본 검사는 **현행 토폴로지와 무관하게 분산 트레이스가 끊기지 않는지를 확인하는 일반 도구**다. Phase 4 에서 Toxiproxy 로 장애를 주입할 때도, 새 서비스 추가 시에도 동일 관점으로 사용한다. 검사 대상 서비스가 늘어나면 `EXPECTED_HOPS` 같은 변수만 갱신.

## 비범위

- **payment confirm 시나리오 정합성** — 별도 통합 테스트
- **traceId 외 메트릭(p95 latency 등)** — Prometheus + Grafana 별도

## 관련 자료

- 직전 봉인 시점의 작업 기록: `docs/archive/pre-phase-4-hardening/phase-gate/trace-continuity-smoke.md` (T-E3 산출물 — 시점 의존 트리아지 가이드)
