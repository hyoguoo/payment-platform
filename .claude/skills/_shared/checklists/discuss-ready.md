# discuss-ready 체크리스트

discuss 단계 종료 조건. 두 개의 섹션으로 분리된다:

- **Gate checklist** — Critic / Domain Expert가 판정하는 설계 품질 항목. 모두 **yes**여야 pass.
- **Post-phase checklist** — 페르소나가 판정하지 않는다. discuss 라운드 pass 이후 오케스트레이터(`workflow-discuss`)가 순차 실행하는 housekeeping. Critic 판정에 포함 금지.

---

# Gate checklist (Critic / Domain Expert 판정)

## scope (범위)

- [ ] TOPIC이 UPPER-KEBAB-CASE로 확정됨
- [ ] 이 변경이 건드리는 모듈/패키지 경계가 명시됨
- [ ] non-goals(이번 작업에서 **안 할 것**)가 최소 1개 이상 명시됨
- [ ] 범위 밖에서 발견된 이슈는 `docs/context/TODOS.md`로 위임됐거나 현재 스코프에 포함됨

## design decisions (설계 결정)

- [ ] hexagonal layer 배치(어느 layer에 무엇을 둘지)가 명시됨
- [ ] 포트 인터페이스 위치(`application/port` vs `infrastructure/port`)가 결정됨
- [ ] 새 상태가 추가되는 경우, 상태 전이 다이어그램(텍스트/mermaid)이 `docs/topics/<TOPIC>.md`에 있음
- [ ] **전체 결제 흐름**(결제 요청 → 외부 PG 연동 → 후처리)과의 호환성이 검토됨

## acceptance criteria (수락 조건)

- [ ] 성공 조건이 **관찰 가능한 형태**로 기술됨 (예: "p95 < 300ms", "테스트 X가 pass")
- [ ] 실패를 어떻게 관찰할지(로그/지표/테스트) 명시됨

## verification plan (검증 계획)

- [ ] 테스트 계층이 결정됨 (단위/통합/k6 중 어디까지 할지)
- [ ] 벤치마크가 필요한 경우 지표(TPS, p95, 실패율)가 명시됨

## artifact (산출물)

- [ ] `docs/topics/<TOPIC>.md`에 "결정 사항" 섹션이 존재

## domain risk (Domain Expert 전용)

- [ ] 멱등성 전략이 결정됨 (idempotency key 소스/수명/충돌 처리)
- [ ] 장애 시나리오 최소 3개 식별됨 (예: 외부 PG 타임아웃, 내부 네트워크 단절, DB 트랜잭션 롤백, 메시지 유실 등)
- [ ] 재시도 정책이 정의됨 (횟수, 백오프, 포기 조건) — 재시도가 적용 가능한 경우만
- [ ] PII/민감정보가 새로 도입되는 경우, 로깅·저장·전송 경로 검토됨

---

# Post-phase checklist (오케스트레이터 실행, 판정 제외)

아래 항목은 Critic / Domain Expert가 판정하지 않는다. 두 페르소나가 모두 pass를 반환한 뒤 `workflow-discuss` 오케스트레이터가 순서대로 실행하고, 수행 여부만 확인한다.

- [ ] GitHub issue 생성 (배경 + 설계 + 범위 포함) — `mcp__github__issue_write`
- [ ] feature branch `#<issue-number>` 생성
- [ ] STATE.md에 issue/branch 반영 + stage → `plan`
- [ ] `docs:` 단일 커밋 (topic.md + STATE.md + 라운드 문서)
