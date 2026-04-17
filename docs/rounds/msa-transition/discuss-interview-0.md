# MSA-TRANSITION Round 0 — Interviewer 명료화

> 작성: 2026-04-17

## 1. Ambiguity Ledger (4트랙)

| 트랙 | 초기 질문 | Path | 결과 |
|---|---|---|---|
| scope | 서비스 분해 개수 목표 | Path 2 | **ADR-01에서 결정** (deferred) |
| scope | 이번 토픽 산출물 범위 | Path 2 | **ADR + 전면 구현 + 로컬 오토스케일러 코드까지** |
| constraints | DB 분리 수준 | Path 2 | **container-per-service** (서비스마다 MySQL 컨테이너) |
| constraints | 현 docker-compose에 Kafka 인프라 존재 여부 | Path 1 | 없음 — 신설 필요 (application 설정만 있고 서비스 미기동) |
| outputs | ADR 문서 + 구현 코드 + 오토스케일러 | 위 Q3으로 해소 | 전면 구현 + 오토스케일러 코드까지 포함 |
| verification | 최종 정합성 검증 강도 | Path 2 | **장애 주입** (Toxiproxy · 브로커 파티션 · DB 지연) |

## 2. 확정 사실

**이전 사전 브리핑에서 이미 확정된 항목**:
- 보안(인증·mTLS·PCI) 제외
- 실배포(k8s) 제외 → docker-compose 한정
- 모놀리스 잔재 허용 (Strangler)
- Spring Cloud 잠정 스택은 ADR에서 개별 검증

**Round 0에서 추가 확정**:
- DB는 **container-per-service** → Saga·보상·분산 정합성 설계가 강제됨. 분산 트랜잭션 옵션 원천 배제
- **Kafka 인프라는 신설**이 전제 (기존 docker-compose에 broker 없음)
- **검증은 장애 주입 수준**까지 → Toxiproxy 또는 동등 도구 스택 결정이 ADR에 포함되어야 함
- 산출물 범위가 **전면 구현 + 오토스케일러 코드** → 단일 토픽 안에서 완결하기엔 대형 작업. **plan 단계에서 phase 분할 필수**

## 3. Dialectic Rhythm

Path 1(Kafka 인프라 사실 확인) 1회 → Path 2(4트랙 배치 질문) 1회.
연속 Path 1/4 3회 조건에 해당 없음. Guard 정상.

## 4. 열린 질문 이월 (Architect Round 1에 인계)

- **서비스 분해 개수 및 경계** (ADR-01) — 커버링 ADR 순서에 영향
- **Admin UI(Thymeleaf) 분리 여부** (ADR-24) — 후순위지만 container-per-service 정책에서 관리자 전용 DB 여부와 엮임
- **paymentgateway 컨텍스트의 물리 분리 여부** (ADR-21) — 코어 PG 선택 결정과 연계
- **이벤트 스키마 포맷** (ADR-12) — 서비스 분해 개수가 커질수록 Schema Registry 필요성 상승
- **현 `OutboxAsyncConfirmService` + 릴레이 테이블 자산의 운명** — container-per-service 정책 하에서 릴레이 테이블은 "각 서비스가 소유"해야 함. Kafka 재시도 전면 전환 논의는 ADR-04/04a에 존치
- **장애 주입 도구 스택** — Toxiproxy 단독 vs Chaos Mesh(경량) vs 수동 kill 스크립트 — ADR-29 또는 별도 ADR 필요 가능성

## 5. Round 1 Architect 인계 메시지

- 토픽: `MSA-TRANSITION`
- 산출물 디렉터리: `docs/topics/MSA-TRANSITION.md` (사전 브리핑 기작성)
- **ADR 29개 초안 작성**: 카테고리별로 구조화하여 한 문서 안에 배치
- 제약:
  - container-per-service 강제 → Saga·보상·멱등성이 우선 방어선
  - Kafka 인프라 신설 전제
  - 장애 주입(Toxiproxy 수준) 검증 요구를 ADR에 반영
  - plan 단계에서 phase 분할이 필요할 정도의 대형 범위이므로 **ADR 간 의존 순서**를 topic 문서에 명시
- 참조: `docs/context/ARCHITECTURE.md`, `CONVENTIONS.md`, `INTEGRATIONS.md`, `STRUCTURE.md`, `CONCERNS.md`, `PITFALLS.md`, `TODOS.md`

## 6. 종료 조건 충족

- scope / constraints / outputs / verification 4트랙 모두 최소 1회 Path 2 커버 ✅
- 핵심 가정(DB 분리, 검증 강도, 산출물 범위) 사용자 확인 완료 ✅
- Round 1 Architect 인계 준비 완료 ✅
