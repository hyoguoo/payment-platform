# 현재 작업 상태

> 최종 수정: 2026-07-02 (DOCS-CONSISTENCY-OVERHAUL Task 1 완료 — 사실 목록 + 진단 리포트 뼈대)

## 활성 작업

- **주제**: DOCS-CONSISTENCY-OVERHAUL — 문서 전수 정합 개선 (docs/context + README + 위키, 코드 대조 정정·완료 항목 정리·문체 교정)
- **단계**: execute
- **활성 태스크**: Task 2: 진단 — 플로우·대장·함정 5파일 (`CONFIRM-FLOW.md`/`PAYMENT-FLOW.md`/`TODOS.md`/`CONCERNS.md`/`PITFALLS.md`)
- **이슈/브랜치**: #120
- **파일**: docs/topics/DOCS-CONSISTENCY-OVERHAUL.md / docs/DOCS-CONSISTENCY-OVERHAUL-PLAN.md / docs/DOCS-CONSISTENCY-OVERHAUL-DIAGNOSIS.md

## 재개 메모

- plan 게이트 2R 양측 pass (2026-07-02). 19태스크 — 진단(1~6) → 정정(7~12) → 위키 5배치(13~17, Task 7~10 완료 후 착수) → 재발 방지(18) → 최종 검증(19).
- Task 1 완료 — `docs/DOCS-CONSISTENCY-OVERHAUL-DIAGNOSIS.md` 생성(항목 형식·심각도·기본값 층위 규칙 확정 + 사실 목록 28건(F1~F28) + 표본 12건 판정, 전건 소스 파일:라인 채록). Task 2~6 은 이 리포트 §4 플레이스홀더를 채워나간다.
- 주의: 사실 판정 근거는 소스 파일:라인만 (문서 상호 인용 불인정). 위키는 파일 수정만, 커밋은 사용자.

## 최근 완료

- **FAULT-INJECTION-RESILIENCE** (서비스·DB·Redis 가용성 알람 + docker stop 완전 다운 정합 거동 실증 — 가용성 사각을 4서비스 `DependencyHealthMetrics` 직접 폴링 게이지(`dependency_up{component}`, 2s 타임아웃 가드, payment redis dedupe/stock 2분리, last-poll staleness)로 메우고 신규 `availability.yml`(ServiceDown/DependencyDown/DependencyHealthStale + `absent()` backstop)로 탐지. 완전 다운 정합은 `@EmbeddedKafka`+전용 MySQL+`@MockitoSpyBean doThrow` 통합테스트로 **DLQ 유실0**(load-bearing, 시간 무관) 고정 — 컨테이너 stop 은 Hikari 30s×5 비결정성으로 금지. **검증이 두 갭 발견**: (1) execute 중 implementer 도메인 변경(`resetToReady`→order NOT_STARTED 복원, EXPIRED 종결 활성화)을 domain-expert **critical**로 롤백 — 설계 전제 "IN_PROGRESS→READY→EXPIRED 2단 마스킹"이 실제론 order EXECUTING 잔류로 `expire()` 차단(EXPIRED 도달 불가·READY 영구 잔류 + 만료 batch poison-pill)임을 실측해 CONCERNS **L-14** 등재(L-10 정책 갭). EXPIRED 종결화는 D7 가드가 TQ-1 복구를 봉쇄해 비종결 READY보다 나쁨이 롤백 근거. (2) ship **라이브 드릴**이 stale jar 배포 갭(bootJar 선행 누락 → 게이지 빈 미생성) + user `@EnableScheduling` 누락(폴러 미실행 → 알람 영구 오발화)을 잡음 — promtool/통합테스트 사각. no-divergence(over-sell 0)는 공허 단정 제외, **신규 복구 로직 없음**(TQ-1/TC-3 위임). 7태스크, payment 단위465+통합42·pg330·product50·user9 PASS + promtool 25케이스 + 라이브 ServiceDown·DependencyDown{db,redis-dedupe} 발화/해소 실측, discuss R3·plan R2(critical 1 reconcile)·execute 도메인 critical 1(롤백)·ship 코드리뷰 R2(critical 1 user scheduler), 2026-06-30, 이슈/브랜치 #118) — `docs/archive/fault-injection-resilience/COMPLETION-BRIEFING.md`
- **ALERTING-RULES-AND-FAULT-DRILL** (Prometheus 알람 규칙 인프라 + Toxiproxy 장애 주입 발화 실증 — rule 평가만 도입(Alertmanager 미도입): `prometheus.yml` rule_files → `observability/prometheus/rules/*.yml` 로드, `/api/v1/{rules,alerts}` 평가까지. 3그룹 7규칙(coordinator/guard-skip/dlq), `promtool test rules` 16케이스 회귀 고정. Toxiproxy latency 전용 드릴 프로파일. **라이브 실증이 promtool 사각 dead branch 발견** — broker 완전 정지 시 kafka_brokers 는 0 아닌 absent → `absent(kafka_brokers)` 3분기 보강 + PITFALLS #24. 단일 broker 구조 한계로 코디네이터/EOS 라이브 발화는 promtool+통합테스트 격하. 애플리케이션 코드 무변경. 7태스크, 18커밋, 2026-06-27, 이슈/브랜치 #116) — `docs/archive/alerting-rules-and-fault-drill/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
