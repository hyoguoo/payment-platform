# Self-Improvement Loop Plan

> 자기 주도 코드/문서 개선 루프 운영 가이드.
> 커밋 X · 멈춤 X · 리뷰 → 작업 → 리뷰 → 수정 → 다시 리뷰 사이클.
> 작성: 2026-04-27 (MSA + PRE-PHASE-4-HARDENING 봉인 직후)
> 갱신: 2026-04-27 — 재고 모델 정리 4 커밋 완료 후 baseline 재정리. **R1 진입 직전 상태**.

## 0. 진입 직전 baseline

| 항목 | 상태 |
|---|---|
| HEAD | `250e6d72 docs: stock 모델 정리 후속` |
| 직전 4 커밋 | `b94461d1`(stock-redis wiring) · `72e636bc`(payment stock 모델) · `399b81b6`(product redis 제거) · `250e6d72`(시드 + docs) |
| 테스트 | 570 PASS (eureka 1 + gateway 3 + payment 339 + pg 206 + product 20 + user 1) |
| `docs/STATE.md` | 활성 작업 없음 (MSA + PRE-PHASE-4 봉인) |
| 활성 디렉토리 | `docs/topics/` X · `docs/rounds/` X · `docs/phase-gate/` X |
| 새 재고 모델 | redis-stock = payment 단독 선차감 캐시 / product RDB = SoT / restore 토픽 폐기 / `seed-stock.sh` 부팅 1회 |
| 미해결 docs 항목 | `FLOW-ANALYSIS.md` 의 D2/D9/D14(major) + D1/D3/D10/D13/D15/D17(minor) — R1 진입 시 우선 처리 |

## 1. 사이클 구조 (한 라운드 = 4 step)

```
[Step A — Review]   현행 영역 1개 선택 + 페르소나 검토
        ↓ findings 목록
[Step B — Work]     Tier 분류 후 수정
        ↓ 변경 diff
[Step C — Re-review] 같은 영역 재검토 (Critic + Domain Expert 관점)
        ↓ residual findings or PASS
[Step D — Refine]   남은 항목 처리. PASS 면 다음 영역으로 이동
        ↓
    다시 Step A (다음 영역)
```

각 라운드 종료 시: 변경 diff 요약 + `./gradlew test` 결과 + 다음 영역 한 줄 보고.

## 2. 검토 영역 큐 (우선순위 — 재정렬)

