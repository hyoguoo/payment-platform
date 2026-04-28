# Discuss Round 0 — Interviewer

**Topic**: PAYMENT-DOUBLE-FAULT-RECOVERY (재시작)
**Date**: 2026-04-09

## 사용자 원요구

1. 재시도 가능 오류 한정 자동 재시도(N회)
2. UNKNOWN 상태 제거 — 정말 관리자 개입 필요한 건만 격리
3. RETRYING 상태는 재시도 진입된 건만
4. 서버 임의 종료 시 데이터 유실 없음, 복구 라인으로 재개
5. Toss 멱등성 활용해 승인/복구를 단일 메서드로 통합, 응답 기반 분기

## 4-Track Ambiguity Ledger

### Scope
- **확정**: 카탈로그 16건 전부 방어선 결정 대상
- **확정**: `claimToInFlight` 기반 현재 선점 로직 유지(단일 승자 가정). DB 원자 연산으로 간단히 가능하면 강화, 아니면 추후 교체 용이하게 구현.

### Constraints
- **확정**: 복구 사이클은 **getStatus 선행 조회 → 결과 기반 분기** 단일 메서드 구조
- **확정**: 재시도 오류 분류는 기존 Toss 코드 분류(retryable/non-retryable) 재사용
- **확정**: 재시도 한도 N = 3 (기본값)
- **확정**: Toss idempotency-key는 orderId pass-through — 동일 승인 호출 안전 재시도 가능
- **가정**: 틱 주기는 현재 OutboxProcessingService 구성 유지 (별도 변경 없음)

### Outputs (상태/모델 변경)
- **확정**: `PaymentStatus.UNKNOWN` **완전 제거** (enum 매핑 fallback 포함)
- **확정**: 신규 격리 상태 도입 — 가칭 `QUARANTINED` (관리자 개입 필요 전용)
- **확정**: `PaymentEventStatus.RETRYING`은 재시도 진입된 건에만 부여 (진입/이탈 규칙 topic.md에서 정의)
- **미정**: 한도 소진 시 **케이스별 분기** —
  - 지속 판단 불가(타임아웃/5xx 반복) → `QUARANTINED`
  - 순수 1차 장애(PG에 주문 없음, 일시 4xx) 승인 재시도 소진 → 자동 실패 확정 + 재고 복구
  - 분기 규칙의 정확한 조건은 Architect가 topic.md §4 결정 ID로 정의

### Verification
- **가정**: 기존 복구 테스트 스위트(재시도 소진→보상, NON_RETRYABLE 즉시 보상, IN_FLIGHT 타임아웃 복구 등) 회귀 유지
- **미정**: 신규 방어선 케이스별 테스트 추가 범위 — plan 단계에서 구체화

## 확정 가정 리스트

1. 복구 진입점 단일화: `getStatus` 선행 조회를 갖는 공통 메서드 하나
2. 멱등성 경로: Toss 응답 결과로 상태 전이 분기 (승인 완료/실패/취소/진행중/없음/매핑불가)
3. 한도 N = 3, retryable 오류 한정 증가
4. `UNKNOWN` 제거, `QUARANTINED` 신설 — 한도 소진 판단 불가 건 전용
5. 동시성: 기존 claimToInFlight 유지, 가능하면 DB 원자 연산으로 강화
6. 카탈로그 16건 전수 대응 (topic.md 각 케이스 방어선 명시)

## Path Routing 기록

- Q1 (getStatus 선행) — Path 2 (user)
- Q2 (한도 소진 동작) — Path 2 (user)
- Q3 (UNKNOWN 네이밍) — Path 2 (user)
- Q4 (N 값) — Path 2 (user)
- Q5 (범위) — Path 2 (user)
- Q6 (동시성 가정) — Path 2 (user)
- Q7 (오류 분류) — Path 2 (user)

Dialectic Rhythm: Path 2 연속 (Path 1/4 없음) — 다음 Architect 라운드에서 코드 재료 확인 진행.

## 종료 조건

- [x] Scope/Constraints/Outputs/Verification 4트랙 모두 커버
- [x] 핵심 가정 사용자 확인 완료

**Next**: Round 1 — Architect가 위 가정으로 `docs/topics/PAYMENT-DOUBLE-FAULT-RECOVERY.md` 초안 작성.
