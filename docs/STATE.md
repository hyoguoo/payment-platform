# 현재 작업 상태

> 최종 수정: 2026-07-10 (DLQ-QUARANTINE-RECOVERY execute Task 2 완료 → Task 3 대기)

## 활성 작업

- **토픽**: DLQ-QUARANTINE-RECOVERY (격리 결제 안전 종결 + 유실 메시지 재주입 수동 복구)
- **단계**: execute — Task 3 대기 (Task 1·2 완료)
- **이슈/브랜치**: #122
- **산출물**: `docs/topics/DLQ-QUARANTINE-RECOVERY.md` (설계) · `docs/DLQ-QUARANTINE-RECOVERY-PLAN.md` (8 태스크)

## 재개 메모

Task 1(복구 전용 조건부 보상 — `StockRecoveryCompensationResult`/`StockCachePort.compensateIfDecremented`/`stock_compensation_if_decremented.lua`/`StockCacheRedisAdapter`/`FakeStockCachePort`), Task 2(격리 복구 도메인 전이 `PaymentEvent.failFromQuarantine` — QUARANTINED 전용 가드, 정상 `fail()`과 물리적 분리, 신규 에러코드 `INVALID_STATUS_TO_FAIL_FROM_QUARANTINE`) TDD 완료, 479 전체 PASS. Task 3(CAS 조건부 저장)부터 이어서 TDD 실행. 주의: 새 포트 메서드는 Fake 갱신 동반(컴파일), Task 3 CAS 는 event+order 동조 저장(기존 `saveOrUpdate` 가 event·order 를 별도 두 단계로 save 하므로 affected=1 일 때만 같은 TX 에서 order 자식 행도 반영), Task 4 는 보상 TX 밖·전이+저장 단일 TX, Task 7 retention 은 `create-topics.sh` 실적용.

## 최근 완료

- **DOCS-CONSISTENCY-OVERHAUL** (문서 전수 정합 개선 — 에이전트 문서 22파일 + README/GUIDE + 위키 25페이지를 사실 목록 28건(전건 소스 파일:라인 재확인) 기반 진단→정정. **소스-온리 근거 룰**(문서 상호 인용 불인정)은 discuss 게이트 critical(메인의 기준 예문이 stale CONFIRM-FLOW 를 인용해 위키의 참인 서술 — outbox 발행 실패 시 롤백으로 PENDING 복귀 — 를 "틀린 사실"로 뒤집을 뻔)로 실증돼 채택. outbox 발행 실패 복구 stale 클러스터(REQUIRES_NEW 선점·IN_FLIGHT 유지 서술) 전 문서 정정 + FAILED dead-terminal·attempt SoT·stock-committed key(productId) 동기화, TODOS/CONCERNS 3분류 정리(완료 32건 삭제·수용 한계 보존), README 배너 사실화(Phase 6 완료·단위861/통합59), 위키 본문 현행화+구조 불변 문체 교정+실이력 서사 9곳(structured-logging 은 삭제된 Logstash/마스킹 스택→Loki/Promtail 전면 재작성, state-management 는 RETRYING/RecoveryDecision 시대 본문 역사 강등+EOS 컨슈머 절 신설). doc-review 4관점 3라운드(R1 기술 정확성 FAIL 17건 포함) 전 관점 PASS + 기계 검사(링크·stale 마커) 0건. 코드 결함 후보 4건 TODOS 등재만: [PAYMENT-OUTBOX-INFLIGHT-UNUSED]·[STRUCTURED-LOGGING-MASKING-GAP]·[PAYMENT-STATUS-TRIGGER-DETECT-DEAD-BRANCH]·[PG-RETRY-BACKOFF-OFF-BY-ONE]. 재발 방지 5종(ship-ready/context-update/workflow-ship/writing/doc-review) 명문화. 19태스크, 단위 861·통합 59 PASS + checkstyle/spotbugs(Main·Test) 통과, discuss R2(critical 1)·plan R2(major 3)·ship 리뷰 pass(minor 6 전건 수정). **위키 20파일은 미커밋 — 사용자가 `../payment-platform.wiki/` 에서 검토 후 커밋·push 필요.** 2026-07-07, 이슈/브랜치 #120) — `docs/archive/docs-consistency-overhaul/COMPLETION-BRIEFING.md`
- **FAULT-INJECTION-RESILIENCE** (서비스·DB·Redis 가용성 알람 + docker stop 완전 다운 정합 거동 실증 — 가용성 사각을 4서비스 `DependencyHealthMetrics` 직접 폴링 게이지(`dependency_up{component}`, 2s 타임아웃 가드, payment redis dedupe/stock 2분리, last-poll staleness)로 메우고 신규 `availability.yml`(ServiceDown/DependencyDown/DependencyHealthStale + `absent()` backstop)로 탐지. 완전 다운 정합은 `@EmbeddedKafka`+전용 MySQL+`@MockitoSpyBean doThrow` 통합테스트로 **DLQ 유실0**(load-bearing, 시간 무관) 고정 — 컨테이너 stop 은 Hikari 30s×5 비결정성으로 금지. **검증이 두 갭 발견**: (1) execute 중 implementer 도메인 변경(`resetToReady`→order NOT_STARTED 복원, EXPIRED 종결 활성화)을 domain-expert **critical**로 롤백 — 설계 전제 "IN_PROGRESS→READY→EXPIRED 2단 마스킹"이 실제론 order EXECUTING 잔류로 `expire()` 차단(EXPIRED 도달 불가·READY 영구 잔류 + 만료 batch poison-pill)임을 실측해 CONCERNS **L-14** 등재(L-10 정책 갭). EXPIRED 종결화는 D7 가드가 TQ-1 복구를 봉쇄해 비종결 READY보다 나쁨이 롤백 근거. (2) ship **라이브 드릴**이 stale jar 배포 갭(bootJar 선행 누락 → 게이지 빈 미생성) + user `@EnableScheduling` 누락(폴러 미실행 → 알람 영구 오발화)을 잡음 — promtool/통합테스트 사각. no-divergence(over-sell 0)는 공허 단정 제외, **신규 복구 로직 없음**(TQ-1/TC-3 위임). 7태스크, payment 단위465+통합42·pg330·product50·user9 PASS + promtool 25케이스 + 라이브 ServiceDown·DependencyDown{db,redis-dedupe} 발화/해소 실측, discuss R3·plan R2(critical 1 reconcile)·execute 도메인 critical 1(롤백)·ship 코드리뷰 R2(critical 1 user scheduler), 2026-06-30, 이슈/브랜치 #118) — `docs/archive/fault-injection-resilience/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