| # | 영역 | 검토 관점 | 비고 |
|---|---|---|---|
| **Q1** | `docs/context/PAYMENT-FLOW.md` + 관련 | FLOW-ANALYSIS **D1 / D2 / D3** 처리 — publish 시그니처, PaymentConfirmCommandMessage 필드, monolith.confirm.enabled 라인 정리 | 시작점 |
| **Q2** | `docs/context/INTEGRATIONS.md` + `ARCHITECTURE.md` | FLOW-ANALYSIS **D9 / D17** — pg-service 자체 retry 흐름 추가, DLQ 발행 출처 정정 | |
| **Q3** | `docs/context/CONFIRM-FLOW-ANALYSIS.md` + `PITFALLS.md` | FLOW-ANALYSIS **D14** — QUARANTINED status 폴링 결과 = PROCESSING (운영 영향) + admin 복구 필요성 명시 | |
| **Q4** | `docs/context/CONFIRM-FLOW-ANALYSIS.md` 또는 `CONFIRM-FLOW-FLOWCHART.md` | FLOW-ANALYSIS **D10 / D13** — pg-service dedupe 모델 차이(markSeen vs two-phase lease), retry/DLQ attempt≥4 정책 | |
| **Q5** | `docs/context/STACK.md` | FLOW-ANALYSIS **D15** — `./gradlew :payment-service:integrationTest` 실행해 Flyway 적용 확인 후 명시 | 실 실행 필요 |
| **Q6** | `docs/context/` 12 파일 (전체 sweep) | 위 D 항목들 외 stock 정리 후 추가로 어긋난 사실, 빠진 디테일, 다이어그램 누락 | |
| **Q7** | `docs/archive/{msa-transition,pre-phase-4-hardening}/COMPLETION-BRIEFING.md` | 인덱스 정확성, archive 내부 링크 (archive 내부 본문은 history 라 손 안 댐 — 인덱스 라벨만) | |
| **Q8** | `scripts/smoke/infra-healthcheck.sh` + `seed-stock.sh` + `compose-up.sh` | 실 docker 컨테이너명 매칭, 커버리지 빈틈, 시드 운영 안전성 | |
| **Q9** | Flyway 통합본 V1 들 (4서비스) | DDL 누락 컬럼/인덱스, 주석 정합성. payment V1 의 `quarantine_compensation_pending` 같은 dead 컬럼 검토 | |
| **Q10** | `.claude/skills/_shared/personas/`, `protocols/`, `agents/` | 옛 어조 잔재 (FailureCompensationService / StockRestore* / StockEventPublishingListener / StockCacheWarmup) 추가 검색 | |
| **Q11** | payment-service 비동기 confirm 코드 | `OutboxAsyncConfirmService` / `OutboxRelayService` / `OutboxImmediateEventHandler` / `ConfirmedEventConsumer` / `PaymentConfirmResultUseCase` — Javadoc · LogFmt key · 예외 분류 · dead code · 메서드 분리 가능성 |
| **Q12** | pg-service `PgConfirmService` / `PgEventPublisher` / 벤더 Strategy 3종 | 동일 |
| **Q13** | product-service `StockCommitConsumer` + `StockCommitUseCase` (restore 사라진 후 단일 경로) + `JdbcEventDedupeStore` | 단일화 후 dead code, dedupe 정책 |
| **Q14** | 모든 `core/log/LogFmt` + `EventType` enum | key 일관성, dead EventType (`STOCK_RESTORE_*` 등) 제거, 누락된 ERROR 승격 지점 |
| **Q15** | 모든 `*.exception.*` 클래스 | 예외 계층 정합성, 메시지 일관성, 사용처 grep |
| **Q16** | `*Test*` 명명 + JaCoCo 제외 정책 + assertion 깊이 | 누락된 contract test, 빈약한 assertion |
| **Q17** | `application-*.yml` 4서비스 × 3 profile | 누락된 traceparent 설정, dedupe TTL 명시 누락, default 안전성. 새 `payment.cache.stock-redis` 설정의 운영 가이드 |
| **Q18** | `docker/docker-compose.*.yml` + `observability/` | healthcheck 일관성, 의존 순서, prometheus scrape, redis-stock seed 의존 |
| **Q19** | `gateway/`, `eureka-server/` 코드 | 라우팅 정확성, 헬스 노출 |
| **Q20** | `NicepayReturnController` + `PaymentAdminController` | PG returnUrl 처리 분기, admin 페이지 정합성 |

큐가 끝나면 다시 Q1' (같은 영역 다른 관점 — readability, 도식 보강) 회전 + 신규 영역 추가.

## 3. Tier 분류 (Step B)

| Tier | 정의 | 처리 |
|---|---|---|
| **T1 — 명백한 결함** | 깨진 링크, 죽은 참조, 오타, 명명 오류 | 즉시 수정 |
| **T2 — 일관성 개선** | LogFmt key 통일, 예외 분류 정렬, 컨벤션 어김 | 즉시 수정. 코드 의미 변경 X |
| **T3 — 도메인 강화** | 가드 보강, 테스트 추가, 메트릭 추가 | 의도 한 줄 보고 후 수정. 매번 회귀 테스트 |
| **T4 — 구조 변경 (메서드 분리·클래스 추출 등)** | private 메서드 추출, 클래스 책임 분리, 인터페이스 도입 — **큰 기능 변경 없는 한 confirm 없이 진행** | 즉시 수정. 매번 회귀 테스트 |

**T4 의 경계** — confirm 없이 가능한 범위:
- ✅ private 메서드 추출 (try 블록 패턴 룰 반영 등)
- ✅ 같은 패키지 내 class split (응집도 개선)
- ✅ 도우미 record/value object 추출 (불변성 강화)
- ✅ 시그니처 정리 (파라미터 묶기, 반환 타입 좁히기) — 단 외부 사용처 적은 경우
- ✅ dead code/dead enum/dead config 제거 (사용처 0 확인 후)
- ❌ public API 변경 (controller endpoint, port 인터페이스 시그니처) — confirm 필요
- ❌ Kafka 토픽 / DB 스키마 변경 — confirm 필요
- ❌ ADR 변경 — confirm 필요
- ❌ 새 의존성 추가 — confirm 필요

## 4. 멈춤 방지 룰

