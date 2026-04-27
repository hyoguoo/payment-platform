# Codebase Risk / Drift / Dead Code Findings Report

> 작성: 2026-04-27 (CONFIRM-FLOW 통합 후 systematic sweep)
> 임시 파일 — 처리 완료된 항목은 제거됨. 미처리 항목만 잔존.

## 처리 완료 (이력 보존)

| 항목 | 처리 | commit |
|---|---|---|
| #핵심1 보상 중복 진입 가드 | handleFailed/handleQuarantined 의 isTerminal 가드 추가 | `3a7989b1` |
| #C1 pg HTTP timeout 근거 | yml 주석 + INTEGRATIONS.md 정책 섹션 + TODOS T4-D 보강 | `324c9c59` |
| #C2 Kafka groupId 네이밍 룰 | CONVENTIONS.md Kafka 섹션 | `324c9c59` |
| #C3 OutboxWorker default 통일 | 10 → 50 (yml 명시값과 정합) | `324c9c59` |
| #C4 Kafka consumer timeout 정렬 | CONVENTIONS.md timeout 정렬 가이드 | `324c9c59` |
| #B1 EXPIRED 만료 정책 | TODOS TC-4 등재 — 별도 토픽 필요 | (deferred) |
| #B2 DLQ 자동 처리 | TODOS TQ-1 등재 | (deferred) |
| #B3 VT bulkhead | TODOS TC-6 등재 | (deferred) |
| #B4 Retryable ControllerAdvice | TODOS TC-5 등재 | (deferred) |

---

## 미처리 (TODO 미등재 + 코드 미변경)

### #C5 — product / pg dedupe 테이블 cleanup 스케줄러 부재

- **위치**: `stock_commit_dedupe` (product), `pg_inbox` (pg)
- **문제**: 만료 row 자동 cleanup 스케줄러 없음 → 시간이 지날수록 테이블 누적. 장기 운영 시 쿼리 성능 저하 가능
- **현 상태**: ARCHITECTURE.md 의 dedupe 결정 사유 섹션에 한 줄 메모만 있고, TODO 미등재
- **제안**: TODOS.md 에 TC-? 신규 등재. 또는 별도 cleanup 토픽 격상

---

## 처리 옵션

(가) #C5 를 TODOS 에 신규 등재 → 본 보고서 완전 삭제 (가장 깨끗)
(나) 본 보고서를 #C5 reference 로 유지 (이력 추적 가치)
