# Self-Improvement Loop Plan

> 자기 주도 코드/문서 개선 루프 운영 가이드.
> 커밋 X · 멈춤 X · 리뷰 → 작업 → 리뷰 → 수정 → 다시 리뷰 사이클.
> 작성: 2026-04-27 (MSA-TRANSITION + PRE-PHASE-4-HARDENING 봉인 직후)

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

## 2. 검토 영역 큐 (우선순위)

이번 세션 결과물부터 → 코드베이스 sniff 로 확장.

| # | 영역 | 검토 관점 |
|---|---|---|
| Q1 | `docs/context/` 12 파일 | 코드와 어긋난 사실, 빠진 디테일, 다이어그램 누락 |
| Q2 | `docs/archive/{msa-transition,pre-phase-4-hardening}/COMPLETION-BRIEFING.md` | 인덱스 정확성, archive 내부 링크 |
| Q3 | `scripts/smoke/infra-healthcheck.sh` 와 가이드 | 실 docker 컨테이너명 매칭, 커버리지 빈틈 |
| Q4 | Flyway 통합본 V1 들 (4서비스) | DDL 누락 컬럼/인덱스, 주석 정합성 |
| Q5 | `.claude/skills/_shared/personas/`, `protocols/`, `agents/` | 옛 3전략 어조 잔재 추가 검색 |
| Q6 | payment-service 비동기 confirm 코드 (`OutboxAsyncConfirmService`, `OutboxRelayService`, `OutboxImmediateEventHandler`, `ConfirmedEventConsumer`, `PaymentConfirmResultUseCase`) | Javadoc · LogFmt key · 예외 분류 · dead code · 메서드 분리 가능성 |
| Q7 | pg-service `PgConfirmService`, `PgEventPublisher`, 벤더 Strategy 3종 (Toss/NicePay/Fake) | 동일 |
| Q8 | product-service `StockCommitConsumer` / `StockRestoreConsumer` + dedupe | 동일 |
| Q9 | 모든 `core/log/LogFmt` + `EventType` enum | key 일관성, 누락된 ERROR 승격 지점 |
| Q10 | 모든 `*.exception.*` 클래스 | 예외 계층 정합성, 메시지 일관성, 사용처 grep |
| Q11 | `*Test*` 명명 + JaCoCo 제외 정책 + assertion 깊이 | 누락된 contract test, 빈약한 assertion |
| Q12 | `application-*.yml` 4서비스 × 3 profile | 누락된 traceparent 설정, dedupe TTL 명시 누락, default 안전성 |
| Q13 | `docker/docker-compose.*.yml` + `observability/` | healthcheck 일관성, 의존 순서, prometheus scrape |
| Q14 | `gateway/`, `eureka-server/` 코드 | 라우팅 정확성, 헬스 노출 |

큐가 끝나면 다시 Q1 부터 다른 관점(Q1' = readability, Q1'' = 도식 보강)으로 회전 + 신규 영역 추가.

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
- **archive 내부 손대지 X** — 역사 기록 보존 (메모리 룰)
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

5~10 라운드마다 누적 요약 한 번.

## 7. 도구 활용

- **검토**: 직접 `Read` + `Grep` + `Bash` 로 grep. critic / domain-expert 관점은 main thread 에서 직접 적용
- **수정**: `Edit` 우선, 본문 재작성 시 `Write`
- **태스크 추적**: `TaskCreate / TaskUpdate` — 큐 항목 단위
- **테스트**: `./gradlew :<svc>:test` (모듈 단위 우선). 영향 큰 변경만 `./gradlew test`
- **subagent**: 사용자 명시 없으면 미사용

## 8. 큐 한 바퀴 완료 후

(A) Q1' (같은 영역 다른 관점) 으로 회전 + (B) 신규 영역(Q15~) 추가 — 두 옵션 번갈아.

신규 영역 후보:
- 도메인 entity 의 빌더 패턴 일관성
- 메트릭 카운터 명명 (`*_total` / `*_seconds` Prometheus 컨벤션)
- Thymeleaf 템플릿 (payment-service admin)
- `static/` 결제 UI 페이지 (checkout · success · fail)
- gradle build 캐시 / 빌드 시간 최적화 여지

## 9. 변경 시작 전 사전 sniff

R1 시작 전 1회 실행:
```bash
git status                                                 # 깨끗한 상태 확인
git log --oneline -10                                      # 직전 커밋 컨텍스트
./gradlew test 2>&1 | tail -5                              # baseline PASS 확인
grep -rln "TODO\|FIXME\|XXX" payment-service pg-service product-service user-service --include="*.java" | head -20
```

baseline 이 깨져 있으면 그 자리에서 수정 후 시작.

## 10. 종료 시 처리 (사용자 stop 신호 시)

- 마지막 라운드 결과 보고
- 누적 변경 요약 (`git status` + 파일별 1줄)
- 사용자 결정 — 커밋 / 추가 작업 / 일부 revert 등
- 본 SELF-LOOP.md 는 **본 루프 동안 잠정 자료** — 작업 종료 후 사용자 결정으로 보존 또는 삭제