- "변경할 게 없다" 결론 금지 → 항상 다음 큐 항목으로 이동
- 같은 영역 3 라운드 PASS 면 큐 다음으로
- 큐가 한 바퀴 돌면 다시 Q1 부터 새 관점으로 회전
- 사용자 명시적 stop 만 종료

## 5. 안전 가드 (절대 어기지 않음)

- **커밋 절대 X** (사용자 명시 — 본 루프 동안)
- **`git push` X**, `git rm` 신중
- **archive 내부 본문 손대지 X** — 역사 기록 보존 (단 인덱스 라벨/링크는 갱신 가능)
- **외부 시스템 영향 X** — docker compose down/up, GitHub API X
- **`docs/STATE.md` / `docs/topics/` / `docs/<TOPIC>-PLAN.md` 손대지 X** — 활성 작업 없는 상태 유지
- **테스트 fail 시 즉시 수정** — fail 채로 다음 사이클 X
- **public API · DB schema · Kafka topic · ADR · 새 의존성** — confirm 한 줄 후 진행

## 6. 라운드 사이 보고 형식

각 라운드 끝나면:
```
🔄 R<N>·Q<X> [<영역명>]
  Findings: T1=N · T2=N · T3=N · T4=N
  적용: <짧은 변경 목록>
  Verify: gradle test PASS / N/A · git status: M=N file(s)
  Next: Q<X+1> [<영역명>]
```

5~10 라운드마다 누적 요약 한 번. FLOW-ANALYSIS.md 의 D 항목 RESOLVED 표시도 그때 갱신.

## 7. 도구 활용

- **검토**: 직접 `Read` + `Grep` + `Bash` 로 grep. critic / domain-expert 관점은 main thread 에서 직접 적용
- **수정**: `Edit` 우선, 본문 재작성 시 `Write`
- **태스크 추적**: `TaskCreate / TaskUpdate` — 큐 항목 단위
- **테스트**: `./gradlew :<svc>:test` (모듈 단위 우선). 영향 큰 변경만 `./gradlew test`
- **subagent**: 사용자 명시 없으면 미사용

## 8. 큐 한 바퀴 완료 후

(A) Q1' (같은 영역 다른 관점) 으로 회전 + (B) 신규 영역(Q21+) 추가 — 두 옵션 번갈아.

신규 영역 후보:
- 도메인 entity 의 빌더 패턴 일관성
- 메트릭 카운터 명명 (`*_total` / `*_seconds` Prometheus 컨벤션)
- Thymeleaf 템플릿 (payment-service admin)
- `static/` 결제 UI 페이지 (checkout · success · fail)
- gradle build 캐시 / 빌드 시간 최적화 여지
- `IdempotencyStoreRedisAdapter` 의 checkout 멱등성 검증 (Caffeine vs Redis 이중 가드)
- `PaymentReconciler` 의 단순화된 책임 단독 검증 (stock 부분 제거 후)
- AOP `@PublishDomainEvent` / `@PaymentStatusChange` 의 적용 누락 사용처 검색

## 9. 사전 sniff (R1 진입 직전 1회)

baseline 이 깨져 있으면 그 자리에서 수정 후 시작:

```bash
git status                                                 # clean 또는 untracked 만 (FLOW-ANALYSIS.md / SELF-LOOP.md 만)
git log --oneline -6                                       # 직전 4 커밋 + 봉인 커밋
./gradlew test 2>&1 | tail -5                              # 570 PASS baseline 확인
grep -rln "TODO\|FIXME\|XXX" payment-service pg-service product-service user-service --include="*.java" | head -20
grep -rn "FailureCompensationService\|StockRestore\|StockSnapshot\|StockCacheWarmup\|StockCacheDivergence\|StockEventPublishingListener" payment-service/src pg-service/src product-service/src --include="*.java" 2>/dev/null | head -10   # 잔재 0 확인
```

## 10. 종료 시 처리 (사용자 stop 신호 시)

- 마지막 라운드 결과 보고
- 누적 변경 요약 (`git status` + 파일별 1줄)
- 사용자 결정 — 커밋 / 추가 작업 / 일부 revert 등
- 본 `SELF-LOOP.md` + `FLOW-ANALYSIS.md` 는 **본 루프 동안 살아있는 자료** — 작업 종료 후 사용자 결정으로 보존 또는 삭제
