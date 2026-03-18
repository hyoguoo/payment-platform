# Payment Platform — 비동기 결제 처리 마이그레이션

## What This Is

기존 동기 방식의 결제 확인(confirm) 플로우를 비동기로 전환하는 프로젝트다. Toss Payments API 호출을 비동기화하여 고부하 시 요청 지연 문제를 해결하고, 3가지 처리 전략(Sync / DB Outbox / Kafka)을 포트-어댑터 패턴으로 교체 가능하게 구현한다. k6 성능 측정으로 각 전략의 TPS·레이턴시를 정량 비교하는 것을 최종 목표로 한다.

## Core Value

**어떤 비동기 전략을 쓰든 코드 변경 없이 Spring Bean 설정만으로 교체 가능하고, k6로 성능 차이를 즉시 측정할 수 있어야 한다.**

## Requirements

### Validated

- ✓ 결제 체크아웃 (POST /api/v1/payments/checkout) — existing
- ✓ 결제 확인 동기 처리 (POST /api/v1/payments/confirm) — existing
- ✓ 결제 상태 스케줄러 (UNKNOWN/IN_PROGRESS 복구, READY 만료) — existing
- ✓ Toss Payments 연동 (confirm / cancel / 조회) — existing
- ✓ 결제 히스토리 이벤트 기록 — existing
- ✓ Hexagonal Architecture (Ports & Adapters) — existing

### Active

- [ ] 비동기 처리 포트 인터페이스 정의 (`PaymentConfirmAsyncPort`)
- [ ] Sync 어댑터 — 기존 동기 처리 구현체를 포트 구현으로 래핑
- [ ] DB Outbox 어댑터 — 요청을 DB에 저장 후 워커 스레드가 처리
- [ ] Kafka 어댑터 — 요청을 Kafka 토픽에 발행 후 컨슈머가 처리
- [ ] Spring 설정으로 어댑터 교체 (`spring.payment.async-strategy: sync|outbox|kafka`)
- [ ] GET /api/v1/payments/{orderId}/status — 처리 상태 폴링 엔드포인트
- [ ] 202 Accepted 응답 — confirm 요청 접수 즉시 반환 (비동기 어댑터 사용 시)
- [ ] k6 부하 테스트 스크립트 — 3가지 전략 성능 측정
- [ ] 성능 비교 결과 문서 (README 또는 별도 문서)

### Out of Scope

- WebSocket / SSE 실시간 푸시 — 폴링으로 충분, 복잡도 대비 효용 낮음
- 분산 환경 고려 (멀티 인스턴스) — 싱글 인스턴스 가정
- 실결제 연동 — 포트폴리오 용도, Toss 테스트 키 사용
- 프론트엔드 UI — API + 문서 결과물로 충분
- 인증/인가 — 기존 코드 없음, 추가 불필요

## Context

- **기존 아키텍처**: Hexagonal (Ports & Adapters), 4-layer per module (presentation → application → domain → infrastructure)
- **현재 confirm 플로우**: 요청 수신 → 재고 감소 → Toss API 호출 (동기) → 상태 DONE → 응답. Toss API 호출이 병목.
- **기존 `PaymentProcess`**: 이미 결제 Job 추적 테이블 존재 → Outbox 레코드로 재활용 가능
- **기존 스케줄러**: `PaymentRecoverService` 이미 UNKNOWN/IN_PROGRESS 복구 로직 존재 → Outbox 워커 패턴과 자연스럽게 연계
- **포트폴리오 목표**: 아키텍처 우아함 + 실제 프로덕션 패턴(Outbox, At-least-once delivery) + k6 수치 비교

## Constraints

- **Tech Stack**: Java 17+, Spring Boot, JPA, MySQL — 기존 스택 유지
- **Kafka**: Docker Compose로 로컬 실행 (포트폴리오 환경)
- **Single Instance**: 분산 락 불필요, 단순 워커 스레드로 충분
- **Profile/Config 기반 전환**: 코드 변경 없이 `application.yml` 값만 바꿔서 전략 교체

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| PaymentConfirmAsyncPort 단일 포트로 3가지 전략 추상화 | 전략 교체 시 나머지 코드 무변경 | — Pending |
| 기존 PaymentProcess 테이블을 Outbox 레코드로 활용 여부 | 신규 테이블 최소화 vs 관심사 분리 | — Pending |
| Kafka 어댑터 선택 시 202, Sync 어댑터 선택 시 200 유지 | API 계약 일관성 vs 의미론적 정확성 | — Pending |
| k6 테스트 대상 시나리오 (VU 수, 부하 패턴) | 포트폴리오에서 의미있는 차이를 보여야 함 | — Pending |

---
*Last updated: 2026-03-14 after initialization*
