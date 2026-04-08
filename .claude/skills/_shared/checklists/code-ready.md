# code-ready 체크리스트

execute 단계에서 **태스크 단위**로 판정하는 체크리스트. 각 태스크 완료 시점에
Critic / Domain Expert 페르소나가 이 체크리스트를 판정 기준으로 사용한다.

결정론적 백본(`./gradlew test`)이 통과하지 않으면 다른 항목은 의미가 없다.

---

## task execution (태스크 실행)

- [ ] 현재 태스크의 RED 커밋이 존재 (`tdd=true`인 경우)
- [ ] 현재 태스크의 GREEN 커밋이 존재 (구현 + PLAN.md 체크박스 업데이트 + "완료 결과")
- [ ] REFACTOR 커밋은 필요한 경우에만 존재 (옵션)
- [ ] 커밋 메시지가 `feat:` / `test:` / `refactor:` 포맷 준수
- [ ] STATE.md의 active task가 올바르게 갱신됨

## test gate (결정론적 백본)

- [ ] **전체 `./gradlew test` 통과** — 이 항목이 false면 다른 어떤 항목도 통과 선언 불가
- [ ] 신규/수정된 business logic에 테스트 커버리지 존재
- [ ] 새 state machine 전이가 `@ParameterizedTest @EnumSource`로 커버됨

## convention (관례)

- [ ] Lombok 패턴 준수 (`@RequiredArgsConstructor`, `@Getter` 사용, `@Data` 금지)
- [ ] `@AllArgsConstructor(access = AccessLevel.PRIVATE)` + `@Builder` 패턴 준수 (적용 대상에서)
- [ ] 신규 로깅이 LogFmt 사용
- [ ] `null` 반환 금지, `Optional` 사용
- [ ] `catch (Exception e)` 없음 (있다면 `handleUnknownFailure` 경유)

## execution discipline (실행 규율)

- [ ] 범위 밖 코드 수정 없음 (발견한 이슈는 주석 또는 `docs/context/TODOS.md`)
- [ ] 분석 마비(5+ Read/Grep/Glob without 코드 변경) 없음 — 라운드 중 Critic이 감시

## final task only (마지막 태스크일 때만)

- [ ] STATE.md stage → `review`로 전환됨 (최종 GREEN 커밋 내)
- [ ] `.continue-here.md` 제거됨 (있었다면)

## domain risk (Domain Expert 전용)

- [ ] `paymentKey` / `orderId` / 카드번호 등이 plaintext 로그에 노출되지 않음
- [ ] 보상 / 취소 로직에 멱등성 가드 존재
- [ ] PG가 반환하는 "이미 처리됨" 계열 특수 응답(예: `ALREADY_PROCESSED_*`)이 맹목 수용되지 않고 정당성 검증을 거침
- [ ] 상태 전이가 불변식을 위반하지 않음 (예: SUCCESS → FAIL 금지)
- [ ] race window가 있는 경로에 락 / 트랜잭션 격리 고려됨
