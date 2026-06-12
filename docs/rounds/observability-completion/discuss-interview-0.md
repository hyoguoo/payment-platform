# OBSERVABILITY-COMPLETION — Interview Round 0

> Interviewer(Opus) 주도 명료화. ambiguity ledger 4트랙 + 확정 가정.

## Ambiguity Ledger (4트랙)

### scope
- **3기둥 확정**: (1) 비즈니스 지표 대시보드, (2) 시스템(서버별) 지표 대시보드, (3) 트레이스/로그 추적 진입 편의.
- **신규 메트릭 3종 포함**: D7 가드 스킵 카운터 / dedupe cleanup 실패 카운터 / Kafka tx coordinator 가용성 지표.
- **알람 rule 제외** (사용자 결정 Path 2): Prometheus alert rule / SLO 는 이번 범위 밖. 측정(k6) 후 별도 토픽. 대시보드+추적만 완성.
- **측정 인프라 무관**: k6 부하·Toxiproxy 장애주입 없이 완결. 임계치 기반 작업은 전부 제외.

### constraints
- **span 비즈니스 속성 키 = 주문번호(orderId) + 사용자ID(userId)** (사용자 결정 Path 2). 결제키(paymentKey)·벤더타입(gatewayType)은 **이번 범위에서 제외** — Architect 가 임의로 재추가하지 않는다(필요하면 근거와 함께 재논의 제기).
  - orderId 는 고카디널리티지만 span **속성**(메트릭 라벨 아님)이라 카디널리티 폭발 없음. userId 도 동일.
  - **불변**: 이 두 키를 Micrometer 메트릭 **라벨**로는 절대 쓰지 않는다(메트릭 카디널리티 폭발 방지).
- **트레이스 샘플링 기본값 = 1.0 전량** (사용자 결정 Path 2). 학습/데모 환경 기준 항상 트레이스 존재. `TRACING_SAMPLING_PROBABILITY` env override 경로는 유지(운영 시 하향 가능).
- **로그 포맷**: 현재 텍스트(`%X{traceId}`) 유지. JSON 구조화 전환은 범위 밖(가정, 미반박).
- **인스턴스 단위**: 현재 단일 인스턴스 가정(L-3). 시스템 대시보드는 서비스(application 라벨) 단위로 구성하되, 멀티 인스턴스 확장 시 변수로 분해 가능하게 둔다(가정).

### outputs
- 대시보드 2종: `business-dashboard.json`(가칭) + `system-dashboard.json`(가칭). 기존 `payment-dashboard.json` 재구성/분할.
- span 비즈니스 속성 부착 코드(어느 경계에서 orderId/userId 를 현재 span 에 set 할지 — Architect 설계).
- Prometheus exemplar 활성(histogram exemplar) + Grafana 패널 exemplar 링크.
- Tempo `metrics_generator` 활성(service graph + span RED 메트릭) — `tempo.yml` + datasource.
- 신규 메트릭 3종 구현(payment-service / product-service / 가용성 지표).
- 샘플링 기본값 변경(`application.yml` 6서비스 또는 공통).

### verification
- **측정 무관 수동 스모크**로 검증(가정, 사용자 확인 대상): compose up → confirm 1건 흘림 →
  (a) 비즈니스 대시보드에 격리·전이·상태분포·벤더 latency·신규 메트릭 패널 렌더,
  (b) 시스템 대시보드에 6서비스 JVM/heap/GC/Hikari/consumer lag 서버별 표시,
  (c) **Tempo TraceQL 로 orderId 검색 → 해당 트레이스 워터폴 진입**(핵심 수용 기준),
  (d) latency 패널 exemplar 클릭 → 트레이스 점프,
  (e) `trace-continuity-check.sh` 여전히 통과.
- 코드측 신규 메트릭은 `./gradlew test` 단위 테스트로 회귀 가드.

## 확정 가정 리스트

1. 알람 rule 제외 — 관측(대시보드+추적)만 완성, 알람은 측정 후 별도 토픽.
2. span 비즈니스 키 = orderId + userId 두 개만. paymentKey/gatewayType 제외.
3. 트레이스 샘플링 기본 1.0 전량(env override 유지).
4. 로그 포맷 텍스트 유지(JSON 전환 범위 밖).
5. 시스템 대시보드는 서비스(application) 단위, 멀티 인스턴스는 변수 분해 여지만.
6. 검증은 수동 스모크 + 단위 테스트(측정 인프라 무관).

## 종료 판정
- 4트랙(scope/constraints/outputs/verification) 모두 ≥1회 커버. 핵심 가정 3건 사용자 Path 2 확인 완료. → Architect Round 1 진입.
