# 현재 작업 상태

> 최종 수정: 2026-06-26 (ALERTING-RULES-AND-FAULT-DRILL discuss 완료 → plan)

## 활성 작업

- **주제**: ALERTING-RULES-AND-FAULT-DRILL (Prometheus 알람 규칙 인프라 구축 + Toxiproxy 장애 주입으로 알람 발화 실증)
- **단계**: plan
- **이슈/브랜치**: #116

## 재개 메모

- discuss 완료 — 설계 SSOT `docs/topics/ALERTING-RULES-AND-FAULT-DRILL.md`, 게이트 reviewer/domain-expert 둘 다 R3 pass.
- 확정 범위: 알람 3그룹(코디네이터 정체 / 종결 가드 늦은-결과 무시 / DLQ 적체) + rule 평가만(Alertmanager 미도입) + 장애 주입 전용 프로파일 + 그룹별 발화 검증 스크립트. 애플리케이션 코드 무변경(메트릭 전부 기존 존재).
- **plan 최우선 실증 대상**: ① 코디네이터 lag 비대칭 실현(서비스별 프록시 리스너 등) ② latency 하 EOS commit timeout 결정성(주입 지연 ↔ `transaction.timeout.ms` 의존). 불가 시 `promtool test rules` + 통합테스트로 격하(규칙은 운영 유효).
- 범위 밖 후속: 통지 채널, 나머지 장애 6종, k6 부하 곡선, 오토스케일러.

## 최근 완료

- **DLQ-REACHABILITY** (장애 지속 시 DLQ 도달 보장 — [PG-SELFLOOP-ATTEMPT-GAP]+TC-13-FOLLOW-7 둘 다 해소. Track P: pg self-loop 시도횟수가 런타임 1 고정(relay 헤더 미발행+attempt 컬럼 부재)이라 한도 dead branch·무한 반복하던 것을 `pg_inbox.attempt`(Flyway V5) SoT로 영속(Option B), 워커 resolveAttempt 읽기+retry 분기 incrementAttempt(TX_B) 누적→4 소진 시 기존 DLQ→QUARANTINED 자동 격리. 격리 metric은 QUARANTINED 전이 성공 지점(멱등). Track E: payment EOS 커밋 반복 실패가 컨테이너 디폴트 AfterRollbackProcessor(9회·DLQ 미진입)로 빠지던 것을 `setAfterRollbackProcessor` 명시 연결(공유 recoverer 빈 추출+신규 `payment.kafka.after-rollback.backoff.*` 기본 1000ms×5)로 confirmed.dlq 도달+metric. 비트랜잭션 DLQ 템플릿이라 실패 EOS tx와 분리. #7 갭-문서화→갭-수정-검증 전환. 수용 한계: over-sell 자동 복구는 TQ-1 후속, attempt over-count(동시 진입 조기 격리)는 안전 방향 수용. 4태스크, pg 단위 324+통합 9/payment 단위 458+통합 39 PASS+린트, discuss R2·plan R2·ship pass critical0/major1 doc-sync/minor2, 2026-06-25, 이슈/브랜치 #114) — `docs/archive/dlq-reachability/COMPLETION-BRIEFING.md`
- **CONFIRM-APPROVED-RESEND-GAP** (비동기 confirm APPROVED 재고 확정 재발행 갭 — RDB DONE 커밋 후 EOS 발행 유실 시 재배달이 D7 종결 가드에 막혀 영구 유실되던 갭을 종결 가드 DONE+APPROVED 재발행으로 복구. 설계 SSOT가 믿던 affected==0 발행 분기는 dedupe+종결전이 원자 커밋이라 도달 불가 dead branch → 제거. product 결정적 키 멱등 흡수로 차감 1회(under-publish 위험/over-publish 무해). 신규 `PaymentConfirmTerminalResendMetrics`. 결정적 주입 시드(`commitTransaction` 1회 실패)로 #6 복구 실증; #7이 설계 S2 가설 반증 — EOS 커밋 실패는 AfterRollbackProcessor(9회·DLQ 미진입) 경로 + 재발행도 같은 EOS tx라 지속 실패 시 완전 유실 → TC-13-FOLLOW-7. 3태스크, 단위 457+통합 39 PASS, discuss R2·plan R1·ship pass critical0/major1 doc-sync/minor3, 2026-06-22, 이슈/브랜치 #112) — `docs/archive/confirm-approved-resend-gap/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
