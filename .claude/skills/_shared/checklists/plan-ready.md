# plan-ready 체크리스트

plan 단계 종료 조건. 두 개의 섹션으로 분리된다:

- **Gate checklist** — Critic / Domain Expert 판정.
- **Post-phase checklist** — pass 이후 `workflow-plan` 오케스트레이터 실행. 판정 제외.

---

# Gate checklist (Critic / Domain Expert 판정)

## traceability (추적성)

- [ ] PLAN.md가 `docs/topics/<TOPIC>.md`의 결정 사항을 참조함
- [ ] 모든 태스크가 설계 결정 중 하나 이상에 매핑됨 (orphan 태스크 없음)

## task quality (태스크 품질)

- [ ] 모든 태스크가 **객관적 완료 기준**을 가짐 ("테스트 X가 pass", "파일 Y 존재" 등)
- [ ] 태스크 크기 ≤ 2시간 (한 커밋 단위로 분해 가능한지로 판정)
- [ ] 각 태스크에 관련 소스 파일/패턴이 언급됨

## TDD specification (TDD 명세)

- [ ] `tdd=true` 태스크는 테스트 클래스 + 테스트 메서드 스펙이 명시됨
- [ ] `tdd=false` 태스크는 산출물(파일/위치)이 명시됨
- [ ] TDD 분류가 합리적 (business logic / state machine / edge case는 tdd=true)

## dependency ordering (의존 순서)

- [ ] layer 의존 순서 준수 (port → domain → application → infrastructure → controller)
- [ ] Fake 구현이 그것을 소비하는 태스크보다 먼저 옴
- [ ] orphan port 없음 (port만 있고 구현/Fake 없는 경우)

## architecture fit (아키텍처 적합성)

- [ ] `docs/context/ARCHITECTURE.md`의 layer 규칙과 충돌 없음
- [ ] 모듈 간 호출이 port / InternalReceiver를 통함
- [ ] `docs/context/CONVENTIONS.md`의 Lombok / 예외 / 로깅 패턴을 따르도록 계획됨

## artifact (산출물)

- [ ] `docs/<TOPIC>-PLAN.md` 존재

## domain risk (Domain Expert 전용)

- [ ] discuss에서 식별된 domain risk가 각각 대응 태스크를 가짐 (멱등성 검증 테스트, 상태 전이 테스트 등)
- [ ] 중복 방지 체크(예: `existsByOrderId`)가 필요한 경로에 계획됨
- [ ] 재시도 안전성 검증 태스크 존재 (재시도 정책이 있는 경우만)

---

# Post-phase checklist (오케스트레이터 실행, 판정 제외)

- [ ] STATE.md stage → `execute`, 활성 태스크 = Task 1
- [ ] PLAN.md + STATE.md + 라운드 문서를 단일 `docs:` 커밋으로 기록
