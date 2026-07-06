# 문서 전수 정합 개선 — 진단 리포트

> 최종 갱신: 2026-07-06 (§6.4 doc-review 라운드 1 수정 2차 — README.md 시크릿 안내·약어 각주·문체 + PAYMENT-FLOW-GUIDE.md 서사(존댓말/Phase 축 개칭/내부 ID 제거)·문체 + 위키 잔여 5파일(`compensation-tx`/`idempotency`/`scenario-test`/`pg-strategy`/`stock-cache-recovery`) + 전 위키 규격 sweep(워커→Worker/~를 통해/내부 토픽 표기/dead-end 경로/명사형 불릿/약어 풀이/평가 형용사/mermaid 화살표·중간점/한 줄 한 문장) 반영 — 상세는 §6.4. 메인 저장소분(README/GUIDE/DIAGNOSIS/STATE)만 커밋, 위키는 파일 수정까지만(커밋은 사용자). PLAN.md Task 19 완료 결과는 아직 미기록(재검수 라운드 2 이후 종결). 이전: 2026-07-03 (§6 doc-review 라운드 1 수정 1차 — Task 19 최종 검증 스윕의 doc-review 4관점 검수에서 기술 정확성 + 동일 파일 서사·독자 관점 FAIL 로 지적된 위키 11파일(`outbox-pattern`/`architecture`/`pg-confirm-flow`/`message-delivery-and-dedupe`/`structured-logging`/`state-management`/`msa-transition`/`metrics`/`async-outbox`/`outbox-channel-dispatch`/`trace-propagation`) 정정 + `docs/context/TODOS.md` 코드 확인 필요 항목 `[PG-RETRY-BACKOFF-OFF-BY-ONE]` 신규 등재(위키 파일 수정까지만, 커밋은 사용자) — 상세는 §6. PLAN.md Task 19 완료 결과에는 아직 미기록(수정 2차 + 재검수 후 종결). 이전: 2026-07-03 (Task 18 — 재발 방지 장치: `docs/context/**`가 아닌 스킬·체크리스트 5개(`_shared/checklists/ship-ready.md`/`context-update/SKILL.md`/`workflow-ship/SKILL.md`/`_shared/conventions/writing.md`/`doc-review/SKILL.md`)에 이번 토픽의 재발 방지 결정을 명문화 — 이 진단 리포트가 채록한 사실(대장 3분류·헤더 동기화·소스 근거 원칙·문체 기준)이 다음 토픽에서도 유지되도록 규칙으로 승격. §4.1.4 L-14(CONCERNS.md 3분류 (b) 혼합 항목 모범 사례) 를 `context-update` SKILL.md 예시 근거로 인용 완료. `docs/context/**` 파일은 변경하지 않음(진단 대상 문서가 아니라 절차 문서). 상세는 `docs/DOCS-CONSISTENCY-OVERHAUL-PLAN.md` Task 18 완료 결과 참고. 이전: 2026-07-03 (Task 17 — 위키 5차·잔여 6페이지 정정(파일 수정까지만, 커밋은 사용자): `trace-propagation.md`/`ai-workflow.md`/`Home.md`/`_Sidebar.md`/`_Footer.md`/`Benchmark-Report.md` §4.5.8~4.5.11 전건 종결 — **위키 정정 단계(Task 13~17) 전체 완결**. 6페이지 전부 진단 판정("변경 불요·보존")대로 재확인돼 콘텐츠 실질 변경 0건. Task 13 이월 확인 건(세션 시작 시점 미커밋 상태였던 `pg_inbox.stored_traceparent` RDB 복원 서술, `trace-propagation.md`/`outbox-channel-dispatch.md`/`pg-confirm-flow.md` 3파일)을 `TraceparentExtractor`/`V4__add_pg_inbox_stored_traceparent.sql`/`PgInboxPollingWorker.processWithRestoredContext`/`PgInboxPendingService.insertPendingAndPublish`/`PaymentConfirmConsumer.consume` 소스 전건 대조로 검증 완료 — 서술 정확, 그대로 유지. 위키 25페이지 내부 링크 스윕(외부 URL·문서 내부 앵커 제외) — 페이지 간 링크 파손 0건, `compensation-tx.md:213` 의 `.md` 확장자 포함 링크(나머지 24개 링크와 표기 불일치) 1건만 확장자 제거로 정정(범위 밖 파일이나 링크 스윕 대상이라 서식만 통일, 내용 변경 없음). `ai-workflow.md` 는 에이전트 모델 표가 "고지능/최고지능/중간지능" 추상 등급만 사용해 2026-07-02 Claude 5 모델 티어링 조정 이후에도 갱신 격차 없이 유효함을 재확인. 이전: 2026-07-03 (Task 16 — 위키 4차 5페이지 정정(파일 수정까지만, 커밋은 사용자): `architecture.md`/`msa-transition.md`/`event-driven-choreography.md`/`metrics.md`/`structured-logging.md` §4.5.1~4.5.5 전건 종결. `structured-logging.md`(13페이지 중 최대 오류) 는 로깅 파이프라인 다이어그램(Console appender→docker 로깅 드라이버→Promtail→Loki 전면 재작성)·TraceId 전파(OTel Micrometer Tracing 자동 MDC 로 교체)·Logstash 연동(Promtail 라벨 기반 수집으로 교체)·민감정보 마스킹(과거 구현 기록으로 격하, 대체 없음을 코드 확인 필요 항목으로 고지)·설계 결정 요약 4개 섹션 전면 재작성(S1 critical). `architecture.md` domain 트리의 `RecoveryDecision.java` 제거 + `PaymentEventStatus` 8종 정정 + 핵심 설계 결정 표 FCG 행을 "설계 완료, 미연결" 로 정정. `msa-transition.md` 토폴로지 `redis-stock` 연결(`Prod-->RedS`→`Pay-->RedS`) 정정으로 `architecture.md` 와의 모순 해소 + "후속 예정" 절 완료분/미착수분 분리. `metrics.md` 배너·메트릭 목록에 `dependency_up` 게이지 + 알람 규칙 4그룹 반영, 삭제된 `payment_health_max_retry_reached_total` 제거, `TossApiMetrics` pg-service 소속 명시, "비동기 플로우의 관측 맹점" `RETRYING` 분기를 `QUARANTINED` 로 교체, 신규 "알람 규칙" 절 신설(Home.md 신규 페이지 검토 결과 흡수). `event-driven-choreography.md` 는 재검증 결과 변경 없음(보존). 이전: 2026-07-03 (Task 15 — 위키 3차 5페이지 정정(파일 수정까지만, 커밋은 사용자): `state-management.md`/`retry-recovery.md`/`scenario-test.md`/`cross-validation.md`/`pg-strategy.md` §4.4.10~4.4.12 + §4.5.6·4.5.7 전건 종결. `state-management.md` 배너 "RecoveryDecision + FCG + 격리 사이클은 유지된다"(12페이지 중 최대 단일 오류)를 "EOS 컨슈머 모델로 전면 대체됨"으로 재작성 + "PaymentEvent 상태 머신"을 8상태(RETRYING 제거)로 전면 재작성 + "RecoveryDecision"·"격리 전 최종 확인"·"복구 사이클 전체 플로우" 3섹션을 "Phase 5 모델(폐기)" 단일 섹션으로 병합 격하 + 신규 "현재 모델 — EOS 컨슈머" 절(3분기 플로우차트, PaymentReconciler, FCG 미연결) + "생성 시점의 동기화, 이후의 독립 진행" 절(Outbox 발행 추적과 PaymentEvent 결과 반영의 분리 명시) 신설(S1 critical). "복구 스케줄러 구성" 표에 PaymentReconciler 신규 행 + OutboxImmediateWorker→OutboxImmediateEventHandler 오기 정정(S2 후보 확정). `scenario-test.md` OutboxProcessingServiceTest 섹션 전체 폐기 격하(S1 critical, 배너 고지 범위 밖 신규 확정) + FakeProductRepository 포트 폐기 각주(S1). `pg-strategy.md` 배너를 "클래스 전면 소멸+pg-service 재구성" 규모로 구체화(S1). `retry-recovery.md`/`cross-validation.md` 는 재검증 결과 보존, retry-recovery.md 링크 설명만 현행화. 부수 정정 — `compensation-tx.md` 의 state-management 상호 링크 2건이 섹션 재구성으로 dangling 될 상황을 Rule 1 로 함께 정정. 이전: 2026-07-03 (Task 14 — 위키 2차 4페이지 정정(파일 수정까지만, 커밋은 사용자): `message-delivery-and-dedupe.md`/`idempotency.md`/`compensation-tx.md`/`stock-cache-recovery.md` §4.4.6~4.4.9 전건 종결. `compensation-tx.md` 배너 사실 오류(재고 복구가 product-service 호출로 진화했다는 서술) 정정 + "이 모델의 이후 변화" 절 신설로 RD/RETRYING/FCG 클러스터를 retry-recovery.md 템플릿대로 명확히 역사로 격하(S1 critical). `message-delivery-and-dedupe.md`/`stock-cache-recovery.md` 에 `AfterRollbackProcessor` DLQ 경로 반영(S1/S3). Task 13 이월 항목(outbox-channel-dispatch.md DLQ 임계 모호성)도 소스 재확인으로 해소 — §4.4.2 확정 갱신. `idempotency.md` 는 문체 교정만(보존). 이전: 2026-07-03 (Task 13 — 위키 1차 5페이지 정정(파일 수정까지만, 커밋은 사용자): `outbox-pattern.md`/`outbox-channel-dispatch.md`/`pg-confirm-flow.md`/`async-outbox.md`/`tx-scope.md` §4.4.1~4.4.5 전건 종결. 빈 헤더 삭제 + `FAILED` dead-terminal 각주(outbox-pattern) + topic 문체 기준 예문 실반영(S5) + `pg.outbox.channel.worker-count` 누락 행 추가(outbox-channel-dispatch) + FCG "살아있는 트리거"·"향후 계획" 모순을 "구현됐으나 미연결"로 통일(pg-confirm-flow, PAYMENT-FLOW.md §4.9 와 결론 통일) + `scheduler.outbox-worker.*` 층위 위반 각주(async-outbox, `parallel-enabled` 와 동일 축) + `RecoveryDecision` 전방 참조 2곳 정정(async-outbox) + 근거 있는 서사 섹션 3건 신설(outbox-channel-dispatch/pg-confirm-flow 도입 배경, async-outbox 말미 회고). tx-scope.md 는 재검증 결과 변경 없음(보존). outbox-channel-dispatch DLQ 임계 모호성(S2 후보)은 소스 재확인 전이라 미해결 보존. 세션 시작 시점에 이미 존재하던 uncommitted 변경(3개 파일의 `stored_traceparent` 관련 서술, 이 태스크가 작성하지 않음)은 DIAGNOSIS 항목 밖이라 건드리지 않고 보존 — 상세는 §4.4.5 뒤 완료 노트 참고). 이전: 2026-07-03 (Task 12 — PAYMENT-FLOW-GUIDE 정정: §4.3.2 S1 클러스터 5건(L105/L107/L214-217/L254-255/L294·L307-308) 전건 `docs/context/PAYMENT-FLOW-GUIDE.md` 반영. CONFIRM-FLOW.md/PAYMENT-FLOW.md(Task 7 정정본)와 동일 사실 축으로 정정 — "Kafka 발행 실패 → IN_FLIGHT 유지 → 워커 타임아웃 폴백"이던 서술 전건을 "발행 실패 → relay 단일 TX 롤백 → PENDING 즉시 복귀 → OutboxWorker 5초 주기 배치 재픽업(1차 경로), IN_FLIGHT 5분 타임아웃 회수는 워커 크래시 등 보조 경로"로 정정(§A 시퀀스 단계14/15 각주, §B-2 PUBREC mermaid, §C 회복 경로 색인 표, §D 마스터 플로우차트 노드·엣지). mermaid 노드 라벨 금지 문자(중괄호·중간점·유니코드 화살표·따옴표) 준수 확인. 문체(S5)는 진단대로 수정 대상 없음(구조화 기술 문서 장르, 보존). 문서 헤더에 정정 시점 1줄 추가. `./gradlew test` 대상 아님(문서만, 코드 무변경)). 이전: 2026-07-03 (Task 11 — README 정정: §4.3.1 전건(S1 critical 2건 + S1 minor 1건 + S3 2건 + S5 2건 + S2 1건) `README.md` 반영. 배너 "🚧 진행 중"→"✅ Phase 6 완료" + "589 PASS"→"단위 861 / 통합 59 PASS"(Task 9 실측) + "정합이 안 맞을 수 있음" 경고·막연한 "보상 트랜잭션 자동 회복 layer" 예시 삭제 + Phase 7 예고에 알람 규칙·장애 드릴 인프라 선행 구축 완료 1줄 추가. "주요 해결 과제" 표 "장애 내성 복구 체계" 행을 폐기 개념(`RecoveryDecision`/`canCompensateStock`/FCG 3개) 대신 현행 사실(self-loop 재시도+DLQ 격리+`PaymentReconciler`+`compensateAtomic`)로 재작성. "결제 상태 관리" 캡션 "보상 안전 가드 자체는 유지" 삭제(Phase 5 시점 스냅샷으로 명시, mermaid 는 보존). Outbox 모델 표 `FAILED` dead-terminal 각주 추가. 문체(S5) "이상적"·"최적의" 평가 형용사 + "~를 통해" 번역투 정정. Phase 표기(S2)는 plan 결정대로 README 축 유지, disambiguation 미반영. README 도메인 사실(S1) 반영 내역은 §4.3.3 에 병기(ship domain-expert 대조 입력). `./gradlew test` 861 PASS 재확인(문서 전용, 코드 무변경)). 이전: 2026-07-03 (Task 10 — conventions·smoke 11파일 정정: `docs/context/CONVENTIONS.md`(인덱스) + `conventions/{code-style,error-logging,kafka,testing,transactions}.md` + `docs/smoke/{alert-firing-check,infra-healthcheck,observability-load,observability-walkthrough,trace-continuity-check}.md` §4.2.5/4.2.8~4.2.17 전건 종결 — 실질 변경은 `conventions/transactions.md` 1건(예시 코드에 `transactionManager = "transactionManager"` qualifier 추가 + 명시 근거 1줄 보강), 나머지 10파일은 재검토 후 불일치 0건(보존). **완료 기준 stale 마커 게이트**(Task 7~10 완료 시점, 에이전트 문서 전체 `docs/context/**`+`docs/smoke/**` grep) — `RETRYING`/`StockOutbox`/payment 측 `EventDedupeStore`/`RecoveryDecision`/"REQUIRES_NEW 선점" outbox 서술/Elasticsearch·Logstash/`MaskingPatternLayout` 7종 전건 재검사, 역사 서술 제외 현행 서술 위반 3건 신규 발견 — `ARCHITECTURE.md`(핵심 설계 결정 인덱스의 FCG/RecoveryDecision 두 행이 무표시로 현재형 서술), `STACK.md`(`spring-boot-starter-data-redis` 주석 "pg/payment-side EventDedupeStore" — payment 측엔 그 이름의 Redis 클래스 없음), `CONFIRM-FLOW.md`(§14 VT+MDC 절이 EOS 전환에서 완전 삭제된 `StockOutboxImmediateEventHandler` 를 현재형으로 병기). 3건 모두 Task 7/9 대상 파일이라 직접 정정 + 해당 섹션(4.1.1/4.2.1/4.2.3)에 발견 기록 추가, 헤더 날짜 갱신. `./gradlew test` 재실행 861 PASS(문서 전용 태스크, 코드 무변경)). 이전: 2026-07-03 (Task 9 — 핵심 참조 문서 6파일(+ `stack/flyway-operations.md`) 정정: `docs/context/{ARCHITECTURE,STRUCTURE,STACK,INTEGRATIONS,TESTING,PITFALLS}.md` §4.2/§4.1.5 전건 종결. STRUCTURE.md §빌드 트리거 절을 STACK.md 참조로 교체("`./gradlew test`=단위+통합" 정면 모순 정정) + JaCoCo 설정 위치 정정("모듈별" → 루트 `build.gradle` `subprojects` 공통). STACK.md 스케줄러 활성화 매트릭스에 누락됐던 user-service 행 + 4서비스 공통 `DependencyHealthMetrics` 역할 반영. TESTING.md 테스트 카운트 재실행 갱신(단위 861/통합 59). PITFALLS.md 헤더 시점 동기화(§24 기준) + §17/§18 CONCERNS.md dangling·오기 ID 참조 정정(자연어 설명 병기). S4 중복 4건 전건 SSOT 반영(JaCoCo/빌드 명령/Contract test/CircuitBreaker). `conventions/transactions.md` S1 은 Task 10 범위로 유보 — 미착수. `./gradlew test` 861 PASS 회귀 확인(문서만 변경, 코드 무변경이라 회귀 아님, 스냅샷 갱신 겸용 재실행)). 이전: 2026-07-02 (Task 8 — 대장 문서 정정: `docs/context/TODOS.md`/`docs/context/CONCERNS.md` §4.1.3·§4.1.4 전건 종결. TODOS 구조적 문제 2건("토픽 묶음 계획"·"## 완료" 섹션, `docs/archive/README.md` 완전 중복) 삭제 + 항목별 3분류 적용(✅완료+archive 경로 24건 전체 삭제 — 예비 판정 22건 + 판정표 누락분 2건(TC-13-FOLLOW-7/TC-9, 동일 패턴 적용) / 혼합 3건 해소분 문장 제거·잔여 보존 / 보존 항목 중 TC-7 "한도 초과 시 종결" stale 서술만 정정) + 코드 확인 필요 항목 3건 신규 등재(`[PAYMENT-OUTBOX-INFLIGHT-UNUSED]`/`[STRUCTURED-LOGGING-MASKING-GAP]`/`[PAYMENT-STATUS-TRIGGER-DETECT-DEAD-BRANCH]`, 코드 수정 없음) + "내부 Phase 번호는 README 개발 과정 Phase 와 별개" 1줄 명시. CONCERNS 신규 발견 4건 반영(L92 qualifier stale 정정, L97 ID 오기 FOLLOW-1→FOLLOW-6 정정 + 완료된 FOLLOW-3/4 forward-reference 를 TODOS T4-B `[DE2]` 참조로 교체, C-9 잔여 불릿 분리) + (a) 8건(C-7/C-12/C-11/L-2/L-3/L-6/L-10/L-13) 전체 삭제, 삭제로 발생한 L-5/L-14 내부 상호참조 dangling 2건을 실제 대상으로 교체(부작용 수정). PITFALLS.md §17/§18 의 L2/L6 dangling ID 참조는 지시대로 Task 9 범위로 유보 — 미착수). 이전: 2026-07-02 (Task 7 — 플로우 문서 정정: `docs/context/CONFIRM-FLOW.md`/`docs/context/PAYMENT-FLOW.md` §4.1.1·§4.1.2 전건 종결. outbox 발행 실패 복구 서술을 소스 기준(단일 `@Transactional` 안 선점+발행, 실패 시 TX 롤백 → PENDING 복귀, `OutboxWorker` 5초 주기 재픽업이 1차 경로 / IN_FLIGHT 5분 타임아웃은 보조 경로)으로 전면 재작성(§3 mermaid+prose, §10, §11, §13, PAYMENT-FLOW Phase 3 다이어그램·장애 복원 포인트) + `PaymentOutboxStatus.FAILED` dead-terminal 각주(§9/§10) + `parallel-enabled` 기본값 코드 fallback/default 프로파일 층위 병기 + dedup TTL 정리 완료 서술로 교체 + 두 문서 헤더 날짜 동기화). 이전: 2026-07-02 (Task 6 — 위키 잔여 13페이지 진단: `structured-logging.md` 가 이 배치 최대 오류로 확정 — 페이지 절반 이상(로깅 파이프라인 다이어그램·민감정보 마스킹 섹션·TraceId 전파 섹션·Logstash 연동 섹션)이 완전히 삭제된 인프라(`MaskingPatternLayout`/`TraceIdFilter`/Logstash 전송/Elasticsearch·Kibana 백엔드, 전건 grep 0)를 현재형으로 서술(S1 critical 4건) — 같은 위키의 `trace-propagation.md` 가 같은 주제를 Promtail/Loki 기준으로 정확히 서술해 대조군이 됨. 민감정보 마스킹 메커니즘이 대체 없이 사라진 것으로 보여 Task 8 TODOS "코드 확인 필요 항목" 후보로 별도 표기. RD/RETRYING/FCG 클러스터가 `architecture.md`(핵심 설계 결정 표의 FCG 행 + domain 패키지 트리의 `RecoveryDecision.java`)·`metrics.md`(관측 맹점 다이어그램의 RETRYING 분기)·`scenario-test.md`(`OutboxProcessingServiceTest` 섹션 전체가 삭제된 클래스)에 추가 확산됨을 확인. **신규 발견**: `msa-transition.md` 토폴로지 다이어그램이 `redis-stock` 연결을 `Prod --> RedS` 로 서술해 같은 위키의 `architecture.md`(`Pay --> RedS`, 정확)와 정면 모순(소스 재확인 결과 payment-service 만 연결) + `metrics.md` 가 RETRY-METRIC-CLEANUP 로 삭제된 `payment_health_max_retry_reached_total` 를 여전히 표에 나열하고 F14 `dependency_up` 게이지·알람 규칙 4그룹을 배너에서 누락 + `TossApiMetrics` 가 pg-service 로 이관됐음에도 payment-service 소속처럼 서술. `ai-workflow.md` 는 2026-06-12 개편을 정확히 반영(파일 자신이 그 개편의 산출물)해 13페이지 중 유일하게 갱신 격차 예외로 확인. `cross-validation.md`/`event-driven-choreography.md`/`trace-propagation.md` 는 배너-본문 정합 모범 사례로 보존 판정. `Home`/`_Sidebar`/`_Footer` 링크-슬러그 전건 대조 완료 — 깨진 링크 0건, 고아 페이지 0건. `Benchmark-Report.md` 는 이미 정확한 자기 배너로 변경 불요. 알람 규칙+장애 드릴 대응 신규 위키 페이지는 비강제, `metrics.md` 확장 흡수 권고). 이전: 2026-07-02 (Task 5 — 위키 도메인 코어 12페이지 진단: `state-management.md` 배너 "RecoveryDecision + FCG + 격리 사이클은 유지된다"가 12페이지 중 최대 단일 오류임을 확정 — `RecoveryDecision` grep 0(완전 삭제)·`RETRYING` enum 부재(F6)·`PaymentEventStatus.canApplyConfirmResult()` 가 READY/IN_PROGRESS 만 EOS 진입 허용함을 재확인, 상태 머신·RecoveryDecision 섹션·재고 복구 가드·복구 사이클 플로우 4곳 전면 재작성 대상(S1 critical) + `compensation-tx.md` 배너 자체가 "재고 복구는 product-service 호출(Kafka 이벤트)로 진화"라는 신규 오류(S1 critical, 실제는 payment-service 내부 Redis Lua `compensateAtomic` 로 완결·product 호출·Kafka 이벤트 없음, `StockCacheRedisAdapter`/CONFIRM-FLOW.md:250 대조로 확정) 발견 + `pg-confirm-flow.md` "결과 메시지 종류" 표와 "향후 확장" 절이 FCG(`PgFinalConfirmationGate`, pg-service 소속, `@Service`+테스트 완비하나 프로덕션 호출처 0)를 각각 살아있는 트리거·미래 계획으로 모순 서술함을 발견(S1+S2) + `async-outbox.md`/`message-delivery-and-dedupe.md`/`stock-cache-recovery.md` 에서 §0.3 층위 위반 신규 사례(batch-size 50 vs benchmark 100) 및 DLQ-REACHABILITY(AfterRollbackProcessor) 반영 누락 발견 + `pg-strategy.md` 배너가 클래스 전면 재작성 규모를 "경계 이동"으로 과소 서술함을 확인 + `outbox-pattern.md` 는 topic 문체 기준 예문 대상 위치 정확히 특정, FAILED dead-terminal 각주 필요 확인 + `tx-scope.md`/`retry-recovery.md` 는 배너·역사 프레이밍 모범 사례로 보존 판정 + 서사 후보 9건 archive 경로 채록(3건은 근거 부족으로 강제하지 않음)). 이전: 2026-07-02 (Task 4 — README + PAYMENT-FLOW-GUIDE 진단: outbox 발행 실패 stale 클러스터가 GUIDE 에도 5곳 확장 잔존 확인 + README "주요 해결 과제" 표 "장애 내성 복구 체계" 행 전체가 폐기된 3개념(`RecoveryDecision` 완전 삭제·`canCompensateStock` 가드 완전 삭제·FCG 프로덕션 호출처 0)을 현재형으로 서술 중임을 신규 발견(S1 critical) + README "결제 상태 관리" 섹션 "보상 안전 가드 자체는 유지" 서술이 코드와 정반대임을 신규 발견 + Outbox 모델 표 FAILED dead-terminal 미표기 확장 + Phase 축 3종(README 개발순서/결제단계/MSA로드맵) 전수 채록 + 위키 링크 25건 슬러그 전건 유효 확인 + README 도메인 사실(S1) 항목 별도 표기(ship 대조 입력용)). 이전: 2026-07-02 (Task 3 — 잔여 에이전트 문서 12파일 + smoke 5파일 진단: 대상 17파일에는 S1 클러스터 3종(outbox REQUIRES_NEW/IN_FLIGHT·FAILED dead-terminal·parallel-enabled 층위) 잔존 0건 확인 + 17파일 자체 교차 대조로 신규 S1 4건 발견(STRUCTURE.md 빌드/JaCoCo 서술 2건이 STACK.md/TESTING.md 와 정면 모순, STACK.md 스케줄러 매트릭스 user-service 누락, conventions/transactions.md 예시 qualifier 누락) + S4 중복 4건 SSOT 지정). 이전: 2026-07-02 (Task 2 — 플로우·대장·함정 5파일 진단: CONFIRM-FLOW/PAYMENT-FLOW 의 outbox REQUIRES_NEW/IN_FLIGHT stale 클러스터 확장 확정 + PaymentOutboxStatus.FAILED dead-terminal 신규 발견 + TODOS/CONCERNS 3분류 예비 판정 + PITFALLS ID 참조 오류 2건 발견)
> 이 문서는 `docs/DOCS-CONSISTENCY-OVERHAUL-PLAN.md` Task 2~19 가 채워 넣는 **근거 대장**이다. 모든 수정(Task 7~17)은 이 문서의 항목을 근거로만 수행한다.
> ship 시 `docs/archive/docs-consistency-overhaul/`로 이동한다.

## 0. 형식 정의

### 0.1 항목 형식 (Task 2~6 이 채우는 표의 컬럼)

| 컬럼 | 의미 |
|---|---|
| **문서 위치** | 파일 경로 + 섹션/줄 (예: `docs/context/CONFIRM-FLOW.md §12`, `outbox-pattern.md L186-194`) |
| **문제** | 무엇이 어떻게 틀렸거나 낡았는지 한 문장 |
| **소스 근거** | **소스 코드 파일:라인만.** 다른 문서(`docs/context/` 상호 인용, archive briefing, 위키 상호 인용)는 근거로 불인정 — 코드가 없으면 "근거 없음"으로 표기하고 심각도를 낮춘다 |
| **수정 방향** | 삭제 / 문장 교체 / 신규 서술 / 보존(변경 없음이 결정인 경우도 명시) |
| **심각도** | S1~S5 (아래 0.2) |

### 0.2 심각도 분류

| 등급 | 정의 |
|---|---|
| **S1** | 코드-문서 불일치 — 문서가 현재 코드와 반대이거나 존재하지 않는 걸 있다고 서술 |
| **S2** | 문서 간 · 문서 내 모순 — 같은 사실을 다르게 서술 |
| **S3** | 완료 잔존 · 노후 — 이미 끝난 일을 "예정"으로, 또는 스냅샷 시점이 오래됨 |
| **S4** | 중복 · 비대 — 같은 내용을 여러 문서가 반복 서술 (SSOT 미지정) |
| **S5** | 문체 (AI체) — 평가 형용사·번역투·과도한 단정문 |

### 0.3 기본값 인용 규칙 (게이트 2R minor 반영)

문서가 설정값의 "기본값"을 인용할 때는 **반드시 층위를 명시**한다 — 같은 키가 계층마다 다른 기본값을 가질 수 있다.

- **코드 fallback**: `@Value("${key:default}")` 또는 `@DefaultValue("...")` 애노테이션의 값 — 어떤 profile yml 도 없을 때 최후 적용
- **default profile yml**: `application.yml` 의 명시값 — 로컬/테스트 기본 구동 시 적용, 코드 fallback 을 덮어씀
- **profile별 yml**: `application-docker.yml` / `application-benchmark.yml` 등 — 운영/벤치마크 시 추가로 덮어씀

**실증 사례**: `scheduler.outbox-worker.parallel-enabled` — 코드 fallback `false`(`OutboxWorker.java:26` `@Value("${scheduler.outbox-worker.parallel-enabled:false}")`) vs default profile yml `true`(`application.yml:149`) vs benchmark profile `${SCHEDULER_PARALLEL_ENABLED:true}`(`application-benchmark.yml:25`). "기본값은 false다"라고만 쓰면 default profile 로 도는 로컬/docker 구동 실측과 어긋난다 — 인용 시 "코드 fallback: false / default 프로파일: true" 두 값을 함께 적어야 한다.

---

## 1. 사실 목록 (Fact Ledger)

EOS 전환(2026-05-17 봉인, PAYMENT-EOS-TRANSITION) 이후 ~ TC-3 재고 수동 resync(2026-07-01) 사이 archive 봉인 토픽 + 봉인 이후 standalone 커밋에서 "코드에 실제로 일어난 변경"을 추출해 **소스에서 재확인**한 목록이다. archive briefing 은 후보 목록 출처일 뿐이며, 아래 각 행은 briefing 인용이 아니라 소스 파일:라인 확인 결과다. Task 2~6 이 문서 대조 시 1차 입력으로 쓴다.

| # | 사실 | 소스 근거 (파일:라인) | 최초 도입/변경 토픽 |
|---|---|---|---|
| F1 | `PaymentConfirmResultUseCase.handle` 은 `@Transactional(transactionManager = "transactionManager", timeout = 5)` 로 JPA TM 을 **명시** qualifier 고정 (qualifier 미명시 아님) | `PaymentConfirmResultUseCase.java:116` | EOS-FOLLOWUP-CLEANUP 2026-05-29 |
| F2 | `OutboxRelayService.relay` 는 단일 `@Transactional` 안에서 선점(`claimToInFlight`)·발행·`toDone()` 을 모두 수행 — 발행 실패 시 TX 롤백으로 **PENDING 복귀**(IN_FLIGHT 잔류 아님), REQUIRES_NEW 선점 분리 없음 | `OutboxRelayService.java:49-59` (Javadoc 46-47 "실패 시 rollback으로 PENDING 유지가 올바른 동작") | PAYMENT-EOS-TRANSITION 이전부터 현재까지 이 구조 |
| F3 | `PaymentOutboxUseCase.claimToInFlight`(REQUIRES_NEW 선점)·`incrementRetryOrFail` 은 프로덕션 호출처 0 — `OutboxWorker` 는 `recoverTimedOutInFlightRecords`/`findPendingBatch` 만 호출 | `PaymentOutboxUseCase.java:36-55` 정의, 호출부 `OutboxWorker.java:38,41` (해당 두 메서드 호출 없음, grep 확인) | 구조상 상시 — 코드 확인 필요 항목 (topic 결정) |
| F4 | `OutboxWorker` 폴링 주기 5초, 발행 실패는 retryCount 증가 없이(F3) 무백오프 재시도 | `application.yml:147` (`fixed-delay-ms: 5000`) | 상시 |
| F5 | `DedupeCleanupWorker`(`@Scheduled`) 가 `payment_event_dedupe` 만료행을 `deleteExpired` 로 청소 — "후속 항목" 아니라 구현 완료 | `payment-service/.../infrastructure/scheduler/DedupeCleanupWorker.java` (파일 존재) | EOS-FOLLOWUP-CLEANUP 2026-05-29 |
| F6 | `PaymentEventStatus` enum 은 8개 값(READY/IN_PROGRESS/DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED) — **RETRYING 없음** | `PaymentEventStatus.java:3-12` | CLEANUP-BATCH-E 2026-06-21 |
| F7 | `PaymentEventStatus.canCompensateStock()` 메서드 자체가 코드베이스에서 완전 삭제(grep 0) — `handleFailed`/`handleQuarantined` 는 가드 없이 `stockCachePort.compensateAtomic` 직접 호출 | `PaymentConfirmResultUseCase.java:280-303`, 전체 grep 0 | STOCK-COMPENSATION-OTHER-PATHS 2026-06-21 |
| F8 | `StockCachePort` 포트는 `decrementAtomic`/`compensateAtomic`/`set` 3메서드만 존재 — 단건 API(`decrement`/`rollback`/`findCurrent`/`current`) 5종 삭제 | `StockCachePort.java:23,35,48` | CLEANUP-BATCH-E 2026-06-21 |
| F9 | `payment_event.retry_count` 컬럼 DROP + 도메인 필드 제거(`payment_outbox.retry_count` 는 별개 컬럼으로 존치) | `V5__drop_payment_event_retry_count.sql:13` | RETRY-METRIC-CLEANUP 2026-06-22 |
| F10 | DONE + APPROVED 재배달 시 종결 가드가 noop 하지 않고 `sendStockCommittedEvents` 재발행(`terminalResendMetrics` 계측) | `PaymentConfirmResultUseCase.java:124-138` | CONFIRM-APPROVED-RESEND-GAP 2026-06-22 |
| F11 | `pg_inbox.attempt` 컬럼(Flyway V5) 이 self-loop 시도횟수 SoT | `pg-service/.../V5__add_pg_inbox_attempt.sql` | DLQ-REACHABILITY 2026-06-25 |
| F12 | `KafkaConsumerConfig` 가 `factory.setAfterRollbackProcessor(...)` 명시 연결 — EOS `commitTransaction` 반복 실패 시 `events.confirmed.dlq` 로 도달(과거엔 컨테이너 디폴트 9회 후 단순 스킵) | `payment-service/.../KafkaConsumerConfig.java:92` | DLQ-REACHABILITY 2026-06-25 |
| F13 | `prometheus.yml` 가 `rule_files` 로 4그룹(coordinator/guard-skip/dlq/availability) 규칙 로드 — Alertmanager 통지는 미도입, 평가/조회까지만 | `observability/prometheus/prometheus.yml:10`, `observability/prometheus/rules/{coordinator,guard-skip,dlq,availability}.yml` | ALERTING-RULES-AND-FAULT-DRILL 2026-06-27 + FAULT-INJECTION-RESILIENCE 2026-06-30(availability 그룹) |
| F14 | 4서비스 `DependencyHealthMetrics` 가 `dependency_up{component}` 폴링 게이지 노출 | `{payment,pg,product,user}-service/.../infrastructure/metrics/DependencyHealthMetrics.java` (4파일 존재) | FAULT-INJECTION-RESILIENCE 2026-06-30 |
| F15 | 만료 배치가 건별 독립 트랜잭션으로 분리돼 stranded 1건이 다른 정상 READY 만료를 막지 않음(poison-pill 격리) — `PaymentExpirationServiceImpl` 은 `@Transactional` 없이 `PaymentCommandUseCase.expirePayment`(별도 빈, 자체 TX)를 건별 try/catch 로 호출 | `PaymentExpirationServiceImpl.java:41-50` | L-14 부분 해소, 2026-07-01 (커밋 c0d1b90c) |
| F16 | stranded 결제(DB 다운 등으로 READY 잔류) 는 여전히 자동 복구되지 않음(비종결 READY 영구 잔류) — poison-pill 해소와 별개 한계 | `docs/context/CONCERNS.md:159-161`(코드 근거는 F15 와 동일 파일 — `expire()` 는 order NOT_STARTED 만 대상이라 order EXECUTING 잔류분은 여전히 미해결, `PaymentOrder` 상태 전이 로직 grep 필요 시 Task 7/9 에서 재확인) | FAULT-INJECTION-RESILIENCE 2026-06-30 |
| F17 | payment `POST /admin/stock/resync/{productId}` 가 product RDB 재고를 조회해 redis-stock 을 단건 SET 으로 덮어씀(수동, 단건 한정) — 전체 일괄/자동 발산 감지는 미구현 | `StockAdminController.java`, `StockResyncUseCase.java` (파일 존재) | TC-3 부분 완료, 2026-07-01 (커밋 fa160b34/b39b510e) |
| F18 | `PITFALLS.md` 는 헤더 최종 갱신 2026-05-17 로 표기하지만 본문 `## 24` 항목은 2026-06-27 산출물(broker 완전 정지 absent 분기) | `docs/context/PITFALLS.md:3`(헤더) vs `:248-254`(§24 본문, "absent(kafka_brokers)" 최근 도입 서술) — 헤더-본문 시점 자체가 문서 내부 근거이며 §24 도입 사실은 alerting rule 소스(F13)로 뒷받침 | 헤더 갱신 누락 |
| F19 | 로그·트레이스 관측성은 Promtail → Loki 경유(Elasticsearch/Logstash 아님) — `LogFmt` 로그가 Loki 에 적재, orderId 검색 → derivedField 로 Tempo 점프 | `docs/context/STACK.md:67,70,80` (Loki/Promtail 정의, 로그 기반 추적 진입 서술) | OBSERVABILITY-COMPLETION 2026-06-10~11 |
| F20 | `business-dashboard.json` / `system-dashboard.json` 2분할 — 옛 `payment-dashboard.json` 폐기 | `observability/grafana/dashboards/{business-dashboard,system-dashboard}.json` (파일 존재, `payment-dashboard.json` 부재) | OBSERVABILITY-COMPLETION 2026-06-10~11 |
| F21 | `LocalDateTimeProvider`/`SystemLocalDateTimeProvider` 포트는 코드베이스에서 완전 제거(grep 0, 테스트 파일 주석의 역사적 언급만 잔존) — 4서비스 `Clock` 빈 + `Instant` 로 시간 표준 통일 | 전체 grep 0 (`payment-service/src/test/.../JdbcPaymentEventDedupeStoreRoundTripTest.java:46,135` 만 주석으로 과거 비교 언급) | TIME-MODEL-AND-EXPIRY 2026-06-03 |
| F22 | `payment.expiration.ready-timeout-minutes` 기본값 30(분) — default profile yml 명시값 | `application.yml:131` | TIME-MODEL-AND-EXPIRY 2026-06-03 |
| F23 | payment `Dockerfile` 에 `ENV TZ=UTC` — TZ backstop 3겹(Dockerfile/JVM/compose) 중 하나 | `payment-service/Dockerfile:2` | TIME-MODEL-FOLLOWUP 2026-06-07 |
| F24 | CI 는 서비스별 재사용 워크플로우(`_service-ci.yml`, `workflow_call`) 로 6서비스 fan-out — 단일 2-job 구조 아님 | `.github/workflows/_service-ci.yml` (파일 존재) | CI-PIPELINE-REDESIGN 2026-06-08 |
| F25 | JaCoCo 라인 커버리지 게이트 서비스별 상이(payment 0.86 / pg 0.93 / product 0.97 / user 0.97 / gateway·eureka 0.0), 단위 `test` exec 기준(통합 미합산) | `docs/context/TESTING.md:137` 서술 자체는 최신(직접 code 확인은 Task 3/9 범위 — 루트 `build.gradle` `jacoco.lineCoverageMinimum` 확인 필요) | CLEANUP-BATCH-B 2026-05-31 → CI-PIPELINE-REDESIGN 2026-06-08 재정의 |
| F26 | `docs/context/TESTING.md` "현재 테스트 카운트" 표는 2026-06-14 스냅샷(873단위/48통합) — 이후 CI-PIPELINE-REDESIGN·OBSERVABILITY-COMPLETION·CLEANUP-BATCH-C/D·K6-ASYNC·CAPACITY·STOCK-COMPENSATION·CLEANUP-BATCH-E·RETRY-METRIC-CLEANUP·CONFIRM-APPROVED-RESEND-GAP·DLQ-REACHABILITY·ALERTING-RULES·FAULT-INJECTION·L-14·TC-3 등 다수 토픽에서 테스트 추가/삭제 발생 — 스냅샷이 현재 값을 대표하지 못함(근사치: payment 단위 342 `@Test` 애노테이션 grep, pg 240, product 50, user 9 — 정확한 실행 카운트는 Task 9 수정 시 `./gradlew test` 재실행으로 확정) | `docs/context/TESTING.md:166-178`(스냅샷 표) vs `grep -rc "@Test" {payment,pg,product,user}-service/src/test` = 342/240/50/9(애노테이션 수, parameterized 확장 전) | 지속 누적 |
| F27 | README 배너의 "589 PASS" 는 스냅샷보다도 더 이전 값 — F26 과 같은 근본 원인(수동 갱신 문서) | `README.md:8` | 지속 누적 |
| F28 | 위키 저장소 마지막 실질 커밋은 2026-06-12("수정") — 이후 6/13(cleanup-batch-c)부터 7/1(TC-3)까지 13개 이상 토픽 미반영 | `payment-platform.wiki/` git log: `554e120 2026-06-12`, `51db9c4 2026-06-01`, 그 이전 `ff2735c 2026-05-17`(PAYMENT-EOS-TRANSITION 봉인) | 위키 갱신 프로세스 부재 |

---

## 2. 표본 12건 재검증 판정

topic 문서 "사전 진단 표본" 12건을 위 형식으로 재검증한 결과. **#1·#12는 소스 검증 완료 상태로 인계**받아 근거를 보강, 나머지 10건은 이번 태스크에서 재검증했다.

### #1 — `CONCERNS.md` L-1 qualifier 서술 모순

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/CONCERNS.md` L-1 절, 특히 L92 |
| 문제 | L92 "`@Transactional(timeout=5)` 는 qualifier 미명시로 `@Primary JpaTransactionManager` 를 선택한다" — 현재 코드와 반대. 같은 절 L97 은 "qualifier 명시는 EOS-FOLLOWUP-CLEANUP 에서 완료"라고 써서 **같은 항목 안에서 자기모순** |
| 소스 근거 | `PaymentConfirmResultUseCase.java:116` — `@Transactional(transactionManager = "transactionManager", timeout = 5)` qualifier 명시 확정. `docs/context/CONFIRM-FLOW.md:162` 는 이미 정정된 서술("qualifier 로 명시 고정") — CONCERNS.md 만 뒤처짐 |
| 수정 방향 | CONCERNS.md L-1 L92 문장을 "qualifier 명시 완료(EOS-FOLLOWUP-CLEANUP)" 기준으로 정정. L97 과 중복되는 서술은 하나로 정리 |
| 심각도 | **S1 + S2** (코드 불일치 + 문서 내 모순) |

### #2 — `CONFIRM-FLOW.md` §12 TTL 정리 스케줄러 "후속 항목" 서술

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/CONFIRM-FLOW.md:437` |
| 문제 | "TTL 정리 스케줄러는 TC-13-FOLLOW-2 후속 항목" — 이미 구현 완료된 사실을 미착수로 서술 |
| 소스 근거 | `DedupeCleanupWorker.java` 파일 존재 (F5) |
| 수정 방향 | "후속 항목" → 완료 서술로 교체(스케줄 주기·배치 크기 등 실제 동작 반영) |
| 심각도 | **S1** (완료 반영 누락) |

### #3 — `PITFALLS.md` 헤더-본문 시점 불일치

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/PITFALLS.md:3` |
| 문제 | 헤더 "최종 갱신: 2026-05-17" 이지만 본문 `## 24` 는 그보다 훨씬 뒤 산출물 |
| 소스 근거 | F13(alerting rule 4그룹, 2026-06-27/06-30 도입)이 §24 서술(`absent(kafka_brokers)` 분기)의 소스 근거 |
| 수정 방향 | 헤더 최종 갱신 날짜를 §24 도입 시점으로 갱신 |
| 심각도 | **S3** (헤더-본문 불일치, 경미) |

### #4 — `TODOS.md` 완료 항목 잔존 비대

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/TODOS.md` 전체 (377줄) |
| 문제 | ✅ 완료 마킹 항목이 다수(수십 건) — "토픽 종결 시 항목 삭제" 자체 규칙(`TODOS.md:371`)과 모순, discuss 진입마다 탐색 비용 |
| 소스 근거 | 문서 구조 문제라 소스 코드 근거 대상 아님 — 삭제 판정 근거는 각 항목이 인용한 archive 경로(`docs/archive/<topic>/COMPLETION-BRIEFING.md`)의 실재 여부로 대체 확인(F1~F26 각 사실이 이미 소스로 검증된 완료 항목들과 대응) |
| 수정 방향 | Task 8 에서 3분류 적용 — (a) 완전 삭제 (b) 혼합 항목 문장 단위 제거 (c) 보존. 예: L-14 텍스트(TODOS.md 에는 없고 CONCERNS.md 에 있음, 혼합 항목 사례) |
| 심각도 | **S3** (완료 잔존 비대) |

### #5 — `README.md` 배너 노후

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `README.md:7-12` |
| 문제 | "진행 중 Phase 6 · 589 PASS · 정합이 안 맞을 수 있음" 배너가 현재 상태와 괴리 — 테스트 수는 F26/F27 근거로 훨씬 큼, "재고 복원 가드"(`README.md:24`)는 CLEANUP-BATCH-E 에서 제거된 단건 API 기반 개념(F8) |
| 소스 근거 | F8(StockCachePort 단건 API 삭제), F26/F27(테스트 카운트 스냅샷 노후) |
| 수정 방향 | Task 11 에서 배너 재작성 — 정확한 테스트 카운트는 그 시점 `./gradlew test` 재실행 값 사용, "재고 복원 가드" 문구 삭제/교체 |
| 심각도 | **S1(재고 복원 가드 서술)** + **S3(배너 지표 노후)** — README 도메인 사실 항목이라 ship domain-expert 대조 입력 대상 |

### #6 — README ↔ 내부 문서 페이즈 번호 이원화

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `README.md:34-43`(개발 과정 Phase 1~6+ETC) vs `docs/context/PAYMENT-FLOW.md:23-138`(흐름 단계 Phase 1~5) vs `docs/context/PAYMENT-FLOW-GUIDE.md:70-141`(흐름 단계 Phase 1~6) |
| 문제 | 같은 "Phase" 단어가 두 축을 가리킨다 — README 는 "개발 진행 순서"(위키 페이지별 이정표), PAYMENT-FLOW*.md 는 "결제 요청 하나가 통과하는 처리 단계"(order 생성→confirm→outbox→pg→결과 수신→폴링). PAYMENT-FLOW.md:6 은 심지어 세 번째 축("MSA 전환 Phase 0~3.5")까지 남아 있어 최소 3축이 "Phase" 로 혼용 |
| 소스 근거 | 문서 자체가 근거(용어 사용 실태 채록) — 코드에는 "Phase" 개념이 없음(순수 문서 조직 개념) |
| 수정 방향 | Task 4 에서 전수 채록 후 Task 11 에서 확정안 결정 — 최소한 서로 다른 축임을 각 문서 도입부에 1줄 명시(topic 결정: "내부 Phase 번호가 README 개발 과정 Phase 와 별개임을 TODOS 분류 룰에 1줄 명시") |
| 심각도 | **S2** (용어 충돌로 인한 혼동, 사실관계 오류는 아님) |

### #7 — `TESTING.md` 테스트 카운트 스냅샷 노후

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/TESTING.md:166-178` |
| 문제 | "2026-06-14 기준" 명시 스냅샷(873/48)이나 갱신 시점 규칙이 없어 스냅샷이 영구히 낡아감 |
| 소스 근거 | F26 — `@Test` 애노테이션 grep 342(payment)/240(pg)/50(product)/9(user), 2026-06-14 이후 최소 13개 토픽에서 테스트 추가/삭제 확인 |
| 수정 방향 | Task 9 수정 시점에 `./gradlew test`(+`integrationTest`) 재실행으로 정확한 카운트 갱신, 표 상단에 "스냅샷일 뿐 회귀 가드는 pass/fail" 문구는 유지(이미 있음, TESTING.md:178) |
| 심각도 | **S3** (노후, 경미 — 문서 자체가 스냅샷임을 인지하고 있음) |

### #8 — 위키 전체 갱신 격차 미검증

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `payment-platform.wiki/*.md` 전체 25페이지 |
| 문제 | 마지막 실질 갱신이 2026-06-12, 이후 최소 13개 토픽(F5~F17 포함) 미반영 |
| 소스 근거 | F28 — wiki git log(`554e120 2026-06-12`), F5~F17 각 사실의 소스 근거 |
| 수정 방향 | Task 5/6 에서 25페이지 전수 진단 → Task 13~17 에서 반영 |
| 심각도 | **S1 다수 예상** (Task 5/6 에서 페이지별 확정) |

### #9 — 위키 `structured-logging.md` Elasticsearch/Logstash 서술

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `structured-logging.md:16,32,48-49,282-313` |
| 문제 | Logstash 경유 Elasticsearch 인덱싱을 현재 스택처럼 서술 — 실제는 Promtail/Loki |
| 소스 근거 | `docs/context/STACK.md:67,70,80`(F19) — Loki 3100 포트, Promtail 로그 수집, 로그 기반 추적 진입 서술. 코드 레벨로는 `docker-compose.infra*.yml` 의 loki/promtail 서비스 정의가 1차 소스지만 이번 태스크에서는 STACK.md 의 코드 대조 결과(이미 코드 확인된 상태)를 인용 — Task 6 재검증 시 compose 파일 직접 확인 권고 |
| 수정 방향 | Task 6 진단 확정 → Task 16 에서 본문을 Promtail/Loki 파이프라인으로 재작성 |
| 심각도 | **S1** |

### #10 — 위키 `state-management.md` 폐기된 RETRYING 상태 서술

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `state-management.md:24,43,67,70,72-75,86,96-99,173,188,296,362` |
| 문제 | RETRYING 상태를 현재 상태 머신의 일부처럼 전면 서술(다이어그램·전이표 포함) — 실제로는 완전 제거됨 |
| 소스 근거 | `PaymentEventStatus.java:3-12`(F6) — enum 8개 값, RETRYING 없음 |
| 수정 방향 | Task 5 에서 본문 재작성 범위 확정 → Task 15 에서 상태 다이어그램·전이표 전면 갱신 |
| 심각도 | **S1** (다수 서술 지점) |

### #11 — 위키 `outbox-pattern.md` 빈 "표기 규칙" 섹션

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `outbox-pattern.md:11-13` |
| 문제 | `## 표기 규칙` 헤더 바로 다음 줄이 `## 왜 outbox 인가` — 헤더 아래 내용 없음 |
| 소스 근거 | 구조 결함이라 코드 근거 대상 아님 — 위키 파일 자체가 근거 |
| 수정 방향 | Task 13 에서 빈 헤더 제거 (본문 현행화 작업과 동시 처리) |
| 심각도 | **S4** (구조 결함, 경미) |

### #12 — CONFIRM-FLOW/PAYMENT-FLOW의 outbox 발행 실패 복구 서술 (S1 최우선)

| 컬럼 | 내용 |
|---|---|
| 문서 위치 | `docs/context/CONFIRM-FLOW.md:74,80,90,116,401,415,450` + `docs/context/PAYMENT-FLOW.md:62,68` |
| 문제 | "REQUIRES_NEW 로 선점 → 발행 실패해도 IN_FLIGHT 유지 → 일정 시간 후 타임아웃 회수(백오프 적용)"로 서술 — 실제로는 선점·발행·완료가 **단일 TX** 라 발행 실패 시 TX 롤백으로 **PENDING 복귀**, `OutboxWorker` 5초 주기 재픽업이 전부. `PaymentOutboxUseCase.claimToInFlight`(REQUIRES_NEW)·`incrementRetryOrFail` 은 프로덕션 호출처 0(dead) |
| 소스 근거 | F2(`OutboxRelayService.java:49-59` 단일 TX) + F3(REQUIRES_NEW/increment 메서드 호출처 0) + F4(`application.yml:147` fixed-delay-ms:5000) |
| 수정 방향 | CONFIRM-FLOW.md §3·§4·§9·§11, PAYMENT-FLOW.md Phase 3·장애 복원 포인트를 F2~F4 기준으로 전면 재작성. IN_FLIGHT 타임아웃 회수 서술은 dead-code 각주로 강등하거나 삭제(코드 확인 필요 항목으로 TODOS 등재는 Task 8) |
| 심각도 | **S1 (critical)** — 1라운드 게이트에서 이 stale 서술이 위키의 참인 문장을 뒤집을 뻔한 실증 사례. Task 7 최우선 처리 |

---

## 3. 게이트 2R 잔여 minor 해소

### 3.1 "기본값" 층위 명시 규칙

→ 0.3 절에 항목 형식 규칙으로 편입 완료. `parallel-enabled` 실증 사례 포함.

### 3.2 기준 예문 마지막 불릿(retry 카운트) 재검증

topic 문서 "기준 예문" 마지막 불릿:

> 결제 명령은 무조건 발행돼야 하므로 자동 FAILED 종결은 사실상 도달하지 않는다 — retry 카운트는 백오프 강도 조절과 운영 알람용으로 고려 중

**재검증 결과**: F3 확인 결과 `incrementRetryOrFail`(재시도 횟수 증가 + 한도 소진 시 FAILED 종결)은 프로덕션 호출처가 0이다. `payment_outbox.retry_count` 를 증가시키는 유일한 실사용 경로는 `recoverTimedOutInFlightRecords`(`OutboxWorker.java:38`) — 이는 IN_FLIGHT 타임아웃(선점 후 워커가 죽은 경우) 회수 전용이며, `OutboxRelayService.relay` 의 **발행 실패**(TX 롤백 → PENDING)는 retryCount 를 전혀 건드리지 않는다.

**결론**: "고려 중"(향후 계획 뉘앙스)이 아니라 **"현재 relay 실패 경로에서는 미적용"**이 정확한 현재형 서술이다. 게이트 1R 에서 이미 확정된 "후" 버전 문구(topic 문서 표 참고)는 "retry 카운트는 백오프 조절과 운영 알람용"이라고만 써서 이 미적용 사실을 담지 않는다 — Task 13(위키 outbox-pattern.md 반영) 시 다음과 같이 보강:

> retry 카운트는 원래 outbox 폴백 워커(`recoverTimedOutInFlightRecords`)의 IN_FLIGHT 타임아웃 회수 전용이고, `relay` 자체의 발행 실패는 카운트 증가 없이 5초 주기로 무한 재시도한다 — 결제 명령 발행은 포기 불가라 이 무백오프 반복이 의도된 동작이다.

이 결론은 **코드 확인 필요 항목**(topic doc "코드 확인 필요 항목" 절)과 동일 근거를 공유한다 — 회귀/의도 판정은 이 토픽 범위 밖(코드 미수정)이므로 TODOS 신규 등재는 Task 8 에서 수행.

---

## 4. Task 2~6 진단 확정 대상 (플레이스홀더)

아래 절은 Task 2~6 이 각자 담당 범위를 진단하며 표를 채운다. 형식은 0.1 절을 따른다.

### 4.1 Task 2 — 플로우·대장·함정 5파일 (`CONFIRM-FLOW.md` / `PAYMENT-FLOW.md` / `TODOS.md` / `CONCERNS.md` / `PITFALLS.md`)

전건 통독 + 사실 목록(§1) 대조 결과. #2/#3/#4/#12 는 §2 표본 판정을 인계받아 정확한 현재 줄번호로 확장했다. 소스 근거는 `grep`/`Read` 로 직접 재확인(파일:라인).

#### 4.1.1 `docs/context/CONFIRM-FLOW.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L74(mermaid), L80(mermaid), L90(prose) | §3 "claimToInFlight 가 REQUIRES_NEW 로 원자 선점 → 발행 실패 시 TX rollback 이지만 outbox row 는 IN_FLIGHT 상태로 남는다" — 실제로는 `OutboxRelayService.relay` 가 claim(Step1)·발행(Step3)·toDone(Step4) 를 **단일 `@Transactional`** 안에서 수행. `PaymentOutboxRepository.claimToInFlight` 는 `@Modifying` UPDATE 로 같은 TX 소속, REQUIRES_NEW 아님. 발행 실패 시 전체 TX 롤백 → row 는 (커밋된 적 없는) PENDING 그대로 복귀 | `OutboxRelayService.java:49-78`(단일 `@Transactional`, Step1~4 순차), `PaymentOutboxRepositoryImpl.java:56-61`(`claimToInFlight` propagation 지정 없음 = REQUIRED) | 문장/다이어그램 전면 재작성: "claim+발행+완료가 한 TX, 실패 시 TX 롤백 → PENDING 즉시 복귀 → OutboxWorker 5초 주기 재픽업"으로 | **S1 critical** (표본 #12 확장) |
| L401 (§10 재시도 정책표 "코드 진입점" 행) | `PaymentOutboxUseCase.incrementRetryOrFail` 을 payment 측 retry 진입점으로 표기 — 이 메서드는 프로덕션 호출처 0(dead) | `PaymentOutboxUseCase.java:46-55` 정의, 호출처 전체 grep 0 (F3) | 진입점을 실제 재시도 경로(`OutboxWorker` 5초 주기 재픽업, `PaymentOutboxUseCase.recoverTimedOutInFlightRecords`)로 정정 | **S1** (표본 #12/§3.2 확장) |
| L415 (§11 회복 시나리오 인덱스) | "Kafka producer 실패 (payment → broker) \| IN_FLIGHT 유지 → `OutboxWorker` 타임아웃 후 PENDING 복귀 → relay 재시도" — 위와 동일 오류 재등장 | 상동 (`OutboxRelayService.java:49-78`) | "TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업" 으로 정정 | **S1 critical** (표본 #12 확장) |
| L450 (§13 멱등성 layer 표 "outbox claim" 행) | "`claimToInFlight` REQUIRES_NEW atomic UPDATE" — REQUIRES_NEW 아님 (위와 동일 오류) | 상동 | "REQUIRES_NEW" 삭제, 단일 TX 내 atomic UPDATE 로 정정 | **S1 critical** (표본 #12 확장) |
| L~372-380 (§9 PaymentOutboxStatus 상태표) + L399 (§10 "한도 초과 시 \| outbox FAILED") | `PaymentOutboxStatus.FAILED` 로 전이하는 코드 경로가 현재 0건 — `PaymentOutbox.toFailed()` 도메인 메서드 자체가 CLEANUP-BATCH-E 에서 삭제됐고, `PaymentOutboxStatus.FAILED` 를 세팅하는 지점이 main 코드에 없음(선언·`isTerminal()` 판별 외 참조 0) | `PaymentOutboxUseCase.java` 전체에 `toFailed` 없음(grep 0), `grep -rn "PaymentOutboxStatus.FAILED\|\.toFailed(" payment-service/src/main` = 0건. `incrementRetryOrFail`(exhaustion 판정 유일 지점)도 호출처 0(F3) | FAILED 를 "현재 도달 불가(dead terminal state, TC-7 재검토 대상)"로 각주. state diagram 의 `FAILED --> [*]` 를 dead-branch 표기로 조정 | **S1** (F3 확장, 신규 발견) |
| L113 + 헤더 L3 | "`scheduler.outbox-worker.parallel-enabled`: **false (기본)**" — 코드 fallback(false) 만 인용하고 실제 적용되는 default profile yml 값(true)을 누락. 헤더는 "2026-06-23 parallel-enabled 기본값 false 정정"이라며 이 부정확한 값을 "정정 완료"로 표기 | `OutboxWorker.java:26`(`@Value("...:false}")`) vs `application.yml:149`(`parallel-enabled: true`), `application-benchmark.yml:25`(`${SCHEDULER_PARALLEL_ENABLED:true}`) | §0.3 층위 규칙대로 "코드 fallback: false / default 프로파일(로컬·docker 실구동 값): true" 두 값 병기 | **S1+S2** (§0.3 층위 규칙 위반 실사례, 신규 발견) |
| L437 (§12 dedup TTL 표) | "TTL 정리 스케줄러는 TC-13-FOLLOW-2 후속 항목" — 이미 구현 완료(표본 #2 그대로 잔존, 현재 정확한 줄번호로 재확인) | `DedupeCleanupWorker.java` 파일 존재 (F5) | "후속 항목" → 완료 서술(스케줄 주기·`deleteExpired` 배치 삭제)로 교체 | **S1** (표본 #2 정확 위치 확정) |
| 헤더 L3 | "최종 갱신: 2026-06-23" — 그러나 본문(§5 DLQ-REACHABILITY 절, §16 EOS 시나리오 #6·#7)은 2026-06-25(DLQ-REACHABILITY) 산출물을 이미 반영 — 헤더가 본문보다 뒤처짐 | F12(`KafkaConsumerConfig.java:92`, DLQ-REACHABILITY 2026-06-25) | 헤더 날짜를 본 태스크(Task 7) 정정 완료 시점으로 갱신 | **S3** (표본 #3 과 동일 패턴) |
| §18 관련 문서 목록 | "pg-service listener 분리 안 설계 기록: `docs/archive/pg-confirm-listener-split/` (**verify 완료 후 이동 예정**)" — 이미 이동 완료(COMPLETION-BRIEFING.md 존재) | `docs/archive/pg-confirm-listener-split/COMPLETION-BRIEFING.md` 파일 존재 확인 | "(이동 예정)" 괄호 삭제 | **S3** (완료 잔존, 경미) |

> **[Task 7 종결, 2026-07-02]** 위 8건 전건 `docs/context/CONFIRM-FLOW.md` 에 반영 완료 — §3 mermaid+prose 단일 TX 재작성, §9 FAILED dead-terminal 각주, §10 두 행(한도 초과 시/코드 진입점) 정정, §11 회복 시나리오 정정, §12 dedup TTL 완료 서술, §13 REQUIRES_NEW 삭제, §0.3 층위 병기(parallel-enabled), 헤더 날짜 갱신, §18 "(이동 예정)" 삭제.

> **[Task 10 추가 발견 및 정정, 2026-07-03]** stale 마커 게이트 재검증(`StockOutbox` grep)에서 §14 L464 신규 발견 — "`@Async("outboxRelayExecutor")` 를 `OutboxImmediateEventHandler` 와 `StockOutboxImmediateEventHandler` 가 사용"이 EOS 전환에서 완전 삭제된(`grep -rln StockOutboxImmediateEventHandler --include="*.java"` = 0건) 클래스를 현재도 쓰는 것처럼 서술 — Task 7 이 §3/§4/§10/§11/§13(outbox 재시도 흐름)만 재작성하고 §14(VT+MDC 전파)는 범위 밖이라 잔존. `OutboxImmediateEventHandler` 단독 서술 + "과거 StockOutboxImmediateEventHandler 도 같은 executor 를 썼으나 EOS 전환에서 폐기" 각주로 정정. 헤더 날짜 갱신.

#### 4.1.2 `docs/context/PAYMENT-FLOW.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L68 (Phase 3 다이어그램) | "R5a: IN_FLIGHT 유지 → OutboxWorker가 타임아웃 복구 재발행" — CONFIRM-FLOW L80/L90 과 동일한 stale 서술 (REQUIRES_NEW 분리 커밋 전제) | `OutboxRelayService.java:49-78` (F2) | "TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업" 으로 정정 | **S1 critical** (표본 #12, 정확 줄번호 확정) |
| L200 (장애 복원 포인트) | "리스너 스킵/크래시: payment 쪽은 `OutboxWorker` (fixedDelay 5초, batchSize 50, **IN_FLIGHT 5분 타임아웃 복귀**)" — 발행 실패 회복의 대표 서술로 IN_FLIGHT 타임아웃 경로를 앞세움. 실제 발행 실패의 1차 회복 경로는 TX 롤백 → PENDING 즉시 재픽업(5초 주기)이고, IN_FLIGHT 5분 타임아웃은 워커 크래시 등 별도(더 드문) 시나리오 | 상동 (F2/F3) | "PENDING 배치 재픽업(5초 주기)이 1차 경로, IN_FLIGHT 5분 타임아웃 복귀는 보조 경로"로 우선순위 재정렬 | **S1** (동일 클러스터 확장) |
| L6 | "현재 `main` (MSA 4서비스 분리 + Phase 0~3.5 + PRE-PHASE-4-HARDENING 봉인 시점) 코드를 기준으로" — 봉인 시점 앵커가 2026-04-24 로 매우 오래됨. 이후 EOS 전환·DLQ-REACHABILITY 등 다수 토픽 반영되었으나 도입부 프레이밍은 갱신 안 됨 | 문서 자체 근거(용어 사용 실태) — Phase 축 혼용은 표본 #6 소스 근거 재사용 | 도입부 앵커를 최신 토픽(DLQ-REACHABILITY) 기준으로 교체하거나 앵커 문구 자체를 제거 | **S3** (표본 #6 확장) |

> **[Task 7 종결, 2026-07-02]** 위 3건 전건 `docs/context/PAYMENT-FLOW.md` 에 반영 완료 — Phase 3 다이어그램 R5a 재작성, 장애 복원 포인트 우선순위 재정렬(PENDING 배치 재픽업 1차/IN_FLIGHT 타임아웃 보조), 도입부 봉인 시점 앵커를 DLQ-REACHABILITY 기준으로 교체 + 헤더 날짜 갱신.

#### 4.1.3 `docs/context/TODOS.md` — 구조 + 3분류 판정

**구조적 문제 (개별 항목과 별개)**

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L9-27 "토픽 묶음 계획 (PR 단위)" 섹션 전체 | PR A/B/C 3묶음 모두 ✅ 완료 — 순수 과거 계획 정보이며 `docs/archive/README.md` 작업 뭉치 목록에 각 토픽(cleanup-batch-a, time-model-and-expiry, payment-eos-transition)이 이미 더 상세히 기록됨 | `docs/archive/README.md:35-36,39,46`(해당 토픽 행 존재) | 섹션 전체 삭제 (a) | **S4** (완료 잔존 + SSOT 중복) |
| L344-363 "## 완료" 섹션 전체(~20개 토픽 요약) | `docs/archive/README.md` "작업 뭉치 목록" 표가 동일 토픽을 더 정확하고 상세하게 이미 기록 — TODOS.md 는 "Planned Cleanup / Future Work"(미래 지향) 문서라는 파일 자체 성격과도 어긋남 | `docs/archive/README.md:16-53` (해당 전 토픽 행 존재, 날짜·상세 내용 일치 확인) | 섹션 전체 삭제 (a) — 필요 시 "완료 이력은 `docs/archive/README.md` 참조" 1줄로 대체 | **S4** (대장 비대, SSOT 미지정) |

**항목별 3분류 예비 판정**

| 항목 | 위치 | 분류 | 근거 |
|---|---|---|---|
| TC-13 | L35-37 | (a) 전체 삭제 | ✅ 완료 + archive 경로 존재(`docs/archive/payment-eos-transition/`) |
| TC-13-FOLLOW-1 | L49-51 | (a) 전체 삭제 | ✅ 해소 + archive 경로(`docs/archive/capacity-and-scaleout/`) |
| TC-13-FOLLOW-2 | L53-55 | (a) 전체 삭제 | ✅ 완료, "## 완료" EOS-FOLLOWUP-CLEANUP 항목(L352-356)과 중복 (그 섹션도 자체가 (a) 대상) |
| TC-13-FOLLOW-3 | L65-69 | (a) 전체 삭제 | ✅ 완료(대시보드+알람 모두). 잔여 "DE2"(lag 임계 재교정)는 이미 T4-B 정밀화 묶음(L181)에 동일 내용 존재 — 정보 손실 없음 |
| TC-13-FOLLOW-4 | L71-75 | (a) 전체 삭제 | ✅ 완료. 잔여 "DE1"(status 라벨 미분리)은 이미 T4-B 정밀화 묶음(L180)에 동일 내용 존재 |
| TC-13-FOLLOW-6 | L77-82 | (b) 혼합 | "완료 부분"(qualifier 명시, EOS-FOLLOWUP-CLEANUP) 문장 제거. "미채택 (잔여)" ChainedKafkaTransactionManager 재검토 조건은 보존 — 유일하게 이 문서에만 있는 미채택 결정 기록 |
| TC-13-FOLLOW-5 | L84-86 | (a) 전체 삭제, **S1** | canCompensateStock·RETRYING·`PaymentEventStatusCrossInvariantTest` 를 현재형으로 서술 — 셋 다 이후 토픽(STOCK-COMPENSATION-OTHER-PATHS/CLEANUP-BATCH-E)에서 완전 제거됨(F6/F7). "완료 잔존" 을 넘어 **존재하지 않는 코드를 현재처럼 서술**하는 사실 오류. Task 지시의 "canCompensateStock 잔존 언급" 대상 |
| [PG-SELFLOOP-ATTEMPT-GAP] | L61-63 | (a) 전체 삭제 | ✅ 완료 + archive 경로(`docs/archive/dlq-reachability/`). "수용 한계"(over-count) 는 CONCERNS.md L-13 에 이미 동일 내용 존재 |
| TC-4 | L92-94 | (a) 전체 삭제 | ✅ 완료, "## 완료" TIME-MODEL-AND-EXPIRY 항목(L346-351)과 중복 |
| TC-8 | L96-98 | (a) 전체 삭제 | 상동 |
| [NET-RETRY] | L102-104 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/cleanup-batch-b/`) |
| [FLYWAY-USER-SEED-GAP] | L106-108 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/ci-pipeline-redesign/`) |
| [PRODUCT-TIME-ABSTRACTION] | L112-114 | (a) 전체 삭제 | ✅ 완료, TIME-MODEL-AND-EXPIRY 중복 |
| [TIME-PRODUCT-NOW-UNIFY] | L116-118 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/time-model-followup/`) |
| [TZ-UTC-BACKSTOP] | L120-122 | (a) 전체 삭제 | 상동 |
| [BASEENTITY-AUDIT-SOURCE] | L124-126 | (a) 전체 삭제 | 상동 |
| [SCHEDULER-ENABLED-GATE] | L128-130 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/cleanup-batch-d/`) |
| [CLEANUP-FAILURE-COUNTER] | L132-134 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/observability-completion/`) |
| [GUARD-SKIP-EAGER-REGISTER] | L136-138 | (a) 전체 삭제 | 상동 |
| [SPOTBUGS-TEST-DEBT] | L140-142 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/cleanup-batch-b/`) |
| [CLEANUP-BATCH-B 후속] | L144-149 | (b) 혼합 | 3개 해소 불릿(L146,147,149) 제거, 미해소 불릿(L148 "infra 커버리지 집계 제외") 보존 — 현재도 유효한 정책 결정 |
| TQ-7 | L243-245 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/stock-compensation-other-paths/`) |
| TQ-8 | L247-250 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/cleanup-batch-e/`, `docs/archive/retry-metric-cleanup/`) |
| TC-1 | L254-256 | (a) 전체 삭제 | ✅ 완료 + archive(`docs/archive/observability-completion/`) |
| TC-3 | L258-263 | (b) 혼합 | "부분 완료" — 채택·구현 완료 프로즈는 간결화, "한계/잔여"(전체 일괄 resync·자동 발산 감지 미구현) 불릿은 **보존**(F17 실제 잔여 한계) |
| TC-6 | L265-270 | (c) 보존 | 미착수 open item, Phase 5 T4-D 연계 |
| TC-7 | L272-284 | (c) 보존, 단 **내용 정정 필요(S1)** | "현황" 절 "한도 초과 시 종결" 서술이 `incrementRetryOrFail` 미호출(F3) 및 `PaymentOutboxStatus.FAILED` 도달 불가(위 CONFIRM-FLOW L399 항목) 를 반영 못 함 — 항목 자체는 보존하되 "현황" 문장 정정 필요 |
| TC-11 | L292-304 | (c) 보존 | 이미 현황/보류 구분이 정확한 모범 사례. 변경 불요 |
| TC-12 | L306-318 | (c) 보존 | 보류 결정 기록, 재검토 조건 명시 — 변경 불요 |
| TC-15 | L320-340 | (c) 보존 | 진행 중(항목1·2 open, 항목3 만 완료 — 이미 정확히 구분됨) |
| TQ-1~TQ-6 | L210-241 | (c) 보존 | 전건 open, Phase 4 후속 |
| T4-A~T4-E | L159-206 | (c) 보존 | 전건 Phase 5 대기, 측정/인프라 의존 |

> **[Task 8 종결, 2026-07-02]** 구조적 문제 2건("토픽 묶음 계획"·"## 완료" 섹션) + 항목별 판정 24건(위 표 22건 그대로 적용 + 판정표 누락분 TC-13-FOLLOW-7/TC-9 2건, 동일 ✅완료+archive 경로 패턴이라 동일 (a) 판정 적용) 전건 `docs/context/TODOS.md` 에 반영 완료 — (a) 24건 전체 삭제, (b) 혼합 3건(TC-13-FOLLOW-6/[CLEANUP-BATCH-B 후속]/TC-3) 해소분 문장 제거·잔여 보존, (c) 보존 항목은 TC-7 "현황" 문장만 정정(`incrementRetryOrFail` 프로덕션 호출처 0 반영, 나머지 TC-6/TC-11/TC-12/TC-15/TQ-1~6/T4-A~E 변경 없음). "완료" 섹션 삭제에 따라 참조가 끊긴 TC-15 항목3·TC-11 의 완료 토픽 인용을 실제 토픽명(EOS-FOLLOWUP-CLEANUP)/archive 경로로 교체(Rule 1). 코드 확인 필요 항목 3건([PAYMENT-OUTBOX-INFLIGHT-UNUSED]/[STRUCTURED-LOGGING-MASKING-GAP]/[PAYMENT-STATUS-TRIGGER-DETECT-DEAD-BRANCH]) 신규 등재(코드 수정 없음). "분류 룰"에 "내부 Phase 번호는 README 개발 과정 Phase 와 별개" 1줄 추가. 헤더 날짜 갱신.

#### 4.1.4 `docs/context/CONCERNS.md`

**신규 발견 (표 형식)**

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L92 (L-1 EOS atomicity SSOT) | "`@Transactional(timeout=5)` 는 qualifier 미명시로 `@Primary JpaTransactionManager` 를 선택한다" — 코드와 반대 | `PaymentConfirmResultUseCase.java:116` qualifier 명시 확정 (F1) | "qualifier 명시 완료(EOS-FOLLOWUP-CLEANUP)"로 정정 | **S1** (표본 #1, 정확 위치 재확인) |
| L97 (L-1 "후속 과제") | "TC-13-FOLLOW-1 — `ChainedKafkaTransactionManager` 도입 검토" — TC-13-FOLLOW-1 은 TODOS.md 상 hostname/multi-instance 항목(✅ 해소)이지 ChainedKTM 항목이 아니다. ChainedKTM 은 TODOS TC-13-FOLLOW-6 이 정확한 ID. 같은 문장의 "TC-13-FOLLOW-3·4 후속" 도 두 항목 모두 이미 ✅ 완료라 "후속" 표현이 stale | `docs/context/TODOS.md:49-51`(TC-13-FOLLOW-1 실제 내용) vs `:77-82`(TC-13-FOLLOW-6 실제 내용), `:65-75`(FOLLOW-3·4 완료 마킹) | ID 오기 정정(FOLLOW-1→FOLLOW-6) + "완료(잔여 DE1/DE2 는 T4-B 정밀화)"로 갱신 | **S2** (문서 간 ID 불일치, 신규 발견) |
| L107 (L-3 전체) | "다중 인스턴스 동시 운영 검증 부재" — CAPACITY-AND-SCALEOUT 이 2-인스턴스 fencing 을 이미 실측 완료(정상/rebalance 중복 0, 분산 편차 0.7%) | `docs/context/TODOS.md:51`("2 인스턴스 fencing 실측..."), `docker/docker-compose.apps.yml:30`(hostname 고정 제거 주석 확인) | 항목 전체 삭제 대상(아래 3분류 표) | **S1** (신규 발견, 이하 3분류 표에서 처리) |
| L120-125 (L-6 전체) | "EOS multi-instance 확장 시 docker-compose hostname 충돌" — CAPACITY-AND-SCALEOUT 처방(hostname 라인 제거)이 이미 적용됨 | `docker/docker-compose.apps.yml:30`(payment-service 블록에 `hostname:` 라인 부재, pg/product/user/gateway 는 존재 — 대조 확인) | 항목 전체 삭제 대상 | **S1** (신규 발견) |
| L67-68 (C-9 "후속 해소" 불릿) | 대시보드(완료)와 alerting rule 인프라(완료) 서술 뒤에 "**잔여**: Alertmanager 통지 채널 미도입" — 완료분과 진짜 잔여 한계가 한 불릿에 혼재 | `observability/prometheus/prometheus.yml`(Alertmanager 설정 섹션 부재 — rule_files 평가만) | 완료 서술은 간결화, "Alertmanager 미도입" 잔여는 독립 불릿으로 분리 보존 | **S3** (혼합 서술, 경미) |

**3분류 예비 판정**

| 항목 | 위치 | 분류 | 근거 |
|---|---|---|---|
| C-7 | L47-50 | (a) 전체 삭제 | ✅ 해소(PAYMENT-EOS-TRANSITION), 이미 스트라이크스루 |
| C-12 | L52-55 | (a) 전체 삭제 | ✅ 해소(CAPACITY-AND-SCALEOUT), 이미 스트라이크스루 |
| C-11 | L76-80 | (a) 전체 삭제 | ✅ 해소(CLEANUP-BATCH-D), archive 경로 존재 |
| C-9 | L65-68 | (b) 혼합 | 위 신규 발견 항목 참고 — 완료분 축약, Alertmanager 잔여 보존 |
| C-1, C-2, C-3, C-4, C-5, C-6, C-8, C-10 | High/Medium/Low 각 절 | (c) 보존 | 전건 open, 스트라이크스루 없음 |
| L-1 | L84-97 | (c) 보존, **내용 정정 필요** | Kafka tx coordinator 의존은 여전히 유효한 수용된 한계. 단 L92(qualifier)·L97(ID 오기) 두 곳 정정 필요 (위 표) |
| L-2 | L99-101 | (a) 전체 삭제 | ✅ 해소(EOS-FOLLOWUP-CLEANUP), 이미 스트라이크스루 |
| L-3 | L103-107 | (a) 전체 삭제, **S1** | 위 신규 발견 — CAPACITY-AND-SCALEOUT 이 검증 완료 |
| L-4, L-5, L-7, L-8, L-9, L-11, L-12 | 각 절 | (c) 보존 | 전건 현재도 유효한 수용된 한계, 스트라이크스루 없음 |
| L-6 | L120-125 | (a) 전체 삭제, **S1** | 위 신규 발견 — hostname 라인 이미 제거되어 처방 완료 |
| L-10 | L139-141 | (a) 전체 삭제 | ✅ 해소(TIME-MODEL-AND-EXPIRY), archive 경로 존재 |
| L-13 | L151-153 | (a) 전체 삭제 | ✅ 해소(DLQ-REACHABILITY), archive 경로 존재, [PG-SELFLOOP-ATTEMPT-GAP](TODOS) 과 중복 |
| L-14 | L155-161 | (c) 보존(모범 사례) | "부분 해소" 구조로 완료분(poison-pill)과 잔여 한계(READY 잔류)를 이미 정확히 분리 서술 — 문장 단위 편집 불요, 3분류 규칙의 참고 예시로 재발방지 문서(Task 18)에 인용 완료 — `context-update` SKILL.md "완료 항목 정리" 절의 (b) 혼합 항목 기준으로 반영 |
| 회피된 우려 표 | L163-180 | (c) 보존 | topic 결정상 "기록 보존용" 명시 — 삭제 대상 아님 |

> **[Task 8 종결, 2026-07-02]** 신규 발견 4건 + 3분류 판정 전건 `docs/context/CONCERNS.md` 에 반영 완료 — L92 qualifier stale 문장을 "qualifier 명시 완료" 사실로 정정, L97 ID 오기(FOLLOW-1→FOLLOW-6) 정정 + 이미 완료된 FOLLOW-3/4 를 가리키던 "처방 후속" 문장을 TODOS T4-B `[DE2]` 참조로 교체, C-9 "잔여" 불릿 분리(완료분/Alertmanager 미도입 잔여 독립). (a) 6건(C-7/C-12/C-11/L-2/L-10/L-13) 전체 삭제, 신규 발견 (a) 2건(L-3/L-6) 도 전체 삭제. L-6 삭제로 발생한 L-5 "이전 L-6" 내부 참조 + L-14 "L-10이 명문화한" 내부 참조 dangling 을 실제 대상(L-12, TIME-MODEL-AND-EXPIRY 서술)으로 교체(Rule 1, 삭제 자체 부작용). (c) 보존 항목은 변경 없음(L-14 는 모범 사례로 문장 그대로 유지). PITFALLS.md §18/§17 의 L6/L2 dangling ID 참조는 지시대로 Task 9 범위로 남김 — 미착수. 헤더 날짜 갱신.

#### 4.1.5 `docs/context/PITFALLS.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 헤더 L3 | "최종 갱신: 2026-05-17" — 본문 §24(2026-06-27/06-30 산출물, absent(kafka_brokers) 분기)가 헤더보다 훨씬 최근 | F13(alerting rule 4그룹 도입 시점) | 헤더를 §24 도입 시점(또는 Task 정정 완료 시점)으로 갱신 | **S3** (표본 #3, 위치 재확인) |
| §18 "원인" 절 L187 + 제목 L182 | "L6: 외부 force resetToReady 등이 동일 orderId 재confirm 을 띄울 때 발생 가능" — 이 시나리오는 CONCERNS.md **L-12**("보상 끝난 결제의 새 confirm 사이클 cascade")의 내용과 정확히 일치. CONCERNS.md 의 실제 L-6 은 "EOS multi-instance hostname 충돌"로 전혀 다른 주제 — ID 참조가 어긋나 있음(리스트 재편 이력 추정) | `docs/context/CONCERNS.md:120-125`(L-6 실제 내용=hostname) vs `:147-149`(L-12 실제 내용=force resetToReady cascade, PITFALLS §18 과 문장 단위 일치) | "L6" → "L12" 로 정정 (제목 + 본문 2곳) | **S2** (문서 간 ID 참조 오류, 신규 발견) |
| §17 L180 | "(L2 알려진 한계)" — Redis AOF fsync race window 잔존 위험의 근거로 "L2" 를 인용하나, CONCERNS.md 의 현재 L-2 는 "`payment_event_dedupe` TTL 정리 스케줄러 부재"(✅ 이미 해소, 전혀 다른 주제)로 매칭되는 항목이 CONCERNS.md 에 없음 — 참조 자체가 dangling | `docs/context/CONCERNS.md:99-101`(L-2 실제 내용) — AOF/Redis crash 주제의 L-* 항목이 CONCERNS.md 전체에 부재 확인(grep) | 괄호 인용 삭제하거나, CONCERNS.md 에 신규 L-* 항목으로 등재 후 정확히 재연결 (Task 9 결정) | **S2** (dangling 참조, 신규 발견) |
| 본문 나머지 (§1,2,4~16,19~23) | 사실 목록(F1~F28) 및 코드 재확인 결과와 전건 일치 — 함정 서술 자체는 정합 | 각 절이 인용하는 배경 토픽(TIME-MODEL, STOCK-COMPENSATION-RECOVERY 등)과 F6/F7/F21~F23 대조 결과 불일치 0건 | 변경 불요(보존) | — |

> **[Task 9 종결, 2026-07-03]** 3건 전건 `docs/context/PITFALLS.md` 에 반영 완료 — 헤더 "최종 갱신"을 본문 최신 항목(§24, 2026-06-27 ALERTING-RULES-AND-FAULT-DRILL) 기준으로 동기화. §18 제목 + 본문의 "L6" → 최신 `CONCERNS.md` 기준 실제 대상 "L-12"(자연어 설명 병기: "보상 끝난 결제의 새 confirm 사이클 cascade")로 정정, "L7" 참조는 현행 `CONCERNS.md` L-7 과 일치해 자연어 설명만 병기. §17 의 dangling "(L2 알려진 한계)"는 Task 8 삭제로 `CONCERNS.md` 에 매칭 항목이 남아있지 않음을 재확인 — CONCERNS.md 는 이 태스크 범위 밖이라 신규 항목 등재 대신 괄호 인용을 "수용된 한계(CONCERNS.md 별도 미등재)" 자연어 서술로 교체.

### 4.2 Task 3 — 잔여 에이전트 문서 12파일 + smoke 5파일

대상 17파일(`ARCHITECTURE`/`STRUCTURE`/`STACK`/`stack/flyway-operations`/`CONVENTIONS`/`TESTING`/`INTEGRATIONS` + `conventions/` 5파일 + `docs/smoke/` 5파일) 전건 통독 + §1 사실 목록(F1~F28) 대조 + S1 클러스터(outbox REQUIRES_NEW/IN_FLIGHT stale, `PaymentOutboxStatus.FAILED` dead-terminal, `parallel-enabled` 층위 위반) grep 재확인. **결론: 이 17파일에는 위 S1 클러스터 3종이 나타나지 않는다** — `REQUIRES_NEW`/`IN_FLIGHT`/`toFailed`/`parallel-enabled` 전건 grep 0건(플로우 서술은 CONFIRM-FLOW/PAYMENT-FLOW 에만 있고, 이 17파일은 아키텍처/구조/컨벤션/스모크 레벨이라 outbox 재시도 디테일을 서술하지 않음). 대신 **이 17파일 자체 내부 대조에서 신규 S1 모순 2건**을 발견했다(4.2.2) — 다른 문서를 흉내 낸 게 아니라 코드 대조로 직접 확인.

#### 4.2.1 `docs/context/ARCHITECTURE.md`

전건 통독 + F1~F28 대조. `재고 복구 가드 (폐기)` 행이 이미 F7 기준으로 정합(死 코드로 정확히 표기), dedupe/AOF/Redis 설정 등 세부 수치도 소스와 일치(`docker-compose.infra.yml:98` `appendfsync always`, `PaymentEventDedupeStore` 어댑터 서술 F5 일치). "다음 토픽: PHASE-4 — Toxiproxy 8종 장애 주입" 서술은 stale 로 의심했으나 재검증 결과 **여전히 정확** — `docker/toxiproxy.json` 은 kafka-proxy 1개(latency toxic 전용)만 정의돼 있고 ALERTING-RULES-AND-FAULT-DRILL/FAULT-INJECTION-RESILIENCE 가 수행한 것은 이 중 "코디네이터 lag/DLQ/가용성" 알람 검증용 latency 드릴뿐이라, TODOS.md T4-A(`Kafka 지연/DB 지연/프로세스 kill/보상 중복 방지/FCG timeout/Redis 다운/재고 발산/DLQ 소진` 8종 전체)는 여전히 미착수(Task 2 에서 이미 (c) 보존 판정). 이 파일 범위에서 S1/S2 신규 발견 없음.

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 전체 | 재검토 결과 코드-문서 불일치·문서 내 모순 0건 — 변경 불요(보존) | ARCHITECTURE.md 전 항목을 F1~F28 + `docker/toxiproxy.json` + `docker-compose.infra.yml:98` 로 대조, 불일치 0 | 보존 | — |

> **[Task 9 종결, 2026-07-03]** 본문 판정대로 보존(변경 없음) + 4.2.18 S4 SSOT 반영으로 "HTTP 어댑터 회복성" 행에 `INTEGRATIONS.md` 링크 1줄 추가. 헤더 날짜 갱신.

> **[Task 10 추가 발견 및 정정, 2026-07-03]** stale 마커 게이트 재검증(`RecoveryDecision` grep)에서 §핵심 설계 결정 인덱스(L189-190) 신규 발견 — Task 9 재검토가 "재고 복구 가드 (폐기)" 행(L191)은 정확히 대조했으나 바로 위 "Final Confirmation Gate (FCG)"/"RecoveryDecision 값 객체" 두 행을 놓쳤다. 이 표는 "현재 운영 중" 헤더 아래인데도 (1) `RecoveryDecision` 클래스가 완전 삭제됐고(grep 0) (2) `PgFinalConfirmationGate` 는 클래스·테스트만 존재하고 프로덕션 호출처 0건(README 4.3.1 과 동일 축)임에도 두 행 모두 아무 표시 없이 현재형으로 서술돼 있었다. FCG 행에 "(미연결)" + dead code 설명, RecoveryDecision 행에 "(폐기)" + archive 링크 추가. 헤더 날짜 갱신.

#### 4.2.2 `docs/context/STRUCTURE.md`

`STRUCTURE.md` 자체가 아니라 **`STACK.md`/`TESTING.md` 와의 대조에서 코드-문서 불일치 2건을 신규 발견**했다 — 다른 문서 인용이 아니라 각 주장을 `build.gradle` 로 독립 재확인한 결과 `STRUCTURE.md` 쪽이 코드와 어긋난다.

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L169 (§빌드 트리거) | "`./gradlew test` \| 전 모듈 **단위 + 통합** 테스트 (Testcontainers MySQL/Redis 포함)" — 실제로는 `test` task 가 `integration` 태그를 **제외**한다(단위만). `STACK.md:117` "`./gradlew test` \| 단위 테스트만 (`integration` 태그 제외)" 가 정확 | `build.gradle:66-67` — `useJUnitPlatform { excludeTags 'integration' }` | "전 모듈 단위 + 통합 테스트" → "전 모듈 단위 테스트만(`integration` 태그 제외, 통합은 `integrationTest` 별도 task)"로 정정 | **S1** (신규 발견, `STACK.md` 와 정면 모순) |
| L177 (§정적 분석) | "JaCoCo: **모듈별** `build.gradle` 의 `jacocoTestReport` + `jacocoTestCoverageVerification`" — 실제로는 루트 `build.gradle` 의 `subprojects` 블록 안에 태스크 정의가 전부 있고, 서비스별 `build.gradle` 에는 `ext.jacoco.lineCoverageMinimum` 값만 존재(태스크 블록 없음). `TESTING.md:131` "설정 위치: 루트 `build.gradle` `subprojects` 블록 공통(4서비스 일괄). payment-service 개별 블록은 제거됨" 이 정확 | 루트 `build.gradle:20`(`subprojects {`)~`178`(`jacocoTestCoverageVerification {`) 안에 태스크 정의, `payment-service/build.gradle:15` 는 `ext` 값만 | "모듈별 `build.gradle` 의" → "루트 `build.gradle` `subprojects` 블록(4서비스 공통)의" 로 정정 | **S1** (신규 발견, `TESTING.md` 와 정면 모순) |

> **[Task 9 종결, 2026-07-03]** 두 건 전건 `docs/context/STRUCTURE.md` 에 반영 완료 — §빌드 트리거 절 전체를 `STACK.md` 참조 1줄로 교체(S4 SSOT 지정 동시 반영, 틀린 "단위+통합" 서술 자체를 제거), §정적 분석 JaCoCo 문장 "모듈별" → "루트 `build.gradle` `subprojects` 블록(4서비스 공통)의" 정정 + `TESTING.md` 링크 추가. 헤더 날짜 갱신.

#### 4.2.3 `docs/context/STACK.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L98-110 (§스케줄러 활성화 정책 — 매트릭스 + 역할별 목록) | "서비스별 활성 매트릭스"가 payment/product/pg/eureka·gateway 4행만 나열하고 **user-service 가 전체 누락**됨. 그러나 user-service 도 `SchedulerConfig`(`@EnableScheduling` + `@ConditionalOnProperty(scheduler.enabled=true)`)를 보유하고 `DependencyHealthMetrics`(`@Scheduled(fixedDelayString="${metrics.user.dependency.polling-interval-seconds:10}000")`)가 실제로 그 게이트 아래 동작한다(FAULT-INJECTION-RESILIENCE 에서 신규 도입 — STATE.md 재개 메모의 "user `@EnableScheduling` 누락" 갭도 이 컴포넌트 관련). 추가로 "스케줄러 역할별 목록" 불릿도 payment/pg/product 3개뿐이고 4서비스 공통 `DependencyHealthMetrics`(availability 알람이 소비하는 폴링 게이지, ARCHITECTURE.md 는 이미 정확히 "4서비스" 로 서술)가 어느 서비스 목록에도 등재되지 않음 | `user-service/.../infrastructure/config/SchedulerConfig.java:19-24`(`@EnableScheduling` + `@ConditionalOnProperty`), `user-service/.../infrastructure/metrics/DependencyHealthMetrics.java:89`(`@Scheduled`) — payment/pg/product 동일 클래스도 각각 `@Scheduled` 확인(`payment:115`, `pg:110`, `product:89`) | 매트릭스에 user-service 행 추가("`scheduler.enabled=true` 필요, 비활성/활성" — payment/product 와 동일 패턴), 4개 역할별 목록 불릿에 각각 `DependencyHealthMetrics`(의존성 가용성 폴링 게이지, availability 알람 소비) 추가 | **S1** (신규 발견 — FAULT-INJECTION-RESILIENCE 반영 누락, 헤더는 "6/30 ship 반영됨"이라 주장하지만 이 섹션은 실제로 안 됨) |

> **[Task 9 종결, 2026-07-03]** `docs/context/STACK.md` 에 반영 완료 — 활성 매트릭스에 user-service 행 추가, payment/pg/product/user 4개 역할별 목록 불릿에 `DependencyHealthMetrics` 각각 추가(게이트 유무 명시). §정적 분석 도구 JaCoCo 행은 4.2.18 S4 SSOT 반영으로 `TESTING.md` 참조 1줄로 축약. 헤더 날짜 갱신.

> **[Task 10 추가 발견 및 정정, 2026-07-03]** stale 마커 게이트 재검증(payment 측 `EventDedupeStore` grep)에서 §비즈니스 서비스 의존 신규 발견 — `spring-boot-starter-data-redis` 주석이 "pg/payment-side EventDedupeStore" 로 서술했으나, payment-service 에는 그 이름의 Redis 클래스가 없다(`EventDedupeStore`/`EventDedupeStoreRedisAdapter` 는 pg-service 전용, `find payment-service -iname "*EventDedupeStore*"` = 0건). payment-service 의 Redis 기반 dedupe 는 같은 줄에 이미 있는 "StockCachePort (Lua atomic)" dedup token 이 전담(F1 축) — "pg/payment-side" → "pg-side" 로 정정. 헤더 날짜 갱신.

#### 4.2.4 `docs/context/stack/flyway-operations.md`

`STACK.md` §DB 마이그레이션이 상세를 이 문서로 위임(SSOT 이미 명확)하는 패턴이 잘 지켜짐. 두 패턴(payment/pg=`db/migration` 단일 vs product/user=`db/schema`+`db/seed`) 서술을 `V*.sql` 실제 디렉토리 구조와 대조 — 일치. `MissingMigrationException` 3-step 대응 절차도 코드(`spring.flyway.ignore-migration-patterns` 기본값 `*:future` only)와 일치. S1/S2 신규 발견 없음(보존).

> **[Task 9 종결, 2026-07-03]** 재검토 결과 불일치 0건 재확인 — 변경 불요(보존). 헤더에 재검토 완료 각주만 추가.

#### 4.2.5 `docs/context/CONVENTIONS.md` (인덱스)

9줄, 5개 하위 문서 링크만 — 대상 5파일과 제목 1:1 대응 확인(파일 경로·앵커 유효). 신규 발견 없음(보존).

> **[Task 10 종결, 2026-07-03]** 재검토 결과 불일치 0건 재확인 — 변경 불요(보존). 하위 5파일 자체엔 "최종 갱신" 헤더 패턴이 없어(다른 conventions/smoke 파일과 동일 관례) 인덱스도 헤더 갱신 대상 아님.

#### 4.2.6 `docs/context/TESTING.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L166-178 (§현재 테스트 카운트) | "2026-06-14 기준" 스냅샷(873/48) — §2 표본 #7 인계 확장. 정확한 최신 카운트는 여전히 미확정(수정 시점 재실행 필요) | F26(`@Test` grep 342/240/50/9, 이후 13+ 토픽에서 추가/삭제) — Task 3 재확인으로 grep 재실행하니 payment 단위 파일 기준 `@Test` 합계가 F26 시점과도 또 달라져 있음(최신 파일 목록에 `DependencyHealthMetricsTest` 4서비스·`StockResyncUseCaseTest`·`PgConfirmListenerSplitIntegrationTest` 등 F26 이후 신규 파일 다수 확인) — 스냅샷이 계속 낙후되는 구조적 문제 재확인 | Task 9 수정 시점 `./gradlew test`/`integrationTest` 재실행 값으로 갱신(§2 #7 결론과 동일) | **S3** (표본 #7 확장 재확인) |

**S4 중복 발견**: `TESTING.md` §JaCoCo 커버리지 정책(L124-137, 측정대상/제외이유/게이트 산정 근거까지 상세)과 `STACK.md` §정적 분석 도구(L128, 같은 수치·같은 "단위 test exec 기준" 문장을 압축 재서술)이 사실상 동일 내용을 두 곳에서 설명 — SSOT 지정안은 4.2.7 이후 별도 절(4.2.18) 참고.

> **[Task 9 종결, 2026-07-03]** `docs/context/TESTING.md` 테스트 카운트 표를 `./gradlew test --rerun-tasks`(단위) + `./gradlew :<svc>:integrationTest --rerun-tasks`(통합) 재실행 값으로 갱신(2026-07-03 기준: 단위 861 — eureka 1/gateway 3/payment 467/pg 331/product 50/user 9, 통합 59 — payment 43/pg 9/product 6/user 1). "구조적으로 계속 낙후" 1줄 명시 추가. §JaCoCo 커버리지 정책은 4.2.18 S4 SSOT 지정대로 상세 서술 그대로 유지(SSOT). 헤더 날짜 갱신.

#### 4.2.7 `docs/context/INTEGRATIONS.md`

grep 상 "Elasticsearch/Logstash" 매치가 있었으나 실제로는 `net.logstash.logback:logstash-logback-encoder`(JSON 인코더 라이브러리명일 뿐, Elasticsearch 서버 언급 아님) — `관측성 통합` 표는 정확히 "Loki | Logback LogstashEncoder + LogFmt → Promtail/직접 push" 로 F19(Loki/Promtail 스택)와 일치. `PgConfirmPort`/`PgStatusLookupPort` 포트 분리, 502/504 retryable 승격(`ProductFeignConfig.java:54,56` `HttpStatus.BAD_GATEWAY`/`GATEWAY_TIMEOUT`), `pg_inbox.attempt` self-loop(F11) 등 전건 소스 대조 일치. S1/S2 신규 발견 없음(보존).

> **[Task 9 종결, 2026-07-03]** 본문 판정대로 보존(내용 변경 없음) + 4.2.18 S4 SSOT 반영으로 Contract test 문단에 `TESTING.md` 링크 1줄 추가. 헤더 날짜 갱신.

#### 4.2.8 `docs/context/conventions/code-style.md`

주석 금지 ID 예시 목록(`D7`/`PET-8`/`TC-3`/`L-14`/`TQ-1` 등)이 현재도 유효한 식별자 체계와 일치, Builder/Lombok/Try 블록 패턴 모두 실제 코드 패턴(`PgInbox.createPending`, `PaymentEvent.done(Instant, Instant)`)과 대조해 일치. S1/S2 신규 발견 없음(보존).

> **[Task 10 종결, 2026-07-03]** 재검토 결과 불일치 0건 재확인 — 변경 불요(보존).

#### 4.2.9 `docs/context/conventions/error-logging.md`

예외 계층 트리, `LogFmt` 사용법, AOP `@PublishDomainEvent`/`@PaymentStatusChange`/`@TransactionalEventListener(AFTER_COMMIT)` 패턴 서술을 실제 코드와 대조 — 일치. S1/S2 신규 발견 없음(보존).

> **[Task 10 종결, 2026-07-03]** 재검토 결과 불일치 0건 재확인 — 변경 불요(보존).

#### 4.2.10 `docs/context/conventions/kafka.md`

groupId 네이밍(`payment-service`/`pg-service`/`pg-service-dlq`), `DefaultErrorHandler`+`FixedBackOff(1000ms, 5)`+not-retryable 3종(`MessageConversionException`/`IllegalArgumentException`/`IllegalStateException`) 서술을 `KafkaErrorHandlerConfig.java:21,72,75-77` 로 대조 — 일치. `max.poll.records` 미설정(default 500) 서술도 `application.yml` grep 으로 확인 — 일치. S1/S2 신규 발견 없음(보존).

> **[Task 10 종결, 2026-07-03]** 재검토 결과 불일치 0건 재확인 — 변경 불요(보존).

#### 4.2.11 `docs/context/conventions/testing.md`

17줄, Bean Validation + TDD 흐름만 — `CLAUDE.md`/`commit.md` 룰과 대조해 일치. S1/S2 신규 발견 없음(보존).

> **[Task 10 종결, 2026-07-03]** 재검토 결과 불일치 0건 재확인 — 변경 불요(보존).

#### 4.2.12 `docs/context/conventions/transactions.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L20-23 (예시 코드) | `PaymentConfirmResultUseCase.handle` 예시로 `@Transactional(timeout = 5)` 만 표기 — 실제 코드는 `@Transactional(transactionManager = "transactionManager", timeout = 5)` 로 qualifier 를 **명시**한다(F1). 이 qualifier 는 바로 위 §0.3/CONCERNS L-1 이 다루는 EOS 트랜잭션 매니저 혼동 방지의 핵심 디테일이라, 컨벤션 문서의 "표준 예시"에서 빠지면 qualifier 없이도 되는 것처럼 오독될 위험이 있다 | `PaymentConfirmResultUseCase.java:116` — `@Transactional(transactionManager = "transactionManager", timeout = 5)`, 같은 파일 Javadoc L112-114 "qualifier `transactionManager` 는 `JpaConfig#transactionManager` 빈을 명시 지정 — Kafka `KafkaTransactionManager` 와의 혼동 방지" | 예시 코드에 `transactionManager = "transactionManager"` qualifier 추가 + 왜 명시하는지 1줄 근거(EOS 환경에서 `@Primary` 만으로는 의도가 코드에 드러나지 않음) 보강 | **S1** (신규 발견 — 표본 #1/CONCERNS L-1 과 같은 사실 축의 컨벤션 문서 반영 누락) |

> **[Task 10 종결, 2026-07-03]** 예시 코드에 `transactionManager = "transactionManager"` qualifier 추가 + qualifier 명시 근거 1줄(EOS 환경에서 `@Primary` 만으로는 의도가 코드에 드러나지 않음) 보강 완료.

#### 4.2.13 `docs/smoke/alert-firing-check.md`

25케이스(coordinator 6/guard-skip 3/dlq 7/availability 9) 표를 `observability/prometheus/rules/tests/*.yml` 실제 케이스 수·availability 드릴 4시나리오(a~d)를 `alert-firing-availability.sh` 로 대조 — 일치. "라이브 한계 명시"(consumer lag 비대칭 불가/txn abort 미발화) 서술도 F13/STACK.md §알람 규칙 서술과 일치. S1/S2 신규 발견 없음(보존).

> **[Task 10 종결, 2026-07-03]** 재검토 결과 불일치 0건 재확인 — 변경 불요(보존).

#### 4.2.14 `docs/smoke/infra-healthcheck.md`

"13개 서비스 컨테이너"(인프라 8 + scalable 5) 를 `scripts/smoke/infra-healthcheck.sh:76-103` `EXPECTED_INFRA_SERVICES`(8)+`SCALABLE_SERVICES`(5) 로 대조 — 정확히 일치. Eureka 5개 앱 등록 서술도 ARCHITECTURE.md 와 일치. S1/S2 신규 발견 없음(보존).

> **[Task 10 종결, 2026-07-03]** 재검토 결과 불일치 0건 재확인 — 변경 불요(보존).

#### 4.2.15 `docs/smoke/observability-load.md`

부하 생성기 옵션(`--profile`/`--fail-rate`/컨트롤 파일 축) 서술, "QUARANTINED/DLQ 패널은 단순 부하로 안 켜짐 → Phase-4 Toxiproxy 몫" 서술 — TODOS T4-A(미착수, 4.2.1 재확인) 와 일치. S1/S2 신규 발견 없음(보존).

> **[Task 10 종결, 2026-07-03]** 재검토 결과 불일치 0건 재확인 — 변경 불요(보존).

#### 4.2.16 `docs/smoke/observability-walkthrough.md`

대시보드 바로가기 URL 의 UID(`payment-business-d001`/`payment-system-d001`)를 `observability/grafana/dashboards/{business,system}-dashboard.json` 실제 `"uid"` 필드로 대조 — 정확히 일치. "로그(orderId)→traceId→Tempo" 진입 경로 서술도 F19/STACK.md 와 일치. S1/S2 신규 발견 없음(보존).

> **[Task 10 종결, 2026-07-03]** 재검토 결과 불일치 0건 재확인 — 변경 불요(보존).

#### 4.2.17 `docs/smoke/trace-continuity-check.md`

5개 서비스 hop(gateway→payment→pg/product→user/벤더) 서술, `ContextAwareVirtualThreadExecutors`/`PgOutboxChannel.offerNow`/`KafkaConsumerConfig` observation 참조를 ARCHITECTURE.md 횡단 관심사 표(F 대조 완료분)와 재대조 — 일치. S1/S2 신규 발견 없음(보존).

> **[Task 10 종결, 2026-07-03]** 재검토 결과 불일치 0건 재확인 — 변경 불요(보존).

#### 4.2.18 중복 서술(S4) — SSOT 지정안

| 중복 내용 | 위치 A | 위치 B | SSOT 지정 | 근거 |
|---|---|---|---|---|
| JaCoCo 커버리지 게이트 값·정책(측정대상/제외/게이트 산정 근거/단위 test exec 기준) | `TESTING.md` §JaCoCo 커버리지 정책 (L124-137, 상세 — 제외 이유·산정 근거 포함) | `STACK.md` §정적 분석 도구 표 JaCoCo 행 (L128, 거의 동일 문장 압축 재서술) | **`TESTING.md`** (상세 근거 보유) | 두 서술이 같은 수치(payment 0.86/pg 0.93/product 0.97/user 0.97/gateway·eureka 0.0)와 같은 "게이트는 단위 test exec 기준" 근거 문장을 반복 — `STACK.md` 행은 "값·근거는 `TESTING.md`#jacoco-커버리지-정책 참고" 1줄로 축소 (Flyway 가 이미 이 패턴 사용 중, 4.2.4) |
| 빌드 트리거 명령 표(`./gradlew build`/`test`/`:<svc>:test`/`:<svc>:integrationTest`) | `STRUCTURE.md` §빌드 트리거 (L166-171, 4.2.2 에서 `test` 범위 오류 발견) | `STACK.md` §빌드/검증 (L114-120, 정확 + `compose-up.sh`/`infra-healthcheck.sh` 까지 포함해 더 완전) | **`STACK.md`** (정확 + 더 넓은 범위) | 4.2.2 의 S1(모순) 과 동일 원인 — 두 표가 같은 명령 집합을 서로 다르게 서술하다 하나가 stale 화됨. `STRUCTURE.md` 절은 삭제하고 "빌드/검증 명령은 `STACK.md` 참고" 링크로 대체 |
| Contract test 2-layer 패턴(ErrorDecoder + 어댑터 propagation) | `TESTING.md` §Contract test 패턴 (L73-101, 상세 — 표+시나리오) | `INTEGRATIONS.md` §Cross-service HTTP 내 "Contract test" 문단 (L92, 요약 1줄) | **`TESTING.md`** (상세 소유), `INTEGRATIONS.md` 는 요약 유지 | 요약과 상세 관계라 중복 자체는 경미(S4 minor) — `INTEGRATIONS.md` L92 에 `TESTING.md` 명시 링크 추가만 권고, 삭제 불요 |
| CircuitBreaker "Phase 4 예정" 서술 | `ARCHITECTURE.md` 핵심 설계 결정 인덱스 "HTTP 어댑터 회복성" 행 (L181, 근거 없이 1줄) | `INTEGRATIONS.md` §벤더/Cross-service 회복성 (L94, "Phase 4 (T4-D) 예정" + fallbackFactory 마이그레이션 근거 포함) | **`INTEGRATIONS.md`** (근거 보유) | 경미 중복(S4 minor) — `ARCHITECTURE.md` 행에 "상세: `INTEGRATIONS.md`" 링크 추가 권고 |

**완료 기준 대조**: 17파일 전부 페이지별 판정 존재(4.2.1~4.2.17, "보존" 판정도 표/문장으로 명시), S1(4건: STRUCTURE.md×2 + STACK.md×1 + conventions/transactions.md×1) 전건 소스 근거(파일:라인) 포함, S4 중복 4건 SSOT 지정 완료(4.2.18).

> **[Task 9 종결, 2026-07-03]** S4 중복 4건 전건 SSOT 안대로 반영 완료 — JaCoCo(`STACK.md` 행 압축, `TESTING.md` 링크), 빌드 트리거(`STRUCTURE.md` §빌드 트리거 절 삭제 + `STACK.md` 링크로 대체), Contract test(`INTEGRATIONS.md` L92 에 `TESTING.md` 링크 추가), CircuitBreaker(`ARCHITECTURE.md` 행에 `INTEGRATIONS.md` 링크 추가). `conventions/transactions.md` 의 S1(qualifier 예시 누락)은 지시대로 Task 10 범위 — 미착수.

### 4.3 Task 4 — README + PAYMENT-FLOW-GUIDE

전건 통독 + §1 사실 목록(F1~F28) 대조 + Task 2 확정 S1 클러스터(outbox REQUIRES_NEW/IN_FLIGHT stale) grep 재확인. 표본 #5/#6 은 아래 4.3.1/4.3.4 에서 정확한 위치로 확장했다. 모든 소스 근거는 이번 태스크에서 독립적으로 재확인(코드 직접 grep/Read)했으며, 기존 판정(F1~F28, Task2 §4.1)을 인용하는 곳은 "동일 축" 표기만 하고 판정 자체는 재수행했다.

#### 4.3.1 `README.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L18-28 "🚀 주요 해결 과제" 표, "장애 내성 복구 체계" 행(L24) | "복구 판정 객체 + 스케줄링 + 재고 복원 가드 + 격리 직전 vendor 재조회" \| "6 분기 복구 결정 + 격리 전 최종 확인 + 동시성 가드" — 이 행이 서술하는 4개 개념 중 3개가 현재 코드에 없다: (1) "복구 판정 객체"(`RecoveryDecision`) 는 코드베이스에서 **완전 삭제**(파일 자체 0건) (2) "재고 복원 가드"(`canCompensateStock` 이중 조건 가드) 는 **완전 삭제**, `handleFailed`/`handleQuarantined` 는 가드 없이 `compensateAtomic` 직접 호출(F7) (3) "격리 직전 vendor 재조회"("격리 전 최종 확인")는 `PgFinalConfirmationGate` 클래스로 존재하나 **프로덕션 호출처 0건**(미연결) — "6 분기 복구 결정"도 이제 존재하지 않는 `RecoveryDecision` 의 분기 수. "스케줄링"(`PaymentReconciler`)만 현재도 유효 | `RecoveryDecision` 전체 grep 0(파일 자체 부재), `PaymentConfirmResultUseCase.java:280-303`(`handleFailed`/`handleQuarantined` 가드 없이 `compensateAtomic` 직접 호출), `PgFinalConfirmationGate.java` 존재하나 호출부 grep 결과 `PgStatusLookupPort.java`(의존 선언)뿐 — 실제 호출자 0건(`PAYMENT-FLOW.md:377` 이미 동일 결론) | 행 전면 재작성 — 현재 유효한 장애 내성 요소(`PaymentReconciler` 스케줄 복원, pg self-loop retry+DLQ 자동 격리, 종결 가드 재발행 등)로 교체. FCG 는 "존재하나 미연결(dead code, TODOS 등재 대상)"로만 언급 가능, 마치 동작 중인 것처럼 헤드라인화 금지 | **S1 critical** (표본 #5 확장 — 폐기 기능 서술, README 도메인 사실) |
| L295-328 "결제 상태 관리" 섹션, 특히 L297("보상 안전 가드 자체는 유지") + L325(mermaid GUARD 노드 "재고 복원 가드\n대기열 선점 중?\n결제 비종결?") | Phase 5→6 전환 캡션이 "PG 상태 조회 경계만 이동, 보상 안전 가드 자체는 유지"라고 명시하나, 실제로는 그 가드(`canCompensateStock`, 대기열 선점 중 + 결제 비종결 이중 조건)가 STOCK-COMPENSATION-OTHER-PATHS 에서 완전 삭제됐다 — "유지" 주장이 코드와 정반대(현재는 `QuarantineCompensationHandler.handle` 의 단일 종결 상태 체크만 남음, 이중 조건 가드 아님) | F7(`canCompensateStock` grep 0) + `PaymentConfirmResultUseCase.java:280-303`(가드 없는 직접 호출) + `QuarantineCompensationHandler.java:56-60`(남은 것은 `isTerminal()` 단일 체크뿐, "대기열 선점 중" 조건 없음) | 캡션에서 "보상 안전 가드 자체는 유지" 삭제 — STOCK-COMPENSATION-OTHER-PATHS 에서 가드가 제거되고 `QuarantineCompensationHandler` 의 단순 종결 체크로 대체됐음을 명시. mermaid GUARD 노드는 이 섹션이 "Phase 5 시점 스냅샷"(역사 기록)이라는 전제가 명확하면 다이어그램 자체는 보존 가능하나, 캡션의 "유지" 단정은 정정 필수 | **S1 critical** (신규 발견 — F7 축, 표본 #5 와 다른 위치) |
| L138-143 "Outbox 모델" 표, `payment_outbox` 행 | "4상태 머신 (PENDING / IN_FLIGHT / DONE / FAILED)" — enum 값 자체는 4개 맞지만(사실), `FAILED` 는 현재 프로덕션 코드에서 전이 경로 0건인 dead-terminal 상태(Task 2 CONFIRM-FLOW.md L~372-380 항목과 동일 축) — "4상태 머신"이라는 표현이 4개 상태가 대등하게 살아있는 것처럼 오독될 소지 | `PaymentOutboxStatus.java:9-12`(enum 4값 선언), `grep -rn "PaymentOutboxStatus.FAILED\|\.toFailed(" payment-service/src/main` = 0건(Task2 재확인 결과 재사용) | "4상태(PENDING/IN_FLIGHT/DONE/FAILED, FAILED 는 현재 도달 불가)" 로 각주 또는 3+1 표기로 조정 | **S1** (Task 2 클러스터의 README 확장 위치, minor) |
| L7-12 배너 | "🚧 진행 중 · Phase 6", "589 PASS", "⚠️ 정합이 안 맞을 수 있음" 경고 — 표본 #5 판정 그대로 잔존(F26/F27 근거 재확인: `@Test` grep 총합 이 문서 작성 시점 기준 641건(annotation 수, parameterized 확장 전) 로 589 와 이미 상이) | F26(TESTING.md 스냅샷 노후) + F27(`README.md:8` 자체가 F26 보다도 이전 값) + 본 태스크 재확인 `grep -rc "@Test" {payment,pg,product,user}-service/src/test` 합계 641(2026-07-02 시점, 이후 Task 11 수정 시점에 `./gradlew test` 재실행 값으로 최종 확정 필요 — annotation 카운트는 근사치일 뿐) | "589 PASS" 삭제하고 Task 11 실행 시점 `./gradlew test` 실측값으로 교체. "정합이 안 맞을 수 있음" 경고는 이번 토픽 완료(ship) 후 제거 여부를 Task 11에서 결정 | **S3** (표본 #5 정확 위치 재확인, 확정 수정은 Task 11 실행 시점 값 필요) |
| L9 "Phase 6 은 아직 작업/점검 중이며 후속 보강 작업이 누적되어 있음 (예: 보상 트랜잭션 자동 회복 layer, 컨텍스트 정합성 점검 등)" | 괄호 예시가 막연 — "보상 트랜잭션 자동 회복 layer" 가 가리키는 구체 항목이 문서 어디에도 명시되지 않음. 가장 근접한 실제 잔여 항목은 F16(stranded READY 자동 미복구, CONCERNS L-14/TQ-1)이나 이름이 다름 | F16(`docs/context/CONCERNS.md:159-161`) — 자동 미복구 잔여 한계가 존재하긴 하나 "보상 트랜잭션 자동 회복 layer" 라는 명칭과 직접 대응 안 됨 | 막연한 예시 문구를 TODOS 실항목(TC-6/TQ-1 등, Task 8 정정 이후 확정되는 슬림 대장 기준)으로 구체화 | **S3** (경미 — 사실 오류라기보다 모호성) |
| L292 "이상적 자원 할당(Sweet Spot)" | 평가성 표현("이상적", "최적의 수치") — 문체 기준 3항(평가·과시 형용사 제거) 대상 | 문체 기준 자체가 근거(코드 근거 대상 아님) | "이상적 자원 할당" → 사실 서술("커넥션 풀 상한을 시스템 한계에 맞춰 조정" 류)로 교정 | **S5** (경미) |
| L485 "HTTP(OpenFeign + LB) 또는 Kafka 메시지를 통해 서비스 간 통신" | "~를 통해" 번역투 — 문체 기준 3항 대상 | 문체 기준 자체가 근거 | "Kafka 메시지를 통해" → "Kafka 메시지로" 등 구체 동사/조사로 교정 | **S5** (경미) |
| L34-43 "🗺️ 개발 과정" 표 + L225-462 "이전 단계 작업" 섹션 캡션의 Phase 1~6 표기 | README 자체 축(개발 진행 순서)은 내적으로 일관되나, 같은 단어 "Phase" 가 GUIDE/PAYMENT-FLOW(결제 처리 단계)·내부 로드맵(MSA 전환/TODOS T4-* 버킷) 축과 충돌(표본 #6 축 확장) — 상세는 4.3.4 절 전수 채록 | 문서 자체 근거(용어 사용 실태 채록) | Task 11 이 실태(4.3.4) 기반으로 확정 — plan 결정상 README 축은 유지, 내부 문서와 별개임을 1줄 명시(Task 8 TODOS 분류 룰에서 수행) | **S2** (표본 #6 확장, 위치는 표 전체) |
| 위키 링크(L36-43, L52, 63, 114, 160-161, 163, 227, 293, 295, 330, 355, 404, 415, 445, 456, 480 등 25개 앵커) | 슬러그-실재 파일 대조: `cross-validation`/`tx-scope`/`retry-recovery`/`scenario-test`/`structured-logging`/`metrics`/`compensation-tx`/`idempotency`/`async-outbox`/`state-management`/`msa-transition`/`event-driven-choreography`/`stock-cache-recovery`/`outbox-pattern`/`message-delivery-and-dedupe`/`pg-confirm-flow`/`trace-propagation`/`pg-strategy`/`ai-workflow`/`architecture`/`Benchmark-Report` 전건 대응 파일 존재 확인 — **깨진 링크 0건** | `payment-platform.wiki/` 디렉토리 `ls *.md` 25개 전건 대조(README 인용 21종 전부 매치) | 변경 불요(보존). 단 `outbox-channel-dispatch.md` 는 위키에 존재하나 README 어디서도 링크 안 됨 — 누락이 아니라 README 가 모든 위키 페이지를 링크할 의무는 없으므로 보존 판정, Task 5/6 판단 대상으로만 메모 | — (보존) |
| Kafka 토픽 카탈로그 표(L104-112), Redis 2 인스턴스 서술(L55, L473), 스택 표(L471-476) | `application.yml`/`docker-compose.infra.yml` 대조 — 5개 토픽명(`PaymentTopics.java` 등)·redis-dedupe/redis-stock 분리·Java 21/Spring Boot 3.4.4 등 전건 일치 | `payment-service/.../PaymentTopics.java:17`, `pg-service/src/main/resources/application.yml:83-85`, `payment-service/src/main/resources/application.yml:114-115`, `docker/docker-compose.infra.yml:69` | 변경 불요(보존) | — |

> **[Task 11 종결, 2026-07-03]** §4.3.1 전건(S1 critical 2건 + S1 minor 1건 + S3 2건 + S5 2건 + S2 1건) `README.md` 에 반영 완료. (1) "장애 내성 복구 체계" 행 — `RecoveryDecision`/`canCompensateStock`/FCG 3개념을 제거하고 현재 유효한 요소(PG self-loop 재시도+백오프, DLQ 자동 격리, `PaymentReconciler` 스케줄 복원, `compensateAtomic` Redis Lua 원자 연산)로 전면 재작성 — FCG 는 지시대로 헤드라인에서 제외(미연결 dead code). (2) "결제 상태 관리" 캡션 — "보상 안전 가드 자체는 유지" 삭제, 이 섹션 전체를 "Phase 5 시점 스냅샷"으로 명시하고 Phase 6 에서 가드가 `QuarantineCompensationHandler` 단일 종결 체크로 대체됐음을 병기(mermaid GUARD 노드는 스냅샷 전제가 명확해져 원형 보존). (3) Outbox 모델 표 — `FAILED` dead-terminal 각주 반영("현재 도달 불가"). (4) 배너(S3) — "🚧 진행 중"→"✅ Phase 6 완료", "589 PASS"→"단위 861 / 통합 59 PASS"(Task 9 실측값), "정합이 안 맞을 수 있음" 경고 삭제(토픽 정합화 완료 전제), 막연한 "보상 트랜잭션 자동 회복 layer" 예시 문구를 포함한 "아직 작업/점검 중" 줄 삭제 — Phase 7 예고에 "알람 규칙 + Toxiproxy 장애 드릴 인프라는 선행 구축 완료"(FAULT-INJECTION-RESILIENCE/ALERTING-RULES-AND-FAULT-DRILL 반영) 1줄 추가. (5) 문체(S5) 2건 — "이상적 자원 할당"·"최적의 수치" 평가 형용사 제거, "Kafka 메시지를 통해" 번역투를 "Kafka 메시지로" 로 교정. (6) Phase 표기(S2, L34-43 표 전체) — plan 결정대로 README 축(Phase 1~7) 유지, 내부 로드맵과의 번호 불일치는 README 에서 설명하지 않음(disambiguation 미반영, 이미 `TODOS.md` 분류 룰에 1줄 명시돼 있어 README 추가 불요) — 변경 없음이 곧 판정 반영. 위키 링크 25개·Kafka 토픽 카탈로그·스택 표는 재확인 결과 그대로 보존. `./gradlew test` 재실행 861 PASS(문서 전용 태스크, 코드 무변경). README diff 의 도메인 사실(S1) 3항목은 §4.3.3 에 반영 내역 병기(ship domain-expert 대조 입력용).

#### 4.3.2 `docs/context/PAYMENT-FLOW-GUIDE.md`

**S1 critical 클러스터 — outbox 발행 실패 stale 서술이 GUIDE 에도 확장 잔존**(CONFIRM-FLOW.md/PAYMENT-FLOW.md 의 표본 #12 클러스터와 완전히 동일한 사실 오류가 GUIDE 에도 5곳 독립 잔존 — 짝 문서 CONFIRM-FLOW.md 를 베낀 것으로 추정되나 이번 판정은 소스로 별도 재확인):

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L105 (§A Phase 3, 단계 14) | "발행 실패 → TX rollback 이지만 `IN_FLIGHT` 유지 → 워커 폴백" — 실제로는 claim·발행·완료가 단일 `@Transactional`(`OutboxRelayService.relay`) 이라 발행 실패 시 TX 전체 롤백으로 `IN_FLIGHT` 가 커밋된 적 없이 PENDING 그대로 복귀 | `OutboxRelayService.java:49-78`(단일 `@Transactional`, F2) | "발행 실패 → 예외가 relay TX 를 롤백해 선점까지 함께 되돌림 → PENDING 복귀" 로 정정 | **S1 critical** |
| L107 (§A Phase 3, 단계 15 각주) | "폴백: `OutboxWorker`(`@Scheduled` fixedDelay 5s) — `IN_FLIGHT` 5분 타임아웃 → PENDING 복귀 후 재픽업" 을 발행 실패의 **1차 회복 경로**처럼 서술 — 실제 1차 경로는 위 TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업이고, `recoverTimedOutInFlightRecords`(IN_FLIGHT 5분 타임아웃 회수)는 워커 크래시 등 별도(더 드문) 시나리오 | `OutboxWorker.java:26,38,41`(F3 — `recoverTimedOutInFlightRecords`/`findPendingBatch` 만 호출), `application.yml:147`(fixed-delay-ms 5000) | "1차 경로: TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업. `IN_FLIGHT` 5분 타임아웃 회수는 워커 크래시 등 보조 경로"로 우선순위 재정렬(PAYMENT-FLOW.md L200 항목과 동일 수정 방향) | **S1** |
| L214-217 (§B-2 PUBREC 서브그래프) | "Kafka 발행 실패 → `IN_FLIGHT` 유지 → `OutboxWorker` @5s `IN_FLIGHT` 5분 타임아웃 → PENDING 복귀 → relay 재시도" — 위와 동일 오류가 mermaid 다이어그램으로 재등장 | 상동 | "발행 실패 → TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업" 흐름으로 노드·엣지 재작성 | **S1 critical** |
| L254-255 (§C 회복 경로 색인 표) | "Kafka 발행 실패(payment→broker) \| `IN_FLIGHT` 유지 → 타임아웃 후 PENDING 복귀 → relay 재시도" — 표 형태로 재등장 | 상동 | "TX 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업" 으로 정정 | **S1 critical** |
| L294, L307-308 (§D 통합 플로우차트) | `OW["OutboxWorker @5s<br/>IN_FLIGHT 타임아웃 회수"]`, `REL -. 발행 실패·IN_FLIGHT 유지 .-> OW` — 마스터 다이어그램에도 동일 오류 | 상동 | "발행 실패" 엣지를 "TX 롤백·PENDING 복귀"로 재라벨, `OW` 노드는 "IN_FLIGHT 5분 타임아웃 회수(보조 경로)"로 역할 명확화 | **S1** |

**나머지 부분 — 전건 검증 결과 정합(보존)**: 이번 태스크에서 GUIDE 의 기술적 주장 다수를 독립 소스 대조했다.

- 단계 25 "실패(FAILED) → 재고 보상 먼저(`compensateAtomic`) → 실패 확정" — `PaymentConfirmResultUseCase.java:280-287` 와 정확히 일치(가드 없는 직접 호출, F7). README 와 달리 GUIDE 는 이미 가드 삭제 사실을 정확히 반영하고 있음 — 정정 불요
- 단계 26 EOS abort → `DefaultErrorHandler`(FixedBackOff 1s×5) → `events.confirmed.dlq` — F12(`KafkaConsumerConfig.java:92`) 와 일치
- 단계 23 "DONE+APPROVED 재배달 → 재고확정 재발행" — F10(`PaymentConfirmResultUseCase.java:124-138`) 와 일치
- 단계 17 `EventDedupeStore.markSeen`(pg-service) — `pg-service/.../application/port/out/EventDedupeStore.java` 존재 확인(payment 측만 제거됐고 pg-service 는 존치 — stale 마커 아님)
- §C "PG 재시도 한도 초과(DLQ)" 행, 단계 20 `pg_inbox.attempt` 서술 — F11/F12 일치
- `PaymentReconciler`(§B-2 STUCK/RECON, @2분 `resetToReady`) — `PaymentReconciler.java:44`(`fixed-delay-ms:120000` 기본값 2분) 일치, 최근 롤백 이력(STATE.md 재개 메모)도 이 메서드 자체의 존재·주기는 건드리지 않음
- 인용된 클래스/메서드명 17종(`markStockCacheDownQuarantine`/`executeConfirmTx`/`StockEventUuidDeriver`/`PgTerminalReemitService`/`DuplicateApprovalHandler`/`PgInboxImmediateWorker`/`processInProgressZombie`/`invokeVendor`/`applyOutcome`/`PgOutboxRelayService`/`PgEventPublisher`/`shouldRetry`/`handleActiveInbox`/`insertPendingAndPublish`/`OutboxImmediateEventHandler`/`canApplyConfirmResult`/`terminalResendMetrics`) 전건 grep 존재 확인 — 개명·삭제 0건
- 약어 범례의 `D7`/`SCR-6` 내부 ID — `CONFIRM-FLOW.md:22,124,138,164,169,224,423,513` 에 실사용 확인, dangling 아님

**S5 문체 판정**: GUIDE 는 번호 시퀀스·표·mermaid 위주 구조화 기술 문서로 위키/README 와 장르가 다르다 — 평가·과시 형용사, 번역투, 짧은 단정문 연발 패턴이 grep 상 0건(`를 통해`/`함으로써`/`방식을 사용`/`매우`/`완벽`/`탁월`/`최적` 등 전건 매치 없음). **문체 수정 대상 없음(보존)** — Task 12 는 위 outbox 클러스터 5곳의 사실 정정에만 집중.

> **[Task 12 종결, 2026-07-03]** S1 클러스터 5건 전건 `docs/context/PAYMENT-FLOW-GUIDE.md` 에 반영 완료. L105(§A 단계14) — "발행 실패 → 예외가 relay 전체를 감싸는 단일 TX 를 롤백해 선점(13단계)까지 함께 되돌림 → PENDING 그대로 복귀"로 정정. L107(§A 단계15 각주) — "1차 회복 경로: TX 롤백으로 PENDING 복귀한 건은 `OutboxWorker` 의 5초 주기 배치 재픽업이 곧바로 다시 집어간다. `IN_FLIGHT` 5분 타임아웃 회수는 워커 크래시 등 드문 경로의 보조 안전장치다"로 우선순위 재정렬. L214-217(§B-2 PUBREC) — `SENDF → ROLLBACK(relay TX 전체 롤백/PENDING 즉시 복귀) → OW(PENDING 배치 재픽업, 1차 경로) → REREL` 순서로 노드·엣지 재작성. L254-255(§C 표) — "Kafka 발행 실패" 행을 "relay TX 전체 롤백 → PENDING 즉시 복귀 → 5초 주기 재픽업" / "`OutboxRelayService.relay` 단일 TX"로 정정. L294·L307-308(§D 마스터 플로우차트) — `OW` 노드를 "PENDING 배치 재픽업(1차)" + "IN_FLIGHT 5분 타임아웃 회수(보조)"로 역할 분리, `REL -. 발행 실패, TX 롤백/PENDING 복귀 .-> OW` 로 엣지 재라벨(mermaid 노드 라벨 금지 문자 — 중괄호·중간점·유니코드 화살표·따옴표 — 미사용 확인). 문체(S5)는 진단대로 수정 대상 없음(보존). 문서 헤더에 "정정 2026-07-03(outbox 발행 실패 회복 경로 사실 정정, DOCS-CONSISTENCY-OVERHAUL Task 12)" 1줄 추가. `./gradlew test` 대상 아님(문서만, 코드 무변경).

#### 4.3.3 README 도메인 사실(S1) 항목 — ship domain-expert 대조 입력용

plan 게이트 결정(완료 기준 "README diff 중 도메인 사실(S1) 항목은 ship domain-expert 대조 입력에 포함")에 따라 Task 11 수정 후 ship 단계에서 domain-expert 가 별도 대조해야 할 항목을 표시한다.

1. "주요 해결 과제" 표 "장애 내성 복구 체계" 행 전면 재작성 (4.3.1 첫 행) — `RecoveryDecision` 삭제/`canCompensateStock` 삭제/FCG 미연결 3사실 동시 반영
   > **[Task 11 반영, 2026-07-03]** L24 를 "PG self-loop 재시도(백오프) + 한도 소진 시 DLQ 자동 격리 + `PaymentReconciler` 스케줄 복원 + 재고 보상 Redis Lua 원자 연산(`compensateAtomic`)" / "**DLQ 자동 격리** + 멱등 보상(중복 실행 안전)"으로 교체. FCG 는 문구에 포함하지 않음(미연결 dead code 를 헤드라인화하지 않는다는 수정 방향 그대로 적용).
2. "결제 상태 관리" 섹션 "보상 안전 가드 자체는 유지" 캡션 삭제 + mermaid GUARD 노드 처리 방식 (4.3.1 둘째 행)
   > **[Task 11 반영, 2026-07-03]** L297 캡션을 "본문과 다이어그램은 복구 판정 객체 + 격리 전 최종 확인 + 이중 조건 보상 가드가 있던 시점의 스냅샷"으로 교체 + "Phase 6 에서 ... 복구 판정 객체·이중 조건 보상 가드는 삭제되고 `QuarantineCompensationHandler` 의 단일 종결 체크로 대체" 1줄 추가. mermaid GUARD 노드(L325)는 스냅샷 전제가 캡션에 명시돼 원형 그대로 보존(수정 방향의 "보존 가능" 옵션 선택).
3. Outbox 모델 표 `FAILED` dead-terminal 각주 반영 (4.3.1 셋째 행)
   > **[Task 11 반영, 2026-07-03]** L142 를 "4상태 (PENDING / IN_FLIGHT / DONE / FAILED, FAILED 는 현재 도달 불가) + 선점 방식"으로 교체.

#### 4.3.4 Phase 표기 실태 전수 채록 (Task 11 결정 입력)

동일한 "Phase" 단어가 최소 3개 축으로 혼용된다 — 표본 #6 이 발견한 2축(README/PAYMENT-FLOW) 에 더해 이번 태스크에서 3번째 축(MSA 로드맵/TODOS 버킷)을 전수 채록해 확장했다.

| 축 | 의미 | 사용 문서·위치 | 번호 체계 | 비고 |
|---|---|---|---|---|
| **A. 개발 진행 순서** | 위키 페이지가 커밋된 "개발 단계" 이정표 — README 고유 축 | `README.md` 배너(L7,11), 개발 과정 표(L34-43), 이전 단계 작업 섹션 캡션(L225,229,297,332,358,406,417,447,458) | Phase 1~6 (+ETC) 완료, **Phase 7 다음 예정** | 코드 개념 아님, 순수 문서 조직. 내적으로는 일관됨(README 안에서 서로 모순 없음) |
| **B. 결제 처리 단계** | 결제 1건이 checkout→confirm→outbox→pg→결과확정→폴링까지 통과하는 처리 단계 | `PAYMENT-FLOW.md:23-138`(Phase 1~5, 폴링을 Phase5 에 포함), `CONFIRM-FLOW.md:4`("Phase 1~5 전체" 인용), `PAYMENT-FLOW-GUIDE.md:70-145`(Phase 1~6, **폴링을 Phase6 으로 별도 분리** — PAYMENT-FLOW.md 와 하위 경계가 다름) | Phase 1~5 (PAYMENT-FLOW/CONFIRM-FLOW) vs Phase 1~6 (GUIDE) | A 축과 완전 무관 + B 축 내부에서도 PAYMENT-FLOW 와 GUIDE 사이에 폴링 분리 여부가 다름(경미한 하위 불일치, 표본#6 확장 신규 발견) |
| **C. MSA/기능 로드맵 버킷** | TODOS.md 의 미래 작업 뭉치 번호(T4-A~E 등) — 프로젝트 로드맵상 "다음 큰 덩어리"를 가리키는 축 | `PAYMENT-FLOW.md:6`("MSA 4서비스 분리 + Phase 0~3.5 + PRE-PHASE-4-HARDENING 봉인 시점"), `ARCHITECTURE.md:181,228`("CircuitBreaker 는 Phase 4", "Phase 4 후속"), `TODOS.md`(T4-A~E 항목명 자체가 이 축의 번호를 그대로 사용), `docs/smoke/*.md` 일부(Phase-4 Toxiproxy 인용) | Phase 0~3.5 완료 + PRE-PHASE-4-HARDENING 봉인 + **Phase 4 = T4-A~E 버킷(Toxiproxy 8종/k6 재설계/로컬 오토스케일러/CircuitBreaker) 미착수** | PAYMENT-FLOW.md:6 앵커 자체는 2026-04-24 수준 오래된 시점 표기(표본#6 확장, Task 7 정정 대상) |

**핵심 교차 발견**: README 축(A)의 "다음 Phase 7"(L11-12, "회복성 검증 = 장애 주입 + k6 시나리오 재설계 + 로컬 오토스케일러 + 서킷브레이커")과 내부 로드맵 축(C)의 "Phase 4"(T4-A~E: Toxiproxy 8종 장애 주입/k6 시나리오 재설계/로컬 오토스케일러/CircuitBreaker)는 **내용이 완전히 동일한 작업 뭉치를 서로 다른 번호(7 vs 4)로 부르고 있다** — `docs/context/TODOS.md:159-196`(T4-A~D 항목명·내용) 대조로 확인. 세 축 모두 실제로 열려 있는(미착수) 항목이라는 점에서 완료/진행 상태 서술 자체는 정확(README "다음" 표기는 사실과 일치) — 문제는 번호 불일치뿐.

**Task 11 결정 입력**: plan 이미 "README 는 독자용 Phase 1~7 체계 유지"로 확정했으므로 축 통일은 비범위. 다만 위 교차 발견(README Phase 7 = 내부 로드맵 Phase 4, 같은 작업)은 독자 혼란 소지가 있어 Task 11 에서 README "다음 Phase 7" 절 근처에 "내부 로드맵 문서의 Phase 번호와는 무관한 별도 체계"라는 1줄 disambiguation 추가를 권고(선택, plan 승인 필요 시 반영 — 강제 완료 기준 아님).

### 4.4 Task 5 — 위키 도메인 코어 12페이지

대상 12페이지(`outbox-pattern` / `outbox-channel-dispatch` / `pg-confirm-flow` / `async-outbox` / `tx-scope` / `message-delivery-and-dedupe` / `idempotency` / `compensation-tx` / `stock-cache-recovery` / `state-management` / `retry-recovery` / `pg-strategy`) 전건 통독 + F1~F28 대조 + Task 2/3/4 확정 클러스터(outbox REQUIRES_NEW/IN_FLIGHT stale · `PaymentOutboxStatus.FAILED` dead-terminal · `RETRYING` enum 부재 · `RecoveryDecision`/FCG/보상 가드 삭제 · payment 측 `EventDedupeStore` 폐기 · `parallel-enabled` 층위) grep 재확인. **핵심 결론**: 이 12페이지에서 가장 심각한 오류는 outbox 발행 실패 클러스터가 아니라 — `RecoveryDecision`(PG 상태 선행조회 기반 6분기 복구 결정 값 객체) + `RETRYING` 상태 + FCG 가 지금도 살아있는 것처럼 서술하는 **별도의 대형 클러스터**다. `state-management.md` 배너가 이를 "유지된다"고 명시해 위키 안에서 정본화돼 있다. 소스 재확인 결과 `RecoveryDecision` 클래스는 완전 삭제(grep 0), `PaymentEventStatus` 는 8개 값(`READY`/`IN_PROGRESS`/`DONE`/`FAILED`/`CANCELED`/`PARTIAL_CANCELED`/`EXPIRED`/`QUARANTINED`)뿐(F6, `PaymentEventStatus.java:3-12`) — `RETRYING` 없음, `canApplyConfirmResult()`(`PaymentEventStatus.java:41-46`)는 `READY`/`IN_PROGRESS` 두 상태만 EOS 진입 허용. `PaymentConfirmResultUseCase.handleFailed`/`handleQuarantined`(`:280-303`)는 재고 복구 가드나 PaymentEvent 재조회 없이 `stockCachePort.compensateAtomic` 을 바로 호출한다 — 이 3사실은 CLEANUP-BATCH-E(2026-06-21, RETRYING 제거)·STOCK-COMPENSATION-OTHER-PATHS(2026-06-21, 가드 제거)·PAYMENT-EOS-TRANSITION(2026-05-17, PG 폴링 복구 사이클 자체를 EOS 컨슈머로 대체)로 이미 정정 확정된 사실(F6/F7)과 같은 축이다. 아래 페이지별 판정에서 이 클러스터를 **"RD/RETRYING/FCG 클러스터"**로 축약 인용한다.

#### 4.4.1 `outbox-pattern.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L11-13 (`## 표기 규칙` 빈 헤더) | 헤더 바로 다음 줄이 `## 왜 outbox 인가` — 내용 없음 | 위키 파일 자체 (§2 표본 #11 재확인) | 빈 헤더 제거 | **S4** |
| L63, L80/L84/L92 (`payment_outbox` 상태표 + state diagram `IN_FLIGHT --> FAILED`) | "4상태 머신 (PENDING/IN_FLIGHT/DONE/FAILED)"·"FAILED: 보상 경로에서 영구 실패로 마킹(종결 상태)" — `FAILED` 로 전이하는 코드 경로 현재 0건 | `PaymentOutboxUseCase.java`(`incrementRetryOrFail` 호출처 0, F3), `grep "PaymentOutboxStatus.FAILED\|.toFailed(" payment-service/src/main` = 0 (Task 2 §4.1.1 재확인) | "FAILED 는 현재 도달 불가(dead terminal)" 각주 추가. state diagram 의 `IN_FLIGHT --> FAILED` 엣지는 dead-branch 표기 또는 제거 | **S1** (기존 클러스터 확장) |
| L72 "가장 정밀한 모델이다." + L94-98 "핵심 동작" 3불릿 | 평가 형용사·꼬인 구절("타임아웃 회수가 백오프 적용해 되돌림") — 단 **사실관계 자체는 정확**(재확인: `OutboxRelayService.java:49-78` 단일 TX, `PaymentOutboxUseCase.recoverTimedOutInFlightRecords`→`PaymentOutbox.incrementRetryCount`(`PaymentOutbox.java:49-58`)가 실제로 retryCount+1·`nextRetryAt` 백오프 적용 확인 — "두 모델 비교" 표 L329 도 정확) | topic "문체 수정 기준" 기준 예문(topic 문서 §문체 수정 기준, "후" 버전) 이 이 구절 자체를 대상으로 이미 확정돼 있음 | Task 13 에서 topic 문서의 계산된 "후" 텍스트를 그대로 실반영(추가 판단 불요) | **S5** (문체만, 사실 정정 아님 — 이미 캘리브레이션 완료) |
| "발행 흐름"(L102-112) mermaid | `Pub -->|실패| Rollback[TX 롤백 -> PENDING, 다음 cycle 재시도]` — 정확 | `OutboxRelayService.java:49-78` | 변경 불요(보존) | — |
| 서사 후보 | 근거 있는 서사 후보 없음 — 이 페이지의 핵심 사실(단일 TX 롤백 구조)은 EOS 전환 이전부터 불변(F2 "PAYMENT-EOS-TRANSITION 이전부터 현재까지 이 구조")이라 PITFALLS/archive 에 대응하는 "전환 이력"이 없음. 강제 없음 | — | — | — |

#### 4.4.2 `outbox-channel-dispatch.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 전체 | 설정 키/기본값(`pg.inbox.channel.{capacity,worker-count}`=1024/5, `pg.scheduler.inbox-polling-worker.*`=5000ms/10/60000ms/60000ms) 전건 `application.yml:88-104` 대조 — 일치 | `pg-service/src/main/resources/application.yml:88-104` | 변경 불요(보존) | — |
| "관련 설정 → 발행 큐" 표(L157-164) | `pg.outbox.channel.worker-count`(실제값 1) 가 표에서 누락 — capacity 만 기재. 오류는 아니나 발행 큐 워커 수가 1이라는 사실이 본문 어디에도 없어 "여러 워커"로 오독 가능 | `application.yml:99-100` (`pg.outbox.channel.worker-count: 1`) | worker-count 행 추가 | **S3** (누락, 경미) |
| DLQ 임계("4회 초과 시 DLQ", L139) | **[Task 14 재확인, 확정]** pg_outbox 발행(Kafka publish) 실패는 4회 한도(`RetryPolicy.MAX_ATTEMPTS=4`)의 적용 대상이 아니다 — `PgOutboxRelayService.relay`/`PgOutboxPollingWorker.poll` 어디에도 attempt 카운터가 없어 발행 성공까지 무한 재폴링한다. MAX_ATTEMPTS=4 는 `PgVendorCallService.handleRetry`(벤더 confirm self-loop, `commands.confirm` outbox 재시도) 전용 카운터다 — "Kafka publish 실패" 행에 이 문구가 잘못 끼어든 것으로 확정 | `PgOutboxRelayService.java:46-83`(attempt 파라미터 없음), `PgOutboxPollingWorker.java:79-88`(재시도 카운트 없이 매 폴링마다 무조건 relay 시도), `PgVendorCallService.java:180-186`(`handleRetry` 가 `RetryPolicy.shouldRetry(attempt)` 로 4회 한도 판정 — attempt 는 `pg_inbox` 컬럼, outbox relay 와 무관) | outbox-channel-dispatch.md "Kafka publish 실패" 행에서 "4회 초과 시 DLQ" 제거 → "무한 재폴링, DLQ 전이 없음"으로 정정 + "PG 5xx self-retry" 행에 attempt 4 도달 시 DLQ 각주 추가 + 두 행 사이 한도 소속 설명 문단 | **S2 (확정, Task 14 반영 완료)** |
| 서사 후보(도입 동기형, 문서 상단) | `pg_inbox`/`pg_outbox` 2단 채널 구조 자체가 이 문서의 존재 이유 — pg-service listener TX 에서 벤더 호출을 분리하며 신설된 구조 | `docs/archive/pg-confirm-listener-split/COMPLETION-BRIEFING.md`(archive README 요약: "pg-service Kafka listener TX 에서 벤더 호출 분리 — `PgInboxPendingService`... `PgInboxChannel`... `PgInboxImmediateWorker`... 신규", 2026-05-09) | Task 14 에서 "왜 이 구조가 필요했는가"(listener TX 안에서 벤더를 부르면 인바운드 처리량이 벤더 latency 에 묶이는 문제를 실측 후 도입) 도입부 서사로 반영 | — |

#### 4.4.3 `pg-confirm-flow.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L327-331 "결과 메시지 종류" 표 (`FCG APPROVED`/`FCG FAILED`/`FCG 미확정`을 다른 확정 트리거와 나란히 배치) vs L339-351 "## 향후 확장 — 최종 확정 게이트"(FCG 를 "검토 중"으로 서술) | 같은 페이지 안에서 FCG 를 "현재 발행 트리거 중 하나"(표)와 "아직 없는 후속 계획"(향후 확장 절)으로 **모순 서술**. 실제로는 `PgFinalConfirmationGate` 클래스가 `@Service`+`@Transactional`으로 완전 구현되고 전용 단위테스트(`PgFinalConfirmationGateTest.java`)까지 있으나 **프로덕션 호출처가 0**(반쯤 지어진 미연결 상태) — "검토 중"(아직 설계도 안 됨)도 정확한 표현은 아님 | `PgFinalConfirmationGate.java:31-40`(Javadoc "재시도 루프가 소진된 뒤 벤더 getStatus 를 단 1회 호출해 최종 상태를 확정") 전체 구현 확인, `grep -rl "PgFinalConfirmationGate" pg-service/src` = 정의 파일 + 테스트 파일 + `PgStatusLookupPort.java`(의존 선언)뿐 — 호출자 0건 | 표에서 FCG 관련 3행 제거(또는 "미연결" 각주), "향후 확장" 절을 "구현 완료·미연결(dead code, 코드 확인 필요 항목)"로 정정 — README/PAYMENT-FLOW 의 동일 클래스 판정(§4.3.1)과 결론 통일 | **S1+S2** (신규 발견 — RD/RETRYING/FCG 클러스터의 pg-service 측 위치) |
| 나머지 전체 (한눈에 보는 흐름 / Inbox 상태 머신 / TX_A·TX_B 분리 / 좀비 회수 60s / 재시도 백오프표 2s·6s·18s·attempt<4 / DLQ 시퀀스 / 중복 승인 보정 5경로) | 소스 대조 결과 전건 일치 | `application.yml:88-104`, `RetryPolicy.java:17-24`(공식 `baseDelayMs × 2^retryCount`, attempt 2→2s/attempt 3→6s/attempt 4→18s 계산 일치 — 단 jitter ±25% 는 코드 상수와 별도 재확인 필요, Task 14 소스 확인 권고), `PgInboxImmediateWorker`/`PgOutboxImmediateWorker` 클래스 구조 | 변경 불요(보존, jitter 수치만 Task 14 재확인) | — |
| 서사 후보(도입 동기형) | outbox-channel-dispatch.md 와 동일 뿌리 — listener/워커/릴레이 3단 분리 자체가 pg-confirm-listener-split 산출물 | `docs/archive/pg-confirm-listener-split/COMPLETION-BRIEFING.md`(2026-05-09) | Task 14 에서 "핵심 설계 — PG 호출을 listener thread 에서 분리" 절 도입부에 왜 분리했는지(리스너가 벤더까지 부르면 파티션 리밸런스 위험) 배경 1~2문장 추가 | — |

#### 4.4.4 `async-outbox.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 배너(L3-9) | "Phase 5 시점 — 이후 변화: 본문은 모놀리스 시점 `PaymentConfirmChannel`... 패턴을 다룬다. MSA 분리 후... in-memory channel 패턴은 pg-service 의 `PgOutboxChannel` 에만 유지된다" | `payment-service/src/main` 전체에 `PaymentConfirmChannel`/`OutboxImmediateWorker`(300 VT 채널 버전) grep 0 — 클래스 완전 삭제 확인. 현재 payment-service 는 `@TransactionalEventListener(AFTER_COMMIT)` → `OutboxRelayService.relay` 직접 호출(채널 없음, outbox-pattern.md 패턴 A) | 배너 정확 — 변경 불요 | — (배너는 정합) |
| L332-354 "채널/Worker 설정 요약 → 기본 설정 (`application.yml`)" YAML 블록의 `scheduler.outbox-worker.batch-size: 100` | `outbox.channel.*`(capacity 5000/worker-count 300) 는 삭제된 `PaymentConfirmChannel` 소속이라 배너대로 역사 기록이지만, 바로 아래 `scheduler.outbox-worker.*` 는 **현재도 살아있는 `OutboxWorker`** 의 설정이다. 이 값(batch-size 100)은 default profile 이 아니라 **benchmark profile 전용 값**이고 default profile 실제값은 50 — §0.3 층위 규칙 위반(같은 YAML 블록에 역사값과 현재값이 섞여 있어 더 오독 위험 큼) | `payment-service/src/main/resources/application.yml:148`(`batch-size: 50`, default) vs `application-benchmark.yml:24`(`${SCHEDULER_OUTBOX_WORKER_BATCH_SIZE:100}`, benchmark profile) | YAML 블록 주석에 "이 값들은 최종 벤치마크 프로파일 기준(§ 참고) — default 프로파일은 batch-size 50" 명시, 또는 `scheduler.outbox-worker.*` 부분을 "현재도 유효(단, 이 표는 벤치마크 값)"로 별도 각주 | **S1+S2** (신규 발견 — `parallel-enabled` 와 같은 축의 층위 위반) |
| "주요 클래스 역할 정리"(L369) `OutboxProcessingService`... `RecoveryDecision` 분기 / "관련 문서"(L406) "`RecoveryDecision` 복구 결정 값 객체" | 배너가 채널 메커니즘의 역사성은 밝히지만, 이 두 언급은 `RecoveryDecision`/`OutboxProcessingService` 자체가 지금도 유효한 개념인 것처럼(전방 링크 설명 문구로) 읽힘 — 실제로는 RD/RETRYING/FCG 클러스터로 완전 대체됨(4.4.9 참고) | RD/RETRYING/FCG 클러스터 근거(§4.4 도입부) | "관련 문서" 링크 설명에서 "`RecoveryDecision`" 을 "(모놀리스 시점, 현재는 EOS 컨슈머 모델로 대체 — [결제 상태 관리](state-management) 참고)"로 정정 | **S2** (전방 참조 오도, RD 클러스터 파생) |
| 나머지(Round1→Round2 채널 진화 서사, 벤치마크 79.8 TPS 수치, IN_FLIGHT 5분 타임아웃) | 이미 "@Async에서 Channel로의 진화" 절이 자체 서사 구조(문제→해결) 를 갖춤. 벤치마크 수치는 이 페이지 자체가 실측 기록이라 소스 대조 대상 아님(역사 기록으로 보존) | — | 변경 불요(보존) | — |
| 서사 후보(말미 회고형) | 이 페이지가 서술하는 전체 채널 아키텍처(PaymentConfirmChannel + OutboxImmediateWorker 300 VT)가 MSA 전환 시 왜 사라졌는지 — 결제 확인 자체가 payment→pg Kafka 비동기 호출로 바뀌며 "즉시 PG 호출"의 필요성이 없어짐 | `docs/archive/msa-transition/COMPLETION-BRIEFING.md`(archive README: "MSA 전환 — 모놀리스 → 4서비스 분해... Kafka 양방향 confirm 왕복(ADR-30)", 2026-04-24) | Task 14 말미에 "왜 사라졌는가" 1~2문단 추가 — 이미 배너가 사실은 담고 있으니 서사는 "그 결정의 배경"만 보강 | — |

#### 4.4.5 `tx-scope.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 전체 | Phase 2 배너가 "현재 시스템에서는 HTTP 스레드가 PG API를 직접 호출하지 않으며, payment-service 의 Outbox 가 Kafka 토픽으로 발행 / pg-service 가 외부 PG 호출을 격리 처리한다"로 정확히 현재 구조를 요약 — 본문(TX1/TX2 분리, 커넥션 점유 비교, 보상 TX 필요성)은 명시적으로 과거 모놀리스 설계로만 서술돼 소스 대조 대상 아님(역사 기록) | 배너 자체가 `outbox-pattern`/`message-delivery-and-dedupe`/`architecture` 링크로 현재 구조를 정확히 위임 | 변경 불요(보존) — 이 문서가 12페이지 중 배너 설계의 모범 사례 | — |
| 서사 후보 | 근거 있는 신규 서사 후보 없음 — 이 페이지의 "보상 TX 필요성" 결론은 이미 그 자체로 역사 서술(문제→분리→대가)이며, PITFALLS/archive 에 이 페이지 고유의 "이후 이야기"가 따로 없음(트랜잭션 분리 원칙은 EOS 전환까지 그대로 이어져 사실상 변화가 없었음, tx-scope.md 자체가 그렇게 서술) | — | 강제 없음 | — |

> **[Task 13 반영, 2026-07-03]** 4.4.1~4.4.5 (outbox-pattern / outbox-channel-dispatch / pg-confirm-flow / async-outbox / tx-scope) 위키 로컬 파일 전건 종결 — 파일 수정까지만, 위키 커밋은 사용자.
> - **`outbox-pattern.md`**: 빈 헤더 "## 표기 규칙" 삭제(S4). `FAILED` dead-terminal 각주를 state diagram 엣지("보상 경로 진입 (현재 도달 코드 경로 없음)")와 상태표 양쪽에 반영(S1). topic 문체 기준 예문("가장 정밀한 모델이다." + 핵심 동작 3불릿)을 topic 문서의 계산된 "후" 텍스트로 그대로 실반영(S5, 사실 정정 아님). 서사 후보 없음(보존, 지시대로 미강제).
> - **`outbox-channel-dispatch.md`**: "관련 설정 → 발행 큐" 표에 누락됐던 `pg.outbox.channel.worker-count`(값 1) 행 추가(S3). 도입 배경 서사(도입 동기형, pg-confirm-listener-split 2026-05-09) 섹션 신설. DLQ 임계 모호성(L139, S2 후보)은 이 태스크에서 미해결 — 진단 리포트가 명시한 대로 소스 재확인 전이라 다음 위키 배치에서 판정.
> - **`pg-confirm-flow.md`**: "결과 메시지 종류" 표에서 FCG 관련 3개 셀("FCG APPROVED"/"FCG FAILED"/"FCG 미확정") 제거, "향후 확장 — 최종 확정 게이트" 절을 "구현됐으나 미연결 — 최종 확정 게이트(FCG)"로 재작성해 표와의 모순 해소(S1+S2) — `docs/context/PAYMENT-FLOW.md` §4.9 판정과 결론 통일. 도입 배경 서사 문장 1개 추가(pg-confirm-listener-split). jitter ±25% 재확인(별途 미확정 항목)은 이 태스크 범위 밖으로 보존.
> - **`async-outbox.md`**: "채널/Worker 설정 요약" YAML 블록에 default/benchmark 프로파일 층위 구분 문단 + 인라인 주석 추가(S1+S2, `parallel-enabled` 와 동일 축의 층위 위반 정정). "주요 클래스 역할 정리" 표 + "관련 문서" 링크 설명 2곳의 `RecoveryDecision` 전방 참조를 "(모놀리스 시점, 현재는 EOS 컨슈머 모델로 대체)"로 정정(S2). 말미 회고형 서사 섹션 "왜 이 구조가 사라졌는가" 신설(msa-transition, 2026-04-24).
> - **`tx-scope.md`**: 재검증 결과 변경 없음(보존) — 배너·본문 전건 정합, 서사 후보 없음(근거 부족, 지시대로 미강제).
> - **부수 발견(범위 밖, 코드 변경 없음)**: 세션 시작 시점에 `outbox-channel-dispatch.md`(폴링 워커 traceparent 정책 절)·`pg-confirm-flow.md`(폴링 traceparent 문장)·`trace-propagation.md`(stored_traceparent RDB 복원 절)에 이 태스크가 작성하지 않은 uncommitted 변경이 이미 존재했다(`pg_inbox.stored_traceparent` 컬럼 기반 trace 복원 서술 — 내용 자체는 서로 정합돼 보이나 DIAGNOSIS §4.4 항목에 없어 이번 태스크의 소스 재검증 대상이 아니었음). 건드리지 않고 보존 — 출처·사실 여부 확인은 후속(Task 17 trace-propagation.md 배치 또는 사용자 확인) 필요.

#### 4.4.6 `message-delivery-and-dedupe.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L217-230 "DLQ — 격리된 메시지 처리" 표, `payment.events.confirmed.dlq` 행 | "`DefaultErrorHandler` 의 retry 한도 초과 시 자동 격리"만 격리 조건으로 서술 — 실제로는 이와 **별개인 두 번째 격리 경로**가 있다: EOS `commitTransaction()` 반복 실패는 `DefaultErrorHandler` 가 아니라 `KafkaConsumerConfig` 에 명시 연결된 `AfterRollbackProcessor`(독립 backoff, 기본 1000ms×5)를 거쳐 같은 DLQ 로 격리된다(DLQ-REACHABILITY Track E, 이 페이지 작성일 2026-05-17 이후 도입) | `KafkaConsumerConfig.java:47-54,92-112`(Javadoc "afterRollbackProcessor 를 명시 설정하지 않으면 EOS commitTransaction() 반복 실패는... DLQ 를 거치지 않고 조용히 skip", `buildAfterRollbackProcessor` 구현) | 표에 두 번째 행 추가: "EOS `commitTransaction` 반복 실패 \| `AfterRollbackProcessor`(독립 backoff) 소진 시 자동 격리" | **S1** (신규 발견 — 이 페이지가 다루는 정확히 그 주제의 최근 확장 누락) |
| 나머지 (세 가지 전달 보장 모델, 발행/소비 측 다이어그램, 3서비스 dedupe 패턴 비교표, EOS 시퀀스, self-loop attempt=4) | 전건 소스 대조 일치 — `EventDedupeStore` 언급 2건 모두 pg-service 소속으로 정확히 한정(payment 측 폐기와 무관, payment 는 RDB `payment_event_dedupe` 로 별도 서술) | F1/F12(EOS qualifier·AfterRollbackProcessor 연결), `RetryPolicy.java:43`(MAX_ATTEMPTS=4) | 변경 불요(보존) | — |
| 서사 후보(말미 회고형) | "처리 실패와 DLT" 절 말미에 DLQ-REACHABILITY 가 발견한 갭(컨테이너 디폴트 `AfterRollbackProcessor` 는 DLQ 를 거치지 않고 조용히 스킵되던 문제) 추가 | `docs/archive/dlq-reachability/COMPLETION-BRIEFING.md`(archive README: "Track E(payment): EOS `commitTransaction` 반복 실패가 컨테이너 디폴트 AfterRollbackProcessor(9회·DLQ 미진입·단순 스킵)로 빠지던 것을... 명시 연결", 2026-06-25) | Task 14 에서 위 S1 정정과 함께 "왜 두 경로가 필요했는가" 1문단 추가 | — |

#### 4.4.7 `idempotency.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 전체 | Phase 4 배너 "MSA 분리 후 어댑터를 Redis 분산 store로 교체했으나, 포트 계약과 원자성 원칙은 그대로 유지된다" — 검증 결과 정확: `IdempotencyStore.getOrCreate` 포트 시그니처 현재도 동일, `IdempotencyStoreImpl`(Caffeine)·`IdempotencyStoreRedisAdapter` 양쪽 다 존재 | `payment-service/.../application/port/out/IdempotencyStore.java`(`getOrCreate` 시그니처 유지), `infrastructure/idempotency/{IdempotencyStoreImpl,IdempotencyStoreRedisAdapter}.java` 파일 존재 확인 | 변경 불요(보존) | — |
| 서사 후보 | 근거 있는 신규 후보 없음 — "초기 구현의 문제"(TOCTOU) 절 자체가 이미 checkout-idempotency 토픽(2026-03-22)의 서사를 담고 있고, Redis 전환의 세부 이력은 msa-transition 토픽 요약에 이 페이지 고유의 디테일(왜 Caffeine→Redis 인지)이 없어 창작 없이 보강할 근거 부족 | — | 강제 없음(배너 문구가 이미 핵심을 담음) | — |

#### 4.4.8 `compensation-tx.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 배너(L5-10) "MSA 분리 후 재고 복구는 product-service 호출 (cross-service Kafka 이벤트) 로 진화했다" | **배너 자체가 오류**. 실제로는 재고 보상이 payment-service 안에서 완결되는 **Redis Lua atomic 연산**(`StockCacheRedisAdapter.compensateAtomic`)이며, product-service 호출도 Kafka 이벤트 발행도 없다 — FAILED/QUARANTINED 시엔 stock-committed 자체가 발행되지 않는다("redis 보상만") | `payment-service/.../infrastructure/cache/StockCacheRedisAdapter.java` 존재(HTTP/Feign/Kafka 호출 없음), `docs/context/CONFIRM-FLOW.md:250`("APPROVED 결과에서만 발행됨 — FAILED/QUARANTINED 시 stock 발행 X (redis 보상만)"), `docs/context/ARCHITECTURE.md:149`(`compensateAtomic` Lua 1회 호출 서술) | 배너를 "재고 복구는 (product-service 호출 아니라) payment-service 내부 Redis Lua atomic 보상(`compensateAtomic`)으로 진화 — 대상은 여전히 payment 소유 redis-stock 캐시"로 전면 수정 | **S1 critical** (배너 레벨 사실 오류, 신규 발견) |
| 본문 전체(L83-244: "PaymentOutbox+복구 사이클 기반 보상 TX", "재고 복구 가드"(TX 재조회로 판정), "격리 전 최종 확인(FCG)과 보상 TX의 관계", "이중 장애 시나리오"의 `RETRY_LATER`/`FCG` 언급) | RD/RETRYING/FCG 클러스터 — `RecoveryDecision`(`COMPLETE_FAILURE` 결정) 이 보상 TX 트리거라고 서술하나 그 클래스가 삭제됐고, "TX 시작 시점에 PaymentEvent를 DB에서 다시 조회하여 비종결일 때만 재고를 복구"하는 가드도 현재 코드엔 없음(`compensateAtomic` 먼저 호출, PaymentEvent 재조회 가드 없음 — 대신 Lua 쪽 `compensation:done:{orderId}` SETNX 멱등 토큰이 이중 복구를 막음, 트리거 축 자체가 "PaymentEvent 재조회 상태 판정" 에서 "Lua 멱등 토큰"으로 이동) | `PaymentConfirmResultUseCase.java:280-303`(handleFailed/handleQuarantined, 가드 없는 직접 호출) — RD/RETRYING/FCG 클러스터 근거 전체 적용 | 배너 확장(제품 stock 대상뿐 아니라 가드 메커니즘 자체가 재고 복구 가드→Lua 멱등 토큰으로 전면 대체됐음을 명시) + 본문을 stock-cache-recovery.md 기준(현재 정합 확인된 페이지)으로 재작성 — "결정 값 객체"·"FCG" 관련 섹션은 역사 기록으로 명확히 격하하거나 삭제 | **S1 critical** (RD/RETRYING/FCG 클러스터의 가장 큰 반영 지점) |
| 서사 후보(말미 회고형) | Lua atomic dedup token 도입(stock-compensation-recovery) + 실패 보상 가드 삭제(stock-compensation-other-paths) 두 토픽이 이 페이지가 서술하는 "재고 복구 가드"를 완전히 대체한 이력 | `docs/archive/stock-compensation-recovery/COMPLETION-BRIEFING.md`(archive README: "결제 결과 보상 silent loss 회복 layer — Lua atomic dedup token... 도입", 2026-05-08), `docs/archive/stock-compensation-other-paths/COMPLETION-BRIEFING.md`(archive README: "`canCompensateStock` 가드... 동반 제거", 2026-06-21) | Task 14 에서 배너/본문 정정과 함께 "왜 PaymentEvent 재조회 가드가 Lua 멱등 토큰으로 이동했는가"(동시성 경합에서 RDB 재조회보다 원자적 Redis 토큰이 더 강한 보장) 서사 반영 | — |

#### 4.4.9 `stock-cache-recovery.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 전체 | "문제 배경 - 초기 구현"(try/catch 삼킴, `stockCachePort.increment`)은 명시적으로 "기존 코드"로 프레이밍된 역사 기록 — 정확. "to-be 플로우"(보상 Lua 우선 호출 → RDB 실패 처리)는 **현재 코드와 정확히 일치**(`handleFailed`: `compensateAtomic` 먼저, `markPaymentAsFail` 나중) | `PaymentConfirmResultUseCase.java:280-287` 순서 일치, `KafkaErrorHandlerConfig.java:59-73`(`FixedBackOff(1000L,5)`, not-retryable 3종 `MessageConversionException`/`IllegalArgumentException`/`IllegalStateException`) 이 문서의 예시 코드와 정확히 동일 | 변경 불요(보존) — 12페이지 중 가장 최신 상태와 정합된 페이지 | — |
| "Spring Kafka 에러 핸들러 위임" 절 | `DefaultErrorHandler`+`DeadLetterPublishingRecoverer`만 서술, DLQ-REACHABILITY 가 추가한 `AfterRollbackProcessor`(EOS commitTransaction 실패 경로, 이 페이지 작성일 2026-05-31 이후 도입)는 언급 없음 — message-delivery-and-dedupe.md 와 동일 갭 | `KafkaConsumerConfig.java:92-112`(F12, `setAfterRollbackProcessor` 명시 연결) | "이 핸들러는 리스너 RuntimeException 경로 전용이며, EOS 커밋 실패는 별도 `AfterRollbackProcessor` 가 같은 DLQ recoverer 를 재사용한다" 1문단 추가 | **S3** (완전성 갭 — 이 페이지의 주제("조용한 손실 차단")와 직결돼 있어 단순 S3 치고는 비중 있음) |
| 서사 후보(말미 회고형) | 이 페이지가 확립한 "예외를 삼키지 말고 그대로 던져 Kafka 가 재배달하게 한다"는 원칙이, 한 달 뒤 완전히 다른 실패 지점(EOS 커밋 자체의 반복 실패)에도 같은 원칙으로 적용된 사례 | `docs/archive/dlq-reachability/COMPLETION-BRIEFING.md`(2026-06-25, "Track E... 컨테이너 디폴트 AfterRollbackProcessor... 로 빠지던 것을... 명시 연결") | 위 S3 정정과 함께 "이 원칙이 이후 어디에 재적용됐는가" 짧은 회고 추가 | — |

> **[Task 14 반영, 2026-07-03]** 4.4.6~4.4.9 (message-delivery-and-dedupe / idempotency / compensation-tx / stock-cache-recovery) 위키 로컬 파일 전건 종결 — 파일 수정까지만, 위키 커밋은 사용자.
> - **`message-delivery-and-dedupe.md`**: "DLQ — 격리된 메시지 처리" 표에 `payment.events.confirmed.dlq` 의 두 번째 격리 경로(EOS `commitTransaction` 반복 실패 → `AfterRollbackProcessor`, 독립 backoff 기본 1000ms×5회) 행 추가(S1) + "왜 EOS 커밋 실패에 별도 경로가 필요했는가" 서사 절 신설(DLQ-REACHABILITY, 2026-06-25). 나머지는 재검증 결과 전건 정합(보존).
> - **`idempotency.md`**: 내용 변경 없음(보존, S1 없음) — "~를 통해" 번역투 1건만 문체 교정. 서사 후보 없음(근거 부족, 미강제).
> - **`compensation-tx.md`**: 배너를 "MSA 분리 후 재고 복구는 product-service 호출(Kafka 이벤트)로 진화"(사실 오류)에서 "본문은 `RecoveryDecision`+FCG 연동으로 판정하던 Phase 5 모델을 다룬다 — EOS 전환 이후 payment-service 내부 Redis Lua atomic 보상(`compensateAtomic`)으로 완전히 대체"로 전면 수정(S1 critical). RD/RETRYING/FCG 클러스터 본문(설계 변경/보상 TX 실행 흐름/재고 복구 가드/이중 장애 시나리오/FCG 관계 섹션)은 retry-recovery.md 모범 사례 템플릿을 따라 원문 유지 + 새 "## 이 모델의 이후 변화" 절(Phase 5 요소 → 현재 대체표 + Lua 멱등 토큰 전환 서사, stock-compensation-recovery 2026-05-08 / stock-compensation-other-paths 2026-06-21)을 FCG 관계 섹션 직후에 신설해 명확히 역사로 격하(S1 critical). "관련 문서"에 [재고 캐시 보상 회복](stock-cache-recovery) 링크 추가 + state-management 링크 설명에 "(Phase 5 모델)" 명시.
> - **`stock-cache-recovery.md`**: "Spring Kafka 에러 핸들러 위임" 절에 `AfterRollbackProcessor` 가 같은 recoverer 를 재사용해 같은 DLQ 로 수렴한다는 1문단 추가(S3) + "설계 의의"에 "원칙의 재적용" 항목 5 신설(DLQ-REACHABILITY 회고). 나머지는 재검증 결과 12페이지 중 가장 최신 정합 상태 유지(보존).
> - **Task 13 이월 항목 해소**: `outbox-channel-dispatch.md` "장애/폴백 시나리오" 표의 "Kafka publish 실패 → 4회 초과 시 DLQ" 서술을 소스 재확인(`PgOutboxRelayService`/`PgOutboxPollingWorker` attempt 카운터 없음, `PgVendorCallService.handleRetry` 가 4회 한도 전담)으로 오류 확정 — "무한 재폴링, DLQ 전이 없음"으로 정정 + "PG 5xx self-retry" 행에 attempt 4 도달 시 DLQ 각주 추가 + 소속 설명 문단 신설. §4.4.2 해당 행("S2 후보")도 확정 판정으로 갱신.

#### 4.4.10 `state-management.md` — RD/RETRYING/FCG 클러스터 최대 밀집 지점

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 배너(L5-7) "RecoveryDecision + FCG (Final Confirmation Gate) + 격리 사이클은 **유지된다**. 단 PG 상태 조회 경계가... 이동했다" | **12페이지 전체에서 가장 심각한 단일 오류**. "유지된다"는 명시적 현재형 단정이나, `RecoveryDecision` 은 완전 삭제(grep 0), `RETRYING` 은 enum 에 없음(F6), FCG(`PgFinalConfirmationGate`, pg-service 소속)는 존재하되 프로덕션 호출처 0(4.4.3 과 동일 클래스) | `PaymentEventStatus.java:3-12`(8개 값, RETRYING 없음), 전체 grep `RecoveryDecision` = 0, `PgFinalConfirmationGate` 호출처 0(4.4.3) | 배너를 "RecoveryDecision + RETRYING + (payment 측) FCG 연동은 이후 완전히 대체됨 — 현재는 EOS 컨슈머 모델(READY/IN_PROGRESS 만 진입 허용, PG 결과 수신 시 즉시 종결)로 전환. 상세는 [CONFIRM-FLOW](../../docs/context/CONFIRM-FLOW.md) 참고"로 전면 재작성 | **S1 critical** |
| "## PaymentEvent 상태 머신"(L33-99) 전체 — 9상태 정의·전환 다이어그램·가드표 | `RETRYING` 을 정식 상태로 전개(상태표 L43, 전환 다이어그램 L67/70/72-75, 가드표 L94-99) — 실제 8상태(`RETRYING` 없음) | F6 (`PaymentEventStatus.java:3-12`), `canApplyConfirmResult()`(`:41-46`) | 상태 정의·다이어그램·가드표를 8상태로 전면 재작성 — READY→IN_PROGRESS→{DONE,FAILED,QUARANTINED,CANCELED,PARTIAL_CANCELED,EXPIRED} 로 단순화(RETRYING 관련 전이 전부 삭제) | **S1 critical** |
| "## RecoveryDecision — 복구 결정 값 객체"(L179-208) 섹션 전체 | 6분기(`COMPLETE_SUCCESS`/`COMPLETE_FAILURE`/`ATTEMPT_CONFIRM`/`RETRY_LATER`/`QUARANTINE`/`REJECT_REENTRY`) 값 객체가 지금도 존재하는 것처럼 전체 섹션 서술 | 전체 grep `RecoveryDecision` = 0 | 섹션 전체 삭제 또는 "(폐기, PAYMENT-EOS-TRANSITION 이전 모델)" 역사 섹션으로 격하 | **S1 critical** |
| "## 재고 복구 가드"(L211-229) | "TX 시작 시점에 PaymentEvent를 DB에서 다시 조회하여, 비종결일 때만 재고를 복구" — 현재 `handleFailed`/`handleQuarantined` 에 이 재조회 가드 없음(compensation-tx.md 4.4.8 과 동일 축) | `PaymentConfirmResultUseCase.java:280-303` | compensation-tx.md 정정과 동일 방향(Lua 멱등 토큰으로 대체됐음을 명시) | **S1 critical** (compensation-tx.md 와 중복 클러스터) |
| "## 격리 전 최종 확인"(L232-246), "## 복구 사이클 전체 플로우"(L271-308) | FCG·`RecoveryDecision` 6분기 플로우차트 전체가 삭제된 아키텍처 | 상동 | 섹션 전체 역사 격하 또는 삭제 — 현재 실제 흐름(EOS 컨슈머 진입 가드 → handleApproved/handleFailed/handleQuarantined 3분기, PG 자체 재시도는 pg-service self-loop 로 이관)으로 대체 서술 | **S1 critical** |
| "## 복구 스케줄러 구성"(L250-267) `PaymentScheduler` 5분 주기 | 만료 스케줄러(`PaymentExpirationServiceImpl`?) 설명은 현재도 별도 개념으로 유효할 가능성 — 이름(`PaymentScheduler`)이 현재 클래스명과 일치하는지만 Task 15 재확인 필요(이번 태스크 범위에서 클래스명 직접 대조는 미수행) | 미확인(Task 15 소스 재확인 권고) | Task 15 착수 시 클래스명 소스 대조 후 정정 | **S2 후보** (판정 보류) |
| "## RetryPolicy — 백오프 전략"(L312-354) | `payment.retry.max-attempts:5`/`backoff-type:FIXED`/`base-delay-ms:5000`/`max-delay-ms:60000` — 이 설정 자체는 outbox-pattern.md/async-outbox.md 서술과 동일 축(payment 측 발행 재시도, F4)이라 **outbox 재시도 정책과는 별개로 여전히 존재**하는 설정일 가능성 있음(코드 fallback: `RetryPolicyProperties`) — 다만 이 섹션이 속한 상위 맥락("RecoveryDecision 의 RETRY_LATER 결정에 연동")이 죽어 있어, 이 설정이 지금 실제로 무엇에 쓰이는지(outbox 발행 재시도인지 다른 용도인지) Task 15 소스 재확인 필요 | 미확인 — `RetryPolicyProperties`/`RetryPolicy` 클래스가 payment_outbox 재시도(F4, `PaymentOutbox.incrementRetryCount`, 4.4.1 에서 확인)와 같은 클래스인지 다른 인스턴스인지 재확인 필요 | Task 15 착수 시 `RetryPolicyProperties` 빈 배선 대조 — outbox-pattern.md 의 `RetryPolicy`(4.4.1, `PaymentOutbox.incrementRetryCount` 가 사용) 와 동일 설정일 가능성이 높아 보이나 확정은 소스 재확인 후 | **S2 후보** (판정 보류, 두 문서 간 설정 축 통합 여부) |
| 서사 후보(말미 회고형, 최우선 권고) | 이 페이지 전체를 "Phase 5 시점 설계와 그 이후" 역사 문서로 재구성할 만큼 이력이 풍부함: RETRYING 최초 도입(payment-retry-state) → RecoveryDecision/FCG 도입(payment-double-fault-recovery) → EOS 전환으로 PG 폴링 복구 사이클 자체가 대체(payment-eos-transition) → RETRYING enum 제거(cleanup-batch-e) → 보상 가드 제거(stock-compensation-other-paths) | `docs/archive/payment-retry-state/COMPLETION-BRIEFING.md`(2026-04-07), `docs/archive/payment-double-fault-recovery/COMPLETION-BRIEFING.md`(2026-04-10), `docs/archive/payment-eos-transition/COMPLETION-BRIEFING.md`(2026-05-17), `docs/archive/cleanup-batch-e/COMPLETION-BRIEFING.md`(2026-06-21), `docs/archive/stock-compensation-other-paths/COMPLETION-BRIEFING.md`(2026-06-21) — 5개 토픽 전건 archive README 요약으로 확인 | Task 15 최우선 — retry-recovery.md 가 이미 보여준 "이 모델의 한계 → Phase 5 개선" 패턴을 이 페이지에도 적용해, 본문을 "Phase 5 시점(역사)" 로 전면 격하하고 새 "현재 모델" 섹션(EOS 컨슈머 8상태 + Lua 보상)을 신설하는 대수술 권고 | — |

#### 4.4.11 `retry-recovery.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 전체 | Phase 2 배너("이 문서는 Phase 2에서 최초 도입한 복구 모델을 다룬다") + "## 이 모델의 한계"(L110-123, "Phase 5에서 개선되었다") — **12페이지 중 배너·역사 프레이밍이 가장 모범적인 페이지**. 자체 서술은 전건 정합(UNKNOWN 상태·RETRYABLE_LIMIT=5 하드코딩 등은 전부 "과거"로 명시) | 문서 자체 구조 — RD/RETRYING/FCG 클러스터 근거와 대조해도 이 페이지는 "그 모델이 있었다"고만 말하지 "지금 있다"고 말하지 않음 | 변경 불요(보존) | — |
| "관련 위키"(L127-131) "[결제 상태 관리](state-management) — Phase 5 이후 상태 머신, RecoveryDecision, 재고 복구 가드" | 링크 대상(state-management.md) 자체가 4.4.10 에서 확정된 대로 stale — 이 페이지는 잘못이 없으나 참조 대상이 고쳐지기 전까지는 독자를 stale 페이지로 안내 | 4.4.10 근거 재사용 | state-management.md 정정(Task 15) 완료 후 이 링크 설명 문구도 "현재 EOS 모델"로 동반 갱신(선택, state-management.md 수정과 함께라면 자동 정합) | **S2 파생** (원인은 다른 페이지, 독립 수정 불요) |
| 서사 후보(말미 회고형, 선택) | "이 모델의 한계" 표가 Phase 5 개선까지만 그리고 멈춤 — retry_count 관련 死 metric 정리(retry-metric-cleanup)까지 이어지는 후일담을 짧게 추가할 여지 | `docs/archive/retry-metric-cleanup/COMPLETION-BRIEFING.md`(archive README: "재시도 주도권이 pg-service self-loop로 이전되며... `payment_event.retry_count` 를 증가시키는 경로가 사라져 항상 0 인 死 값이 됨"... 제거, 2026-06-22) | Task 15 선택 사항 — "한계" 표 아래에 "이후 재시도 주도권 자체가 pg-service self-loop 로 이관되며 payment 측 retry_count 필드도 정리됨" 1줄 추가 가능(강제 아님) | — |

#### 4.4.12 `pg-strategy.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 배너(L3-7) "모놀리스 시점 — 이후 변화: 본문에 등장하는 도식은 모놀리스 시점 구조다. 호출 경계도 in-process → Kafka 양방향 confirm 으로 이동" | 배너가 "호출 경계 이동"만 언급해 **점진적 변화**로 오독될 수 있으나, 실제로는 본문이 나열하는 구체 클래스(`PaymentGatewayFactory`/`InternalPaymentGatewayAdapter`/`PaymentGatewayPort`/`TossGatewayInternalReceiver`/`TossPaymentGatewayStrategy` 등 `payment`+`paymentgateway` 모듈 전체)가 payment-service 에서 **완전히 사라지고**, pg-service 안에 다른 이름(`PgConfirmStrategySelector`/`PgStatusLookupStrategySelector`/`infrastructure.gateway.{toss,nicepay,fake}`)으로 재구성됐다 — "이동"이라기보다 "재작성" | `grep -rl "class PaymentGatewayFactory\|class TossPaymentGatewayStrategy\|class InternalPaymentGatewayAdapter\|interface PaymentGatewayPort" payment-service/src/main` = 0, `grep -rl "class.*GatewayStrategy" pg-service/src/main` = `PgConfirmStrategySelector.java`/`PgStatusLookupStrategySelector.java`/`infrastructure/gateway/{toss,nicepay,fake}/*.java` | 배너를 "본문의 클래스명·모듈 경계(`payment`/`paymentgateway`)는 모놀리스 전용이며 현재 payment-service 에는 존재하지 않는다. 동등 책임은 pg-service `PgConfirmStrategySelector`/`PgStatusLookupStrategySelector` + `infrastructure.gateway.{toss,nicepay,fake}` 로 재구성됨"으로 구체화 | **S1** (배너가 변화의 정도를 과소 서술 — 신규 발견) |
| Strategy 패턴 자체(OCP, `supports()` 매칭, NicePay 2201 보상, 벤더 중립 예외 2종) | 설계 원리 자체는 pg-service 로 이관 후에도 실질적으로 이어지는 것으로 보이나, 이번 태스크에서 pg-service 측 `PgConfirmStrategySelector`/`FakePgGatewayStrategy` 구현 세부까지는 대조하지 않음(원리 차원만 확인) | 미상세 확인 — Task 17 착수 시 pg-service 실제 selector 로직과 1:1 대조 권고 | Task 17 재확인 후 로직 설명 자체는 재사용하되 클래스명·패키지 경로 표만 pg-service 기준으로 전면 교체 | **S2 후보** (세부 로직 재확인 보류) |
| 서사 후보(말미 회고형) | Toss/NicePay Strategy 패턴 최초 도입(nicepay-pg-strategy) → MSA 전환으로 pg-service 이관(msa-transition) 두 토픽이 이 페이지의 "이후 이야기" | `docs/archive/nicepay-pg-strategy/COMPLETION-BRIEFING.md`(archive README: "NicePay PG 전략 추가 — 멀티 PG 연동 (Strategy 패턴)... 예외 벤더 중립 rename", 2026-04-14), `docs/archive/msa-transition/COMPLETION-BRIEFING.md`(2026-04-24) | Task 17 에서 배너 확장과 함께 "왜 이 패턴이 pg-service 로 그대로 이식됐는가"(Strategy 추상화 덕에 모듈 이관이 클래스 재배치 수준으로 끝남) 서사 반영 | — |

> **[Task 15 반영, 2026-07-03]** 4.4.10~4.4.12 (state-management / retry-recovery / pg-strategy) + §4.5.6(scenario-test)·§4.5.7(cross-validation) 위키 로컬 파일 전건 종결 — 파일 수정까지만, 위키 커밋은 사용자.
> - **`state-management.md`**: 배너를 "RecoveryDecision + RETRYING + FCG 연동은 EOS 컨슈머 모델로 전면 대체됨"으로 재작성(S1 critical). "PaymentEvent 상태 머신" 절을 8상태(`RETRYING` 제거)로 전면 재작성 — 상태 정의·전환 다이어그램·가드표 전건 소스 재확인(`PaymentEventStatus.java`/`PaymentEvent.java` 도메인 가드 메서드 6종). "RecoveryDecision"·"격리 전 최종 확인"·"복구 사이클 전체 플로우" 3섹션을 "Phase 5 모델(폐기)" 단일 섹션으로 병합해 역사 기록으로 명시 격하(S1 critical). 새 "현재 모델 — EOS 컨슈머" 절 신설(진입 가드 → APPROVED/FAILED/QUARANTINED 3분기 플로우차트, `PaymentReconciler`, FCG 미연결 상태 명시) + "생성 시점의 동기화, 이후의 독립 진행" 절 신설(Outbox 발행 추적과 PaymentEvent 결과 반영이 완전히 분리된 두 흐름임을 명시, 기존 "두 상태의 연동" 표가 Phase 5 시절 동일 틱 판정을 전제해 현재와 불일치하던 것을 대체). "PaymentOutbox 상태 머신" 절은 dead-terminal 각주 추가 + 다이어그램에서 도달 불가한 `IN_FLIGHT → FAILED` 전이 제거(Rule 1, `PaymentOutbox.java`에 `toFailed()` 자체가 없음을 재확인). "복구 스케줄러 구성" 표에 `PaymentReconciler`(2분 주기, IN_PROGRESS 장기 체류 복원) 신규 행 추가 + `OutboxImmediateWorker`(존재하지 않는 클래스명) → `OutboxImmediateEventHandler` 정정(S2 후보 확정) + "복구 사이클 위임" 문구를 "Kafka 발행 재시도"로 정정. "RetryPolicy" 절은 outbox-pattern.md 의 `RetryPolicy`(F4)와 동일 설정임을 확정(S2 후보 확정, `PaymentOutboxUseCase`/`PaymentOutbox.incrementRetryCount` 배선 재확인)하고 도입부 문장만 "Outbox 발행 재시도 정책"으로 재프레이밍, 수치·공식은 소스와 일치해 보존. "설계 결정 요약"을 현재 모델 기준으로 전면 교체.
> - **`retry-recovery.md`**: 배너·본문 변경 없음(보존, 진단대로 모범 사례). "이 모델의 한계" 표 아래에 retry-metric-cleanup 후일담 1줄 추가(선택 서사, 2026-06-22) + "관련 위키" state-management 링크 설명을 현재 모델 기준으로 갱신(S2 파생 해소).
> - **`scenario-test.md`**: "단위 테스트 — OutboxProcessingServiceTest" 섹션 전체(10개 시나리오 표 포함)를 "(폐기)" 격하 — `OutboxProcessingService`가 클래스·테스트 통째로 삭제됐음을 명시(S1 critical, 배너 고지 범위 밖 신규 발견 확정 반영). "테스트 계층별 Fake 교체 지점" 표의 `FakeProductRepository` 행에 "(MSA 분리 전 — 포트 폐기)" 각주 + `ProductRepository` 포트 자체가 삭제되고 HTTP Feign 으로 대체됐음을 설명하는 문단 추가(S1, `ProductFeignClient` 존재 확인). 나머지 "Fake 구현체 상세" 절(`FakeTossHttpOperator` 등)은 지시대로 Task 17 범위로 보류(S3, 미착수).
> - **`pg-strategy.md`**: 배너를 "본문 클래스 전체가 payment-service 에서 완전히 사라지고 pg-service 에 재구성됨"으로 구체화(S1, `PgConfirmStrategySelector`/`PgStatusLookupStrategySelector`/`infrastructure.gateway.{toss,nicepay,fake}` 소스 확인 반영). 본문 상세 표·시퀀스는 지시대로 Task 17 범위로 보류(S2 후보, 미착수).
> - **`cross-validation.md`**: 재검증 결과 전건 정합(보존, S1 없음) — 진단대로 변경 없음.
> - **부수 정정**: `compensation-tx.md`의 state-management 상호 링크 2건("격리 전 최종 확인"/"복구 사이클 전체 플로우" 섹션명 인용, "관련 문서" 설명)이 위 state-management.md 섹션 재구성으로 dangling 될 상황을 Rule 1 로 함께 정정(새 섹션명 "Phase 5 모델(폐기)"로 갱신, 페이지 설명을 현재 모델 기준으로 재작성).

**완료 기준 대조**: 12페이지 전부 페이지별 판정 존재(4.4.1~4.4.12, "보존" 판정도 표/문장으로 명시). 서사 후보는 9개 페이지에서 실제 archive 경로 1개 이상과 함께 제시했고(4.4.2~4.4.4, 4.4.6, 4.4.8~4.4.12), 3개 페이지(outbox-pattern/tx-scope/idempotency)는 근거 부족을 명시하고 강제하지 않음(창작 방지 원칙 준수). "Phase N 시점" 배너 정합도 12페이지 전수 판정 — 정확 2건(tx-scope/retry-recovery, 모범 사례) · 부분 정확 2건(async-outbox/idempotency, 배너는 맞으나 배너 밖 세부에 층위·전방참조 문제) · 배너 자체 오류 3건(compensation-tx L-critical/pg-strategy/state-management L-critical) · 배너 없음 5건(outbox-pattern/outbox-channel-dispatch/pg-confirm-flow/message-delivery-and-dedupe/stock-cache-recovery — 이 중 outbox-pattern 은 배너가 필요할 만큼 낡지 않음, 나머지 4건은 비교적 최근 as-built 문서라 배너 불필요가 맞는 판정).

### 4.5 Task 6 — 위키 잔여 13페이지

대상 13페이지(`architecture` / `msa-transition` / `event-driven-choreography` / `metrics` / `structured-logging` / `scenario-test` / `cross-validation` / `trace-propagation` / `ai-workflow` / `Home` / `_Sidebar` / `_Footer` / `Benchmark-Report`) 전건 통독 + F1~F28 대조 + Task 2~5 확정 클러스터(outbox REQUIRES_NEW/IN_FLIGHT stale · `PaymentOutboxStatus.FAILED` dead-terminal · RD/RETRYING/FCG 클러스터 · payment 측 `EventDedupeStore` 폐기 · Elasticsearch/Logstash→Loki/Promtail(#9) · `parallel-enabled` 층위) grep 재확인. §2 표본 #8(위키 전체 갱신 격차)은 이 13페이지에서도 실측 확인 — 마지막 실질 갱신(F28, 2026-06-12)이 `ai-workflow.md` 자신의 개편 반영 시점과 정확히 일치해 그 페이지만 최신이고, 나머지는 그 이전 스냅샷에 고정돼 있다. §2 표본 #9(Elasticsearch/Logstash)는 이 배치에서 최대 오류로 확정.

**핵심 결론**: 이 13페이지의 최대 단일 오류는 `structured-logging.md` 다 — 페이지 절반 이상(로깅 파이프라인 다이어그램, 민감정보 마스킹 섹션 전체, Logstash 연동 섹션 전체, 설계 결정 요약 다수 행)이 **완전히 삭제된 인프라·클래스**(Logstash 전송, `MaskingPatternLayout`, `TraceIdFilter`, Elasticsearch/Kibana 백엔드)를 현재형으로 서술한다. `trace-propagation.md`(같은 13페이지 안에 있음)는 정확히 같은 주제(로그↔트레이스 교차 조회)를 Promtail/Loki 기준으로 정확히 서술해 대조군이 된다 — 한 위키 안에서 같은 사실을 다르게 서술하는 극단 사례(S1+S2).

#### 4.5.1 `architecture.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L170(`payment-service 상세 구조` 트리, `RecoveryDecision.java`) | domain 패키지 트리에 `RecoveryDecision.java` 를 현재 파일처럼 나열 — 완전 삭제된 클래스(RD/RETRYING/FCG 클러스터, 4.4.10 근거) | 전체 grep `RecoveryDecision` = 0 | 트리에서 해당 줄 삭제 | **S1 critical** |
| L172(`PaymentEventStatus.java` 주석 "READY / IN_PROGRESS / **RETRYING** / DONE / ...") | enum 값 나열에 `RETRYING` 포함 — 실제 8개 값뿐(F6) | `PaymentEventStatus.java:3-12` | 주석에서 `RETRYING` 제거, 8개 값으로 정정 | **S1** (F6 확장) |
| L173(`PaymentOutboxStatus.java` 주석 "PENDING / IN_FLIGHT / DONE / FAILED") | `FAILED` 를 다른 3상태와 동등한 도달 가능 상태처럼 나열 — 도달 코드 경로 0(Task 2 §4.1.1 확정) | `PaymentOutboxUseCase.java` 전체 `toFailed`/`incrementRetryOrFail` 호출처 0 | 주석에 "(FAILED 는 현재 도달 불가)" 각주 추가 | **S1** (기존 클러스터 확장) |
| L254(핵심 설계 결정 표 "Final Confirmation Gate (FCG)" 행 — "복구 사이클 한도 소진 시 벤더 getStatus 1회 재조회 후 격리 결정") | 프로젝트 핵심 설계 결정 10건 중 하나로 FCG 를 살아있는 결정처럼 서술 — `PgFinalConfirmationGate` 는 pg-service 소속 완성 코드이나 프로덕션 호출처 0(4.4.3/4.4.10 근거 재사용) | `grep -rl "PgFinalConfirmationGate" pg-service/src` = 정의+테스트뿐, 호출자 0건 | 행을 "설계는 완료됐으나 현재 미연결(dead code) — 상세는 [상태 관리](state-management)" 각주로 수정 또는 표에서 제거 | **S1 critical** (프로젝트 대표 설계 결정 표에 위치해 파급력 큼) |
| L57-64 토폴로지 mermaid(`Pay --> RedS`) | `redis-stock` 은 payment-service 만 연결 — 정확 | `payment-service/src/main/resources/application.yml:99-101`(`stock-redis` 설정), `docker/docker-compose.apps.yml:47,57`(payment-service 만 `redis-stock` depends_on/env) | 변경 불요(보존) — msa-transition.md 와 대조군(4.5.2 참고) | — |
| 나머지(4서비스 토폴로지, hexagonal 6패키지, HTTP/Kafka 협력, 모듈 표) | 소스 대조 전건 일치 | 코드 구조 직접 확인 | 변경 불요(보존) | — |

#### 4.5.2 `msa-transition.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| L110(토폴로지 mermaid `Prod --> RedS`) | `redis-stock` 연결을 product-service 로 서술 — 실제로는 payment-service 만 연결(4.5.1 과 동일 근거, 정반대 방향으로 틀림). **같은 위키 안에서 `architecture.md` 와 정면 모순**(그쪽은 `Pay --> RedS` 로 정확) | `payment-service/src/main/resources/application.yml:99-101`, `docker/docker-compose.apps.yml:47,57` — product-service 쪽 `redis-stock` 참조 전체 grep 0 | 엣지를 `Pay --> RedS` 로 수정(architecture.md 와 통일) | **S1 + S2** (신규 발견, 문서 간 직접 모순) |
| 나머지(분리 배경, Bounded Context 표, DB per Service, Redis 두 인스턴스 서술 텍스트, Kafka/Eureka/Gateway, 클라이언트 사이드 LB 검증 결과, 분리 비용 표) | 소스 대조 전건 일치(`redis-stock` 서술은 다이어그램만 틀리고 본문 표(L197-199)는 "재고 선차감 캐시" 로만 서술해 소유 서비스 명시 없어 오류 없음) | 코드 구조 직접 확인 | 변경 불요(보존) | — |
| "후속 예정" 절(L300-302, "회복성 정책(Resilience4j CircuitBreaker, fallback) 도입 예정 — Phase 4(Toxiproxy 장애 주입 + k6 부하 시나리오 + 로컬 오토스케일러) 작업과 함께") | ALERTING-RULES-AND-FAULT-DRILL(2026-06-27)·FAULT-INJECTION-RESILIENCE(2026-06-30) 가 이미 Toxiproxy 장애 주입 드릴을 도입했으나 CircuitBreaker/fallback 자체는 여전히 미도입 — "Phase 4" 표기가 README 의 "Phase 7"과 동일 축 혼용(§2 표본 #6) | F13/F14(알람 4그룹+DependencyHealthMetrics 존재), CircuitBreaker/Resilience4j 관련 `build.gradle` 의존성 미확인(Task 9 재확인 대상) — Toxiproxy 드릴 자체는 이미 존재(`docker/docker-compose.drill.yml`) | "Toxiproxy 장애 주입" 부분은 이미 부분 완료(2026-06-27/06-30)로 갱신, CircuitBreaker/오토스케일러는 미착수로 구분 | **S3** (완료분 미반영, Phase 표기 혼용은 §2 #6 과 동일 축) |

#### 4.5.3 `event-driven-choreography.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 전체(토픽 카탈로그 5종, 3자 시퀀스 3종, self-loop, 메시지 키 전략, 순서 보장) | 소스 대조 전건 일치 — RD/RETRYING/FCG·outbox stale 클러스터 잔존 0건(grep 확인), FAILED 상태 언급 없음, 토픽명·attempt<4·파티션 키 전부 F11/F13 및 기존 확정 사실과 일치 | `PaymentTopics.java`/`PgTopics.java`류 상수, `RetryPolicy.java:42-43`(attempt 4 한도) | 변경 불요(보존) — 13페이지 중 가장 정합된 페이지 | — |

#### 4.5.4 `metrics.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 배너(L5-7) | "Micrometer + Prometheus 패턴은 유지되며... 분산 트레이스(Tempo)+로그(Loki) 통합으로 확장됐다"까지만 언급 — 이 페이지가 다루는 정확히 그 주제(운영 지표)의 최근 대형 확장인 **알람 규칙 4그룹(F13) + `DependencyHealthMetrics`(F14)** 이 완전히 누락 | F13(`observability/prometheus/rules/{coordinator,guard-skip,dlq,availability}.yml`), F14(4서비스 `DependencyHealthMetrics.java`) | 배너에 "+ 알람 규칙 4그룹(coordinator/guard-skip/dlq/availability) 평가 인프라, 4서비스 `dependency_up` 폴링 게이지 추가"를 명시 | **S1+S3** (이 페이지 주제 직결 — 완전성 갭 비중 큼) |
| "메트릭 목록" 표(L71-80) | `dependency_up{component}` 게이지(F14) 표 누락. `payment_health_max_retry_reached_total`(L77)은 코드에서 완전 삭제된 메트릭(RETRY-METRIC-CLEANUP, F9) — 현재 `PaymentHealthMetrics` 는 `stuck_in_progress` 게이지 1종만 등록 | `PaymentHealthMetrics.java` 전체 — `registerHealthGauge` 호출 1회(`stuck_in_progress`)뿐, `max_retry_reached`/retry 관련 필드·메서드 grep 0 | `payment_health_max_retry_reached_total` 행 삭제, `dependency_up{component}` 행 4서비스분 추가 | **S1** (신규 발견, F9 확장) |
| L67 "모든 메트릭 클래스는 `core.common.metrics` 패키지에 위치한다" + 표의 `TossApiMetrics` 행(L79-80) | `TossApiMetrics`는 현재 **pg-service** 소속(`pg/infrastructure/aspect/TossApiMetrics.java`)이며 payment-service `core.common.metrics` 에 없음 — PG 벤더 호출 자체가 MSA 분리로 pg-service 로 이관됐으므로(4.4.12 근거 재사용) 그 메트릭도 함께 이동 | `pg-service/src/main/java/.../infrastructure/aspect/TossApiMetrics.java` 존재, `payment-service` 쪽 grep 0 | "모든 메트릭 클래스는..." 전제문을 "payment-service 결제 도메인 메트릭은 `core.common.metrics`(아래 5종), PG 호출 메트릭은 pg-service `infrastructure/aspect`(`TossApiMetrics`)로 분리"로 정정, 표에서 `TossApiMetrics` 행을 별도 표로 분리 | **S1** (신규 발견, pg-strategy 이관 규모(4.4.12)의 메트릭 파생) |
| "비동기 플로우의 관측 맹점" mermaid(L138, "PaymentEvent → **RETRYING**") | RD/RETRYING/FCG 클러스터 재등장 — 8상태에 없음(F6) | `PaymentEventStatus.java:3-12` | `RETRYING` 분기 삭제, FAILED/QUARANTINED 로 대체 | **S1** (기존 클러스터 확장) |
| "관련 위키" 절(L221, "[구조화된 로깅](structured-logging) — LogFmt 기반 로깅 체계, **ELK 연동**") | ELK(Elasticsearch/Logstash/Kibana) 연동 서술 — §2 표본 #9 클러스터, 실제는 Loki/Promtail(F19) | F19(`STACK.md:67,70,80`), 4.5.5 구조화 로깅 판정 재사용 | 링크 설명을 "LogFmt 기반 로깅 체계, Loki/Promtail 연동"으로 정정 | **S1** (표본 #9 확장) |
| 나머지(PaymentHistory 파이프라인, PaymentTransitionMetrics, PaymentQuarantineMetrics, 어드민 페이지, Prometheus/Grafana 인프라 표, 설계 결정 요약) | 소스 대조 일치 (단, "콜 스택 기반 trigger 자동 감지"(L99, L211) 서술의 실제 클래스명 `PaymentConfirmService`/`PaymentRecoverService`는 코드베이스에 존재하지 않는 클래스명 — `PaymentStatusMetricsAspect.detectTriggerFromCallStack()` 이 문자열 포함 매칭하는 대상 자체가 실제 클래스와 어긋나는 코드측 정합성 문제로 보임, 문서가 아닌 코드 쪽 이슈일 가능성) | `PaymentStatusMetricsAspect.java:84-88`(`className.contains("PaymentConfirmService")`/`"PaymentRecoverService"`), 실제 grep 결과 두 클래스명 모두 payment-service 에 존재하지 않음(`OutboxAsyncConfirmService`/`PaymentConfirmResultUseCase` 만 존재, `PaymentRecoverService` 전체 삭제) | 문서 수정 범위 아님 — **코드 확인 필요 항목**으로 Task 8 TODOS 신규 등재 후보(trigger 자동 감지가 실제로는 항상 "auto" 폴백으로 빠지는 dead branch 일 가능성) | **S2 후보** (코드 쪽 이슈, 문서는 코드를 그대로 반영해 판정 보류) |

#### 4.5.5 `structured-logging.md` — 이 배치 최대 오류

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| "로깅 파이프라인 전체 흐름" mermaid(L29-56, `H["Logback\nLogstashTcpSocketAppender"]` → `I["Elasticsearch + Kibana"]`) | 현재 로그 파이프라인은 Console appender(stdout) → docker 로깅 드라이버(`com.hyoguoo.loki.enable` 라벨) → **Promtail** → **Loki** — Logstash/Elasticsearch 어디에도 없음 | `payment-service/src/main/resources/logback-spring.xml`(Console appender 뿐, `Logstash`/`PatternLayout` 커스텀 태그 grep 0), `docker/docker-compose.apps.yml:35`(`com.hyoguoo.loki.enable: "true"` 라벨), `docker/docker-compose.observability.yml`(`grafana/loki:3.0.0`+`grafana/promtail:3.0.0`, Elasticsearch/Logstash 이미지 grep 0) | 다이어그램 전면 재작성 — Console → docker logging driver(label) → Promtail → Loki. `trace-propagation.md` "로그 측 — Promtail + Loki" 절을 그대로 반영 대상으로 참조 | **S1 critical** (§2 표본 #9 정확 확장) |
| "민감 정보 마스킹" 섹션 전체(L256-278, `MaskingPatternLayout`) | 클래스 완전 삭제(grep 0) — logback-spring.xml 에 `<layout>`/`maskPattern` 태그 자체가 없음. **마스킹 메커니즘 자체가 현재 대체 없이 사라진 상태로 보임**(신규 발견, 코드 확인 필요) | 전체 grep `MaskingPatternLayout`/`maskPattern` = 0, `logback-spring.xml` 전체에 `PatternLayout` 커스텀 서브클래스 참조 0 | 섹션을 "(과거 구현, 현재 코드에 대응 메커니즘 없음 — 코드 확인 필요)"로 격하 + Task 8 TODOS 신규 등재 후보로 별도 표기(민감정보 마스킹 공백은 도메인 리스크일 수 있어 코드 확인 우선순위 높음) | **S1 critical** (신규 발견, 단순 문서 stale 을 넘어 잠재 회귀 후보) |
| "TraceId 전파" 섹션 전체(L228-253, `TraceIdFilter`) | 클래스 완전 삭제(grep 0) — 현재 traceId 는 OTel Micrometer Tracing 이 자동으로 MDC 에 채움(`MdcContextPropagationConfig`가 `Slf4jMdcThreadLocalAccessor` 등록), 커스텀 서블릿 필터가 UUID 를 직접 발급하는 방식이 아님 | 전체 grep `TraceIdFilter`/`UUIDProvider` = 0, `MdcContextPropagationConfig.java`(OTel Context Propagation 등록) — `trace-propagation.md:197-222` 가 정확한 현재 메커니즘을 이미 서술 | 섹션을 "OTel Micrometer Tracing 이 자동으로 MDC 에 traceId/spanId 를 채움 — 상세 메커니즘은 [trace-propagation](trace-propagation) 참고"로 축약, `TraceIdFilter` 관련 서술 삭제 | **S1 critical** (같은 위키 안에 정확한 대조군 존재) |
| "Logstash 연동 (docker 프로파일)" 섹션 전체(L282-298) | `LogstashTcpSocketAppender`/`destination: logstash:5050` — 현재 docker profile 에 Logstash 컨테이너 자체가 없음(4.5 도입부 근거 재사용) | 상동 | 섹션 전체 삭제 또는 "Promtail 라벨 기반 로그 수집"으로 전면 교체 | **S1 critical** |
| "설계 결정 요약" 표(L302-314, "Kibana 검색/집계", "Elasticsearch 인덱싱 최적화" 등 4개 행) | ELK 스택 전제로 결정 근거 서술 — 상동 | 상동 | 각 행의 "Kibana"/"Elasticsearch" 를 "Grafana(Loki 데이터소스)"로 치환 | **S1** (기존 클러스터 확장) |
| "문제 배경" 절(L16, "Kibana/Elasticsearch에서 검색/집계 불가") | 문제 제기 맥락이라 역사 서술로 볼 여지 있으나, 이 문장이 가리키는 "해결책"(바로 아래 절들)이 실제로는 ELK 가 아니라 Loki 라서 문제-해결 짝이 어긋남 | 상동 | 배너에 "해결책은 당시 ELK 기준으로 설계됐으나 현재는 Promtail/Loki 로 완전히 대체" 1줄 추가 | **S3** (배너 보강으로 해소 가능) |
| 나머지(LogFmt 클래스 구조/출력 포맷/로그레벨, LogDomain/EventType enum, `@PublishDomainEvent` AOP, 도메인 이벤트 이력 기록) | 소스 대조 일치 — `LogFmt.java`/`LogDomain.java`/`DomainEventLoggingAspect.java` 전건 존재, action 매핑도 일치 | `payment-service/src/main/java/.../core/common/log/{LogFmt,LogDomain,EventType}.java`, `core/common/aspect/DomainEventLoggingAspect.java` 파일 존재 | 변경 불요(보존) | — |
| 배너(L5-7) | "LogFmt 패턴 자체는 유지되며, 분산 트레이스+로그 교차 조회(Tempo↔Loki) 메커니즘은 trace-propagation 참고"까지는 정확하지만, 본문이 그 이후에도 여전히 ELK/masking/필터 서술을 유지해 배너의 안내가 무색해짐 | — | 배너 확장 없이 본문 3개 섹션(마스킹/TraceId/Logstash) 전면 재작성이 근본 해법(위 3건과 동일) | **S2** (배너-본문 불일치, 위 3건에 종속) |

#### 4.5.6 `scenario-test.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 배너(L5-7) | "본문에 등장하는 클래스명은 모놀리스 시점... 현재는 pg-service 의 `FakePgGatewayStrategy` 등으로 재구성됐다" — 자기인식형 배너로 상당수 클래스명 stale 을 이미 고지(모범 사례에 가까움) | `pg-service/src/main/java/.../infrastructure/gateway/fake/FakePgGatewayStrategy.java` 존재 확인 | 변경 불요, 단 아래 2건은 배너 수준을 넘는 문제라 별도 처리 | — |
| "단위 테스트 — `OutboxProcessingServiceTest`" 섹션 전체(L237-253, 10개 시나리오 행, `Fcg CallsGetStatus` 등) | 클래스명 변경이 아니라 **클래스와 테스트 자체가 완전히 삭제**됨 — `OutboxProcessingService`(복구 사이클 오케스트레이터, RecoveryDecision 6분기 소비자)가 RD/RETRYING/FCG 클러스터의 일부로 통째로 사라짐(4.4.4/4.4.10 근거 재사용). 배너의 "클래스명이 다르게 표현" 정도가 아니라 "그 테스트가 검증하던 개념 자체가 없어짐" | 전체 grep `OutboxProcessingService`(정의+테스트) = 0 | 섹션 전체를 "(폐기 — EOS 컨슈머 모델로 대체, 상세는 [상태 관리](state-management))"로 격하 또는 삭제 | **S1 critical** (배너 고지 범위를 넘는 신규 발견, RD/RETRYING/FCG 클러스터의 테스트 문서 파생) |
| "Fake 구현체 상세" 절(L85-158, `FakeTossHttpOperator`/`FakeTossOperator`) + "테스트 계층별 Fake 교체 지점" 표(L74-81) | `TossOperator`/`HttpOperator`/`FakeTossHttpOperator`/`FakeTossOperator` 전부 payment-service 에서 삭제(PG 호출 로직 자체가 pg-service 로 이관, 4.4.12 근거 재사용) — 배너가 "재구성됐다"고 이미 고지했으므로 S1 은 아니나, 구체 필드/메서드 표까지 상세 서술해 "지금 이 파일을 찾으면 된다"는 오도 위험이 배너 고지보다 큼 | `grep -rl "class FakeTossHttpOperator\|class FakeTossOperator\|interface TossOperator\|interface HttpOperator" payment-service/src` = 0 | Task 17 에서 pg-service 측 대응 클래스(`FakePgGatewayStrategy` 등) 경로로 표·필드 설명 전면 교체 | **S3** (배너로 부분 고지됐으나 상세 표는 갱신 필요, S1 은 아님) |
| `FakeProductRepository`(L79 표 행) | `ProductRepository` 포트 자체가 삭제 — payment-service 는 product 조회를 HTTP Feign(`ProductFeignClient`)으로 수행(architecture.md 4.5.1 근거 재사용), repository 포트 방식이 아님 | `grep -rl "interface ProductRepository\|class FakeProductRepository" payment-service/src` = 0, `ProductFeignClient.java` 존재 | 행을 삭제하거나 "(MSA 분리 후 HTTP Feign 으로 대체, 포트 없음)"으로 각주 | **S1** (신규 발견 — 배너 고지 범위 밖, 포트 자체의 소멸) |
| 나머지(Fake vs Mock 결정 기준, Fake 배치 위치 결정, `FakePaymentEventRepository`/`FakeIdempotencyStore` — 둘 다 현존 확인, `PaymentControllerTest`/`PaymentCheckoutConcurrencyIntegrationTest` 파일 존재 확인, 설계 결정 요약) | 소스 대조 일치 | `payment-service/src/test/java/.../mock/{FakePaymentEventRepository,FakeIdempotencyStore}.java`, `presentation/{PaymentControllerTest,PaymentCheckoutConcurrencyIntegrationTest}.java` 존재 | 변경 불요(보존) | — |

#### 4.5.7 `cross-validation.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 전체 | 배너("모놀리스 시점 단일 JVM 흐름... 교차 검증 흐름 자체는 유지되나 호출 경계가 HTTP/Kafka 로 바뀜")가 정확하고, 본문이 서술하는 3가지 검증(구매자 ID/총 금액/주문번호)과 에러코드(`INVALID_USER_ID`/`INVALID_TOTAL_AMOUNT`/`INVALID_ORDER_ID`)가 현재 `PaymentEvent` 도메인 검증 로직과 정확히 일치 | `payment-service/.../domain/PaymentEvent.java:132,135,138` | 변경 불요(보존) — 13페이지 중 배너-본문 정합 모범 사례 | — |

#### 4.5.8 `trace-propagation.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 전체 | Promtail/Loki, OTel Context + MDC 이중 ThreadLocal, `ContextAwareVirtualThreadExecutors`, `MdcContextPropagationConfig`, `TraceparentExtractor`+`pg_inbox.stored_traceparent`(Flyway V4) 등 전건 소스 존재·동작 일치 | `ContextAwareVirtualThreadExecutors.java`(payment/pg 양쪽), `MdcContextPropagationConfig.java`, `TraceparentExtractor.java`, `PgInboxPollingWorker.java`, `V4__add_pg_inbox_stored_traceparent.sql:6` 전부 파일·컬럼 존재 확인 | 변경 불요(보존) — 13페이지 중 최신·최정확 페이지, `structured-logging.md`(4.5.5) 재작성의 기준 문서로 사용 | — |

#### 4.5.9 `ai-workflow.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 전체(4단계 구조, 서브에이전트 3종, 선택적 격리 원칙, 개편 이력) | 2026-06-12 워크플로우 개편(4단계/3에이전트/rounds 파일 폐지) 을 정확히 서술 — 파일 mtime(2026-06-12)이 개편 시점과 일치, `.claude/agents/{reviewer,domain-expert,implementer}.md` 3파일·`.claude/skills/workflow-{discuss,plan,execute,ship}/` 4디렉터리 구조와 정확히 대응. 에이전트별 모델은 "고지능/최고지능/중간지능"으로 추상 서술해 이후 모델 티어링 변경(project_model_tiering, 2026-07-02)에도 영향받지 않는 설계 | `.claude/agents/{reviewer,domain-expert,implementer}.md` 3파일 존재, `.claude/skills/` 디렉터리 목록(workflow/workflow-{discuss,plan,execute,ship}/_shared/review/doc-review/context-update/issue-commit-pr/wiki-access/writing) 대조 | 변경 불요(보존) — 13페이지 중 유일하게 F28 갱신 격차 예외(자기 자신이 그 개편의 산출물이므로) | — |

#### 4.5.10 `Home.md` / `_Sidebar.md` / `_Footer.md` — 링크-슬러그 정합

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| `Home.md`/`_Sidebar.md`/`_Footer.md` 전체 링크 | 세 파일이 참조하는 슬러그 전건(22개 콘텐츠 페이지 + `Home`/`Benchmark-Report`) 을 실제 파일명과 1:1 대조 — **깨진 링크 0건, 고아 페이지(링크 없는 실재 파일) 0건**. `Home.md` "개발 여정" Phase 1~6+ETC 표가 22개 콘텐츠 페이지 전부를 최소 1회 이상 참조함을 확인 | `payment-platform.wiki/*.md` 파일명 25개 전건 vs `grep -oP '\]\(\K[a-zA-Z0-9_-]+(?=\))'` 로 추출한 링크 슬러그 대조(수기 diff) | 변경 불요(보존) | — |
| `Home.md` "개발 여정" Phase 표 | ALERTING-RULES-AND-FAULT-DRILL(2026-06-27)/FAULT-INJECTION-RESILIENCE(2026-06-30) 가 도입한 알람 규칙 4그룹 + Toxiproxy 장애 주입 드릴이 Phase 1~6+ETC 어디에도 대응 페이지가 없음 — README 의 "다음 Phase 7"(§2 #6, Task 4 채록)과 같은 축의 공백 | F13/F14, `docker/docker-compose.drill.yml` 존재 | **신규 페이지 판단**: 별도 페이지 신설보다 `metrics.md`(Task 16, 4.5.4 정정과 함께) 확장을 권고 — 알람 규칙·장애 드릴은 "운영 지표" 페이지의 자연 확장이며 별도 페이지로 쪼갤 만큼 서사가 독립적이지 않음(작성은 Task 16 비범위 유지, 이 판단만 기록) | **S3** (완전성 갭, 신규 페이지 비강제) |

#### 4.5.11 `Benchmark-Report.md`

| 문서 위치 | 문제 | 소스 근거 | 수정 방향 | 심각도 |
|---|---|---|---|---|
| 배너(L3-7) | "최초 작성 2026-03-28 / 마지막 업데이트 2026-04-03", "Phase 5 시점 — 모놀리스 단일 JVM 환경 측정... MSA 분리(Phase 6) 이후 재측정은 미실시 — 별도 측정 예정"까지 명시 — topic 사전 결정("시점 기록 유지, 배너·링크만 판정")대로 이미 정확히 자기 프레이밍됨 | 문서 자체(측정치가 `FakeTossHttpOperator` 시뮬레이션 기반임을 §0.1 에서 명시, 모놀리스 시점 도구 이름과 배너의 "모놀리스 단일 JVM" 프레이밍이 일치) | 변경 불요(보존) | — |
| 문서 내부 링크 | 전부 문서 내부 앵커(TOC, `#절-이름`) 뿐 — 위키 간 크로스링크 0건 | 정규식 전수 추출 결과 외부 슬러그 링크 0건 | 변경 불요(보존) | — |

**완료 기준 대조**: 13페이지 전부 페이지별 판정 존재(4.5.1~4.5.11, `Home`/`_Sidebar`/`_Footer` 는 4.5.10 에 묶어 판정 — 세 파일이 동일한 링크-슬러그 검사 대상이라 응집). 최대 오류는 `structured-logging.md`(4.5.5, S1 critical 4건 — 파이프라인 다이어그램·마스킹 섹션·TraceId 섹션·Logstash 연동 섹션 전면 재작성 대상, Task 16 최우선). RD/RETRYING/FCG 클러스터가 `architecture.md`(설계 결정 표 포함)·`metrics.md`(관측 맹점 다이어그램)·`scenario-test.md`(`OutboxProcessingServiceTest` 섹션 전체) 3곳에 추가 확산돼 있음을 확인 — Task 5 의 `state-management.md` 대수술과 함께 Task 15/16 에서 참조 대상으로 사용. 신규 발견 중 도메인 리스크로 볼 여지가 있는 항목(구조화 로깅의 마스킹 메커니즘 공백)은 코드 확인 필요 항목으로 Task 8 TODOS 등재 후보에 추가 표기. 링크-슬러그 정합은 25페이지 전건 대조 완료 — 깨진 링크 0건, 고아 페이지 0건. 위키 신규 페이지는 강제하지 않음(metrics.md 확장으로 흡수 권고, 4.5.10).

> **[Task 16 반영, 2026-07-03]** 4.5.1(architecture)·4.5.2(msa-transition)·4.5.4(metrics)·4.5.5(structured-logging) 위키 로컬 파일 전건 종결 — 파일 수정까지만, 위키 커밋은 사용자. 4.5.3(event-driven-choreography)는 재검증 결과 변경 없음(보존).
> - **`architecture.md`**: domain 패키지 트리에서 완전 삭제된 `RecoveryDecision.java` 행 제거 + `PaymentEventStatus` enum 주석을 8종(`RETRYING` 제거)으로 정정 + `PaymentOutboxStatus` 주석에 `FAILED` dead-terminal 각주 추가(RD/RETRYING/FCG 클러스터, S1). 핵심 설계 결정 표의 "Final Confirmation Gate (FCG)" 행을 "설계 완료, 미연결(dead code) — 상세는 [상태 관리](state-management)"로 정정(S1 critical, 프로젝트 대표 설계 결정 표 위치라 파급력 큼).
> - **`msa-transition.md`**: 토폴로지 mermaid 의 `redis-stock` 연결을 `Prod --> RedS` → `Pay --> RedS` 로 정정(S1+S2, `architecture.md` 와의 정면 모순 해소 — `application.yml:99-101`/`docker-compose.apps.yml:47,57` 재확인, payment-service 만 연결). "후속 예정" 절을 "Toxiproxy 장애 주입 드릴 + 알람 규칙 4그룹은 이미 구축 완료(`metrics` 링크) / CircuitBreaker·k6 오토스케일러는 미도입"으로 완료분·미착수분 분리(S3).
> - **`event-driven-choreography.md`**: 재검증 결과 전건 정합(보존, S1 없음) — 진단대로 변경 불요, 13페이지 중 가장 정합.
> - **`metrics.md`**: 배너에 알람 규칙 4그룹 + `DependencyHealthMetrics` 확장 반영(S1+S3, 이 페이지 주제 직결 완전성 갭 해소). "메트릭 목록" 표를 payment-service 결제 도메인/의존성 가용성(4서비스 공통)/pg-service PG 벤더 호출 3분할 재구성 — 삭제된 `payment_health_max_retry_reached_total` 게이지 제거(S1, F9 확장) + `dependency_up{component}`/`dependency_health_last_poll_timestamp_seconds` 신규 행 추가(F14) + `TossApiMetrics`를 pg-service 소속으로 명시 분리(S1, pg-strategy 이관 규모(4.4.12)의 메트릭 파생). `PaymentHealthMetrics` 섹션에서 존재하지 않는 `max_retry_reached` 항목 삭제. 신규 "DependencyHealthMetrics" 절 + "알람 규칙 — Prometheus rule 평가" 절(coordinator/guard-skip/dlq/availability 4그룹, `absent(dependency_up)` 언급) 신설 — `Home.md` 신규 페이지 검토 결과(4.5.10, "신규 페이지 비강제, metrics.md 확장 권고")를 이 태스크에서 흡수. "비동기 플로우의 관측 맹점" mermaid 의 `RETRYING` 분기를 `QUARANTINED` 로 교체(RD/RETRYING/FCG 클러스터 해소). "관련 위키" structured-logging 링크 설명의 "ELK 연동"을 "Promtail/Loki 연동"으로 정정(§2 표본 #9 확장). "콜 스택 기반 trigger 자동 감지"(존재하지 않는 클래스명 매칭) 는 코드 쪽 이슈로 문서 수정 범위 아님 — Task 8 TODOS `[PAYMENT-STATUS-TRIGGER-DETECT-DEAD-BRANCH]` 로 이미 등재돼 있어 재등재 없이 보존.
> - **`structured-logging.md`**(이 배치 최대 오류, S1 critical 4건 전건 해소): 배너에 ELK→Promtail/Loki 완전 대체 + 마스킹 공백 고지 1줄씩 추가. "로깅 파이프라인 전체 흐름" 다이어그램을 Console appender → docker 로깅 드라이버(`com.hyoguoo.loki.enable` 라벨) → Promtail → Loki 로 전면 재작성(`logback-spring.xml` Console appender 전용 확인, `docker-compose.apps.yml:35` 라벨 확인). "TraceId 전파" 섹션에서 완전 삭제된 `TraceIdFilter`/`UUIDProvider` 서술을 OTel Micrometer Tracing 자동 MDC 전파(`MdcContextPropagationConfig`)로 교체 + [trace-propagation](trace-propagation) 링크(같은 위키 정확한 대조군 문서, 4.5.8 근거 재사용) — logback 의 traceId 출력 패턴 자체는 현재도 유효해 보존. "민감 정보 마스킹" 섹션은 헤더에 "(과거 구현 — 현재 대응 메커니즘 없음)" 명시 + 본문을 과거형으로 전환해 역사 기록으로 격하(`MaskingPatternLayout`/`maskPattern` 전건 grep 0 재확인) — 대체 여부는 `docs/context/TODOS.md` `[STRUCTURED-LOGGING-MASKING-GAP]` 코드 확인 필요 항목으로 이미 등재돼 있어 문서에는 사실만 반영. "Logstash 연동" 섹션을 "로그 전송 — Promtail 라벨 기반 수집"으로 전면 교체(과거 구현 요약 1문단 보존 + 현재 구현 표 신설). "설계 결정 요약" 표의 Kibana/Elasticsearch/`TraceIdFilter`/`LogstashEncoder` 관련 행을 Grafana(Loki)/OTel MDC/docker 로깅 드라이버 기준으로 정정, `MaskingPatternLayout` 행은 "(과거 구현, 현재 대응 메커니즘 없음)" 각주 추가.
> - mermaid 노드 라벨 금지 문자(신규 다이어그램 `->` ASCII 화살표만 사용) 준수 확인. `./gradlew test` 대상 아님(위키·문서 전용, 코드 무변경).

> **[Task 17 반영, 2026-07-03]** 4.5.8(trace-propagation)·4.5.9(ai-workflow)·4.5.10(Home/_Sidebar/_Footer)·4.5.11(Benchmark-Report) 전건 종결 — **위키 정정 단계(Task 13~17) 전체 완결**. 6페이지 전부 진단 판정대로 "변경 불요(보존)"이 재확인돼 실질 콘텐츠 수정 0건(리포트 항목만 반영·종결 마킹).
> - **Task 13 이월 확인 건 해소**: 세션 시작 시점부터 위키에 미커밋 상태로 존재하던 `pg_inbox.stored_traceparent` RDB 복원 서술(`trace-propagation.md`/`outbox-channel-dispatch.md`/`pg-confirm-flow.md` 3파일, Task 13 완료 결과에 "이 태스크가 작성하지 않았고 출처 확인은 후속"으로 인계된 항목)을 소스 재확인 — `TraceparentExtractor`(`pg-service/src/main/java/.../infrastructure/trace/TraceparentExtractor.java`, `extractFromCurrentContext`/`restoreContext` 존재·동작 일치) + `pg_inbox.stored_traceparent` 컬럼(`V4__add_pg_inbox_stored_traceparent.sql`, VARCHAR(64) NULL) + `PgInboxPollingWorker.processWithRestoredContext`(`findStoredTraceparent` 조회 → `restoreContext` → `Scope` 활성화, 코드 순서까지 서술과 정확히 일치) + `PgInboxPendingService.insertPendingAndPublish`(`storedTraceparent` 파라미터를 PENDING INSERT 시 저장) + `PaymentConfirmConsumer.consume`(`TraceparentExtractor.extractFromCurrentContext()` 로 소비 시점 추출 후 `storedTraceparent` 전달) 전건 소스 대조 완료 — **서술 정확, 3파일 모두 그대로 유지**(수정 불요). 출처는 이번 세션 이전 다른 작업에서 반영된 것으로 추정되나 DIAGNOSIS 항목 밖이라 작성 주체는 특정하지 않음.
> - **위키 25페이지 내부 링크 스윕**: 전 페이지 `](...)` 링크(외부 URL·문서 내부 앵커 제외) 전건 추출해 대상 파일 존재 여부 대조 — 페이지 대 페이지 링크는 전건 정상(0건 파손). `compensation-tx.md:213` 링크가 `state-management.md`(`.md` 확장자 포함, 나머지 24개 위키 간 링크는 전부 확장자 없이 슬러그만 사용)로 서술 일관성이 어긋난 것을 신규 발견 — 이 파일은 Task 14/15 종결 대상이라 Task 17 목표 6페이지 밖이지만, 링크 스윕 자체가 25페이지 전건 대상이라 확장자만 제거해 나머지 링크와 표기 통일(내용·문장 변경 없음, 1줄 서식 정정). `Benchmark-Report.md` 내부 TOC 앵커(`#0-이-보고서를-읽기-전에` 등 9개) 전건도 실제 헤더와 슬러그 대조 완료 — 깨짐 0건.
> - **6페이지 개별 재검증**: `trace-propagation.md`(stored_traceparent 절 포함 전건 소스 일치, 위 이월 건과 동일 근거) · `ai-workflow.md`(에이전트 모델 표가 "고지능/최고지능/중간지능" 추상 등급만 사용해 2026-07-02 Claude 5 모델 티어링 조정 이후에도 그대로 유효, 구체 모델명 미기재로 갱신 격차 자체가 발생하지 않는 설계 재확인) · `Home.md`/`_Sidebar.md`(22개 콘텐츠 페이지 + 자기 자신 링크 전건 슬러그 일치, Phase 1~6+ETC 표 구성 변경 불요) · `_Footer.md`(3개 링크 전건 정상) · `Benchmark-Report.md`(배너·측정 시점 프레이밍 그대로 정확, 외부 슬러그 링크 0건이라 위 페이지 재구성 영향 없음) 전건 "변경 불요" 재확인.
> - 대상 6페이지 콘텐츠 실질 변경 0건(링크 서식 정정 1건은 범위 밖 `compensation-tx.md`). `./gradlew test` 대상 아님(위키·문서 전용, 코드 무변경).

---

## 5. 완료 기준 대조

- [x] 사실 목록(§1) 전 항목 소스 파일:라인 채록 — 문서 인용 근거 0건 (F18/F26 은 문서 자체의 헤더-본문/스냅샷 불일치가 사실이라 문서를 1차 근거로 병기하되, 근거가 되는 "무엇이 바뀌었는가"는 각각 F13/F5~F17 소스로 뒷받침)
- [x] 표본 12건 리포트 수록·판정 완료 (§2)
- [x] 항목 형식에 "기본값 층위 명시" 규칙 포함 (§0.3)
- [x] 기준 예문 retry 카운트 불릿 재검증 완료 (§3.2)
- [x] Task 2 — 플로우·대장·함정 5파일 전부 페이지별 판정 존재, S1/S2 전건 소스 근거 포함 (§4.1)
- [x] Task 3 — 잔여 에이전트 문서 12파일 + smoke 5파일 전부 페이지별 판정 존재, S1/S2 전건 소스 근거 포함, 중복 서술(S4) SSOT 지정안 포함 (§4.2)
- [x] Task 4 — README + PAYMENT-FLOW-GUIDE 2파일 판정 완료 (§4.3.1~4.3.2), README 도메인 사실(S1) 항목 별도 표기(§4.3.3, ship domain-expert 대조 입력용), Phase 표기 실태 전수 채록(§4.3.4, Task 11 결정 입력)
- [x] Task 5 — 위키 도메인 코어 12페이지 전부 판정 존재(§4.4.1~4.4.12), 서사 후보마다 실제 이력 근거 경로 1개 이상 포함(3페이지는 근거 부족으로 명시 후 미강제)
- [x] Task 6 — 위키 잔여 13페이지 전부 판정 존재(§4.5.1~4.5.11, Home/_Sidebar/_Footer 는 4.5.10 에 응집), 링크-슬러그 정합 검사 포함(깨진 링크 0건)

---

## 6. doc-review 라운드 1

Task 19 최종 검증 스윕의 doc-review 4관점 검수(1R) 결과와 그 수정 1차 반영을 기록한다. 대상은 위키 11파일 — Task 13~17 이 이미 정정한 문서 중 검수 관점(기술 정확성 / 동일 파일 서사 정합 / 독자 관점)에서 잔여 오류·서술 격차가 재발견된 페이지들이다.

### 6.1 4관점 판정 요약

| 관점 | 판정 | 비고 |
|---|---|---|
| 기술 정확성(소스 대조) | FAIL → 본 수정 1차로 해소 | attempt 헤더 동행 오서술(3파일), payment_outbox PENDING 자기 전이, pg 재시도 백오프 표(2s/6s/18s 대신 실제 6s/18s/54s), PENDING→QUARANTINED 라벨 오배치, dedupe 중복 시 발행 서술 오류, pg 소비 시퀀스 구모델 잔존, `PaymentRetryAttemptedEvent`/`RETRY_ATTEMPT` 완전 삭제 클래스 서술, traceId 로그 라인·MDC 표기 불일치, Flyway 경로 오서술, redis TTL 오배치, 운영 연동(cAdvisor/scrape 주기/Grafana 자격증명/스크레이프 타깃) 노후 등 |
| 동일 파일 서사 정합 | FAIL → 본 수정 1차로 해소 | `state-management.md` 배너가 FCG(구현·미연결)를 RecoveryDecision(완전 삭제)과 뭉뚱그려 "폐기"로 서술해 본문(§FCG 절)과 모순 — 분리 기술로 정정. `structured-logging.md` 배경 4문제 중 1개(비동기 성능 저하 원인 파악)가 설계 대응 4항목 어디에도 회수되지 않던 서사 공백 해소 |
| 독자 관점(중복·밀도·용어 일관성) | FAIL → 본 수정 1차로 해소 | `pg-confirm-flow.md` 종결 상태 재수신 설명 3중 반복 → 분기 절 단일화, "폴백 폴링"/"폴링 폴백" 용어 혼용 통일, `trace-propagation.md` OTel→Tempo/MDC→Loki 대응표 3회 반복 → 최초 1회 + 참조, `message-delivery-and-dedupe.md` 도입부 인용구 중복 통합, `async-outbox.md` 단타 문장 3곳 밀도 정리 |
| 문체(AI체·평가 형용사) | 이번 라운드 대상 아님 | Task 11/18 재발 방지 게이트로 이미 커버 — 별도 신규 위반 없음 |

### 6.2 수정 1차 반영 — 위키 11파일 요약 (파일 수정까지만, 커밋은 사용자)

- **`outbox-pattern.md`**: attempt 는 relay 발행 헤더(`Map.of()`, 빈 헤더)가 아니라 `pg_inbox.attempt` 가 SoT 로 정정(2곳), `payment_outbox` 상태 다이어그램의 `PENDING → PENDING` 자기 전이 삭제(retryCount/nextRetryAt 갱신은 `IN_FLIGHT` 상태에서만 허용, `PaymentOutbox.incrementRetryCount` 가드 확인), in-flight 타임아웃 서술을 "짧은 타임아웃(수십 초)" → "기본 5분"(`OutboxWorker.java:27`, `in-flight-timeout-minutes` 코드 fallback/yml 값 모두 5)으로 정정.
- **`architecture.md`**: attempt 헤더 동행 서술 동일 정정. 디렉토리 트리 정정 — `PaymentConfirmService` 는 `presentation/port/` 소속(`application/port/in/` 아님, 실제 그 자리엔 `PaymentExpirationService`/`PaymentHistoryService`), `OutboxAsyncConfirmService` 는 `application/` 루트 소속(`application/service/` 아님), Kafka 설정은 `infrastructure/config/Kafka*Config.java` 5개 파일로 존재(`core/config/KafkaConfig.java` 부재 확인). 토폴로지 mermaid 토픽 레이블 공백 표기(`payment . commands . confirm`) 정리.
- **`pg-confirm-flow.md`**: 백오프 표를 "다음 시도 번호(2/3/4) → 기준값(2s/6s/18s)"에서 "실패한 attempt(1/2/3) → 런타임 백오프(6s/18s/54s)"로 정정(`RetryPolicy.computeBackoff(nextAttempt)` 호출부 확인, off-by-one — TODOS 신규 등재). `PENDING → QUARANTINED` 라벨을 `[*] → QUARANTINED`(보정 경로 신설)로 이동(`DuplicateApprovalHandler` 의 inbox 신설은 PENDING 우회 확인), "신규/PENDING/IN_PROGRESS 모두 PENDING INSERT" 서술을 "신규만 INSERT, PENDING/IN_PROGRESS 재수신은 채널 재적재"로 분리(`PgConfirmService.processCommand`/`handleActiveInbox` 확인). 좀비 회수 임계 근거를 "벤더 timeout 30s → 60s" 예시에서 "실측 read-timeout 10s 배수"로 정정. 종결 상태 재수신 설명 3중 반복(한눈에 흐름/단계별 정리/분기 절)을 "분기" 절 단일 본체 + 나머지 1줄 포인터로 축소, L12/L14 도입부 중복 문장 정리, "폴백 폴링"→"폴링 폴백" 용어 통일, "발행 큐(`pg_outbox`)" → "발행 대기 테이블(`pg_outbox`)"로 인메모리 채널(Ch2, 동일 명칭 "발행 큐")과 구분. pg-strategy 링크에 역사 문서 고지 추가.
- **`message-delivery-and-dedupe.md`**: "dedupe 중복 시 send 항상 진행" 서술을 "종결 가드(비종결 시 단순 skip, 종결이면 DONE+APPROVED 재배달만 재발행)" 구조로 재작성(`PaymentConfirmResultUseCase.handle:116-179` 확인). pg 소비 시퀀스를 "NONE→IN_PROGRESS 단일 TX 내 벤더 호출" 구모델에서 "listener PENDING INSERT → 워커 TX_A(CAS) → 벤더(TX 밖) → TX_B" 3단 분리로 재작성. 사전 지식 표에 EOS/DLQ 정의 추가, "DLT" 오기를 "DLQ"로 통일(정의를 최초 등장인 사전 지식 표로 이동). 도입부 인용구 2개를 1개로 통합.
- **`structured-logging.md`**: `PaymentRetryAttemptedEvent`/`"retry"` action/`RETRY_ATTEMPT` 를 파이프라인 다이어그램·action 매핑 표·이벤트 계층 classDiagram 전건에서 삭제(`PaymentHistoryEventType` 이 `PAYMENT_CREATED`/`STATUS_CHANGE` 2종뿐임을 grep 0 으로 확인), "6개 상태 변경" → "5개"(`PaymentCommandUseCase` 의 `@PublishDomainEvent(action="changed")` 메서드 5개: execute/done/fail/expire/quarantine). 배경 4문제 중 대응되지 않던 "비동기 처리 중 성능 저하 원인 파악" 항목 삭제(서사 공백 해소). traceId 표기·로그 라인 예시는 재확인 결과 이미 실제(`LogFmt.java`/logback 패턴) 기준으로 정확해 변경 없음(Task 16 반영분 유효).
- **`state-management.md`**: `PENDING → PENDING` 자기 전이 삭제(outbox-pattern 과 동일 근거). 배너의 "RecoveryDecision·RETRYING·FCG 연동·PG 폴링 복구 사이클 모두 폐기" 서술을 분리 — RecoveryDecision/RETRYING/PG 폴링 사이클은 "완전 삭제", FCG 는 "삭제 아닌 구현 완료·미연결"로 구분해 본문(§FCG 절, §210-213)과 정합.
- **`msa-transition.md`**: redis TTL 서술 재배치 — `redis-dedupe` 는 pg 메시지 dedupe 1시간(`EventDedupeStoreRedisAdapter.java:16`) + payment checkout 멱등 10초(`IdempotencyStoreRedisAdapter.java:38`, `IdempotencyProperties` 기본값 10 확인)이고, P8D(8일)는 `redis-stock` Lua 원자 연산의 중복 방지 토큰 TTL(`StockCacheRedisAdapter.java:32`)임을 명확화. Flyway 경로를 payment/pg(`db/migration`) vs product/user(`db/schema`+`db/seed`)로 분리 서술(디렉토리 구조 확인). 알람 규칙 4그룹 서술은 소스(`observability/prometheus/rules/`) 기준 정합 확인 — guard-skip 이 "재고 복원 가드"로 오독되지 않도록 "종결 가드 skip 메트릭" 문구만 보강. 오탈자("발생이 ~ 발생할 수 있다") 정정, "보상 트랜잭션" → "보상 TX" 2곳, outbox-pattern 링크 문구를 "두 모델의 상태머신"에서 "payment 4상태 머신 / pg processedAt·availableAt 모델"로 정정.
- **`metrics.md`**: 운영 연동 절을 현행화 — cAdvisor 삭제(`docker/docker-compose.observability.yml` 서비스 목록에 없음 확인), scrape/evaluation 주기 15s(`observability/prometheus/prometheus.yml:2-3`), 서비스별 타깃(payment/pg/product/user/gateway/eureka, `prometheus.yml:21-63`)로 교체, Grafana 자격증명 admin/admin123(`docker-compose.observability.yml:48-49`). 배너 패키지 서술("서비스별 `core/metrics`")을 실제 패키지(`core/common/metrics` + `infrastructure/metrics`, 4서비스 grep 확인)로 정정. 말미 "이후 변화" 절 신설(Tempo/Loki, `DependencyHealthMetrics`, 알람 규칙 4그룹, Toxiproxy 요약). state-management 링크 문구 현행화.
- **`async-outbox.md`**: benchmark 프로파일 `fixed-delay-ms` 를 5000(default 값 오기)에서 2000(`application-benchmark.yml:23` 확인)으로 정정 + 층위 주석 보강. 주요 클래스 표에 "현존 여부" 열 신설 — `PaymentConfirmChannel`/`OutboxImmediateWorker`/`OutboxProcessingService` 삭제됨, `OutboxImmediatePublisher`/`OutboxImmediateEventHandler`/`OutboxWorker` 는 존재(패키지·역할 일부 변경) 확인 후 표기. 단타 문장 3곳(L133/L162/L203) 을 연결어미로 밀도 개선. 말미 "재고 복구 가드" 링크에 역사 표시 추가(RecoveryDecision 과 동일 축).
- **`outbox-channel-dispatch.md`**: 좀비 임계 근거를 "벤더 timeout × 2"에서 "실측 read-timeout 10s 배수"로 정정. outbox-pattern 링크 문구를 "세 모델(payment/pg/stock)"에서 "두 모델(payment/pg) + stock-committed 는 Kafka EOS 위임"으로 정정. graceful shutdown 중복 서술(상세 비교 표 vs 채택 근거 절)을 표 참조 1줄로 축소. "pg-confirm-listener-split 토픽" 내부 워크플로우 용어를 자연어("listener/워커 분리 작업(2026-05-09)")로 치환.
- **`trace-propagation.md`**: 로그 라인 예시(`ts=... traceid=... spanid=...` key=value 가상 포맷)를 실제 LogFmt+logback 포맷(`[LogDomain] | EventType | message` + `[traceId:%X{traceId}]`, spanId 미출력)으로 교체. 사전 지식 표의 LogFmt 정의를 "key=value 표준"에서 실제 포맷으로 정정. OTel→Tempo/MDC→Loki 대응표 3회 반복(§ThreadLocal 두 개/§로그 측/§In-memory channel 경계)을 최초 1회(§ThreadLocal 두 개 표)만 남기고 나머지 2곳은 참조 문장으로 축소.

### 6.3 스킵 결정 (검수 지적 중 미반영)

1. **표 구분선·패딩 정규화** — 검수가 일부 표의 열 폭 불균형을 지적했으나, `.claude/skills/_shared/conventions/writing.md` 의 "표 포맷은 작성자가 입력한 그대로 유지" 원칙과 충돌해 스킵.
2. **`msa-transition.md` 알람 규칙 "3그룹" 지적 기각** — 검수 일부가 알람 그룹을 3그룹으로 지적했으나, 소스(`observability/prometheus/rules/*.yml`)로 재확인한 결과 4그룹(coordinator/guard-skip/dlq/availability)이 정합이며 검수 근거는 문서 상호 인용(§0.3 원칙상 불인정)이라 기각. 다만 guard-skip 문구 오독 가능성만 본 수정에서 보강.
3. **README 이미지·GUIDE 괄호 병기** — README 스크린샷 캡션과 PAYMENT-FLOW-GUIDE 의 괄호 병기 표기 지적은 판단을 수정 2차로 유보(위키 우선 처리, 재검수 결과에 따라 반영 여부 결정).

### 6.4 수정 2차 반영 — README·GUIDE + 위키 잔여 + 규격 sweep (파일 수정, 메인 저장소분만 커밋)

수정 1차(위키 11파일, 커밋 29807620) 이후 남은 지적 3건(README 시크릿 안내·약어 미풀이·문체, PAYMENT-FLOW-GUIDE 서사·Phase 축 혼동, 위키 잔여 5파일 + 전 위키 규격 sweep)을 반영한다.

**README.md**: 시크릿 안내를 실제 env 매핑(`.env.secret.example:1`=`TOSS_TEST_SECRET_KEY`, `docker-compose.apps.yml:63,105`=`TOSS_SECRET_KEY` 로 매핑, NICEPAY 2종은 예시 파일 부재)으로 정정 + TOCTOU/DLQ/EOS/VT/OTel/MDC/TX 약어 각주 신설(표 하단) + "(k6 Round 9 · 모놀리스 시점)" 내부 라운드 번호를 "k6 최종 측정 · 모놀리스 시점" 자연어로 교체 + 워커→Worker 5곳(발행 Worker/가상 스레드 Worker) + 보상 트랜잭션→보상 TX 2곳 + 재고 복원→재고 복구 3곳(mermaid 노드 포함) + mermaid 라벨 유니코드 화살표 6곳 → `->`, 중간점 1곳 → `/`. 스킵(확정): `<img width>` 5건은 크기 지정이 순수 마크다운으로 불가해 유지(사용자 원본 표현), `:---` 좌측 정렬 셀은 표 포맷 보존 원칙(§writing.md)에 해당해 유지.

**PAYMENT-FLOW-GUIDE.md**: L3-4 존댓말 4건 → `~다` 종결 + L5 헤더의 "DOCS-CONSISTENCY-OVERHAUL Task 12" 내부 태스크 ID 제거(날짜만 남김, 정정 2026-07-06 항목 추가) + "Phase 1~6"(결제 흐름 단계) → "단계 1~6" 전면 개칭해 프로젝트 개발 시기 Phase 축과 분리(§A 헤더 6곳 + §B/D mermaid subgraph 라벨 10곳 + 관련 문서 1곳) + `DLQ-REACHABILITY`/`TQ-1` 내부 ID 3곳을 자연어(날짜 + 설계명)로 치환 + §A 단계18/19 불릿 하위 뎁스 분해(각 3-4개 조건 분기를 sub-bullet 로) + 단계15 노트/검증메모 두 곳을 노운 형(noun-form) 전환 + L72/L246 블록쿼트 한 줄 한 문장 분리 + mermaid 유니코드 화살표(§0/§B-1/§B-2/§D 전 구간, 총 19곳) → `->`, 중간점(§B-1/§B-2, 총 10곳) → `/` 또는 `,` — 이전 라운드(Task 12)가 손대지 않은 범위까지 전수 재확인해 기계적으로 정규화. 스킵(확정): 괄호 병기 `(PaymentController.checkout)` 패턴은 코드 식별자 부가 표기로 가이드 문서 목적에 부합해 유지.

**위키 잔여 5파일** (파일 수정만, 위키는 별도 저장소 — 커밋은 사용자):
- `compensation-tx.md`(서사 최우선) — "최종 일관성 보장 원리"·"설계 결정 요약" 두 절에 "(Phase 5 모델 기준)" 헤더 태그 + 상단 1줄 안내(현재 모델 대응은 위 "이 모델의 이후 변화" 절 참고)를 추가해 역사 절(§217) 뒤에서 폐기 메커니즘이 현재형으로 재등장하던 문제를 해소. "재고 복구 가드" 원칙 3중 반복(본체 L116-121 유지, "최종 일관성" 항목2·"설계 결정 요약" 첫 행은 1줄 참조로 축소). §89-213 주요 절 제목 4곳에 "(Phase 5 모델)" 병기. L6 EOS 풀이, "가장 심각한" 평가 표현 → 사실 서술("재고 수량이 실제 판매 가능량보다 많아짐") 정정, 한 줄 한 문장 분리 6곳, mermaid 화살표 잔존 4곳 추가 정정(1차 반영분 누락 확인분).
- `idempotency.md` — 말미 "이 모델의 이후 변화" 절 신설(Redis 어댑터 전환 + 포트 계약/원자성 원칙 불변 명시) + "관련 위키" 링크 문구 현행화(state-management EOS 기준·async-outbox 모놀리스 시점·compensation-tx Phase 5+이후 변화 병기) + T4(메서드 체인 → 도메인 행위 치환, `IdempotencyResult.hit()`/`isDuplicate()` 체인 서술 정정) + 한 줄 한 문장 2곳 + mermaid 화살표 3곳.
- `scenario-test.md` — Fake 구현체 절(§Fake 구현체 상세) 서두에 "구 모놀리스 코드 기준" 명시(배너와 정합) + "설계 원칙" 표의 `OutboxProcessingServiceTest` Mock 예시를 현존 테스트(`PaymentConfirmResultUseCaseTest`의 `KafkaTemplate` Mock)로 교체 + "OutboxProcessingServiceTest(폐기)" 절의 state-management 링크 문구를 "테스트 커버리지 상세"에서 실제 테스트 소재지(`PaymentConfirmResultUseCaseTest`) 명시로 정정 + EOS 풀이 + "~를 통해" 3곳 자연어 치환 + 한 줄 한 문장/단타 3곳.
- `pg-strategy.md` — "현재 ~ 구현되어 있으며" 현재형 서술을 과거형으로 정정(배너 "이후 재작성됨"과 정합) + OCP 풀이(L6) + "~를 통해" 1곳 + 한 줄 한 문장 2곳 + mermaid `{tid}` 4곳 → `tid`(중괄호는 mermaid 노드 문법과 충돌 가능한 예약 문자).
- `stock-cache-recovery.md` — 내부 "토픽" 표기(`DLQ-REACHABILITY 토픽`) 자연어화("2026-06-25, DLQ 도달 보장 설계") + EOS 풀이(L219) + 워커→Worker(L139) + 한 줄 한 문장 4곳 + 단타 1곳(연결어미로 밀도 개선).

**전 위키 sweep** (5파일 외 나머지 19파일 대상 확인, 실제 반영분만):
- 워커→Worker: `pg-confirm-flow.md`(27곳) · `outbox-channel-dispatch.md`(13곳) · `state-management.md`(1곳) · `trace-propagation.md`(3곳) · `message-delivery-and-dedupe.md`/`outbox-pattern.md`(추가 확인분) 전건 치환, `Benchmark-Report.md` 는 시점 기록(배너·링크만) 대상이라 범위 밖으로 유지.
- "~를 통해" 잔존 0 확인 후 정정: `ai-workflow.md`(1) · `structured-logging.md`(5) · `metrics.md`(3) · `state-management.md`(1). `Benchmark-Report.md` 1건은 범위 밖 유지.
- 내부 워크플로우 "토픽" 표기 잔존 0 확인 후 정정: `retry-recovery.md` L124(`retry-metric-cleanup 토픽` → "2026-06-22, 재시도 지표 정리 작업") · `async-outbox.md` L410(`msa-transition 토픽` → "2026-04-24 MSA 전환") · `message-delivery-and-dedupe.md` L256(`DLQ-REACHABILITY 토픽` → "2026-06-25, DLQ 도달 보장 설계").
- dead-end 리포 경로 참조(백틱 파일 경로만, 링크 아님) → GitHub URL 전환: `state-management.md` L8·L298(CONFIRM-FLOW.md 2건) · `pg-confirm-flow.md` L360(PAYMENT-FLOW.md). 형식: `https://github.com/hyoguoo/payment-platform/blob/main/docs/context/<파일>`.
- `retry-recovery.md` L123-124 문장 순서 정리(대체 사실을 링크 안내보다 먼저 서술) + FCG 계보 1줄 신설("FCG 아이디어는 pg-service 에 미배선 코드로 재구현돼 있다 — 상세는 pg-confirm-flow 참고") — `retry-recovery.md`(FCG=완전 대체 뉘앙스) 와 `pg-confirm-flow.md`(FCG=구현 완료·미연결) 사이 지위 상충 해소.
- 명사형 불릿 잔존 정정: `message-delivery-and-dedupe.md` L72-73(발행 사실 미보장/PID 변경 중복 적재) · `pg-confirm-flow.md` L69-70(listener 분리/폴링 폴백) · L118-121(TX_A/벤더 호출/TX_B/발행) · L140-141(종결 상태 불변/IN_PROGRESS 재수신) · `state-management.md` L200-206(진입 가드/FAILED·QUARANTINED 순서/재시도 판단, 3개 하위 뎁스 분해 포함).
- 약어 첫 등장 풀이 잔존 정정: `outbox-pattern.md`(EOS/MDC) · `outbox-channel-dispatch.md`(OTel·MDC/VT/SoT 순서 정정/CDC/TX_A·TX_B 병기) · `pg-confirm-flow.md`(VT/CAS) · `architecture.md`(SoT/MDC/DLQ/EOS) · `msa-transition.md`(TX/DLQ/EOS) · `metrics.md`(DLQ) · `structured-logging.md`(OTel/MDC) · `state-management.md`(DLQ).
- 평가 형용사 정정: `outbox-channel-dispatch.md`("가장 흔한"→"흔히 쓰이는") · `msa-transition.md`("가장 크고"→"크고", "가장 강한"→"강한") · `trace-propagation.md`("가장 큰 끊김 위험"→"대표적인 끊김 위험 지점").
- mermaid 유니코드 화살표(→)/중간점(·) 잔존 정정: `async-outbox.md`(1곳) · `metrics.md`(3곳) · `pg-confirm-flow.md`(4곳 화살표 + 1곳 중간점) · `trace-propagation.md`(2곳 화살표 + 1곳 중간점) · `compensation-tx.md`(수정 1차 누락분 4곳, 5개 target 파일 자체 재검수로 발견). 전건 grep 재검사로 mermaid 코드 블록 내 잔존 0건 확인(target 10파일 기준).
- 한 줄 한 문장 반영: `metrics.md`(3곳) · `state-management.md`(1곳) · `pg-confirm-flow.md`(도입부 2곳 + 후반 4곳). `trace-propagation.md`/`async-outbox.md` 의 지적분은 재확인 결과 이미 정합(번호 리스트 항목 내 줄바꿈 wrap 이라 실제 위반 아님)이라 추가 변경 없음.

**./gradlew test 대상 아님** — 문서 전용 태스크(README/GUIDE/위키), 코드 무변경.

이 절(§6.4)의 반영 내역은 PLAN.md Task 19 완료 결과에는 아직 기록하지 않는다(재검수 라운드 2 이후 종결).
