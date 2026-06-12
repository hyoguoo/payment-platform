# code-ready 체크리스트

코드 변경의 완료 조건. **ship 단계 리뷰**(diff 전체)와 **단독 리뷰**에서 Reviewer / Domain Expert가 판정 기준으로 사용한다.

결정론적 백본(`./gradlew test`)이 통과하지 않으면 다른 항목은 의미가 없다.

---

## task execution (태스크 실행 — 워크플로우 내 리뷰에서만)

- [ ] 각 태스크의 RED / GREEN 커밋이 존재 (`tdd=true`인 경우), 커밋 메시지 `test:` / `feat:` / `refactor:` 포맷 준수
- [ ] PLAN.md 체크박스 + "완료 결과"가 채워짐
- [ ] STATE.md가 올바르게 갱신됨

## test gate (결정론적 백본)

- [ ] **전체 `./gradlew test` 통과** — 이 항목이 false면 다른 어떤 항목도 통과 선언 불가
- [ ] 신규/수정된 business logic에 테스트 커버리지 존재
- [ ] 새 state machine 전이가 `@ParameterizedTest @EnumSource`로 커버됨
- [ ] 테스트가 **동작을 검증**함 — 구현 구조를 미러링하거나 mock 호출 횟수만 세는 테스트가 아님
- [ ] 실패 경로(예외/거부/타임아웃)에도 단언이 존재 — happy path만 커버하지 않음

## convention (관례)

- [ ] Lombok 패턴 준수 (`@RequiredArgsConstructor`, `@Getter` 사용, `@Data` 금지)
- [ ] `@AllArgsConstructor(access = AccessLevel.PRIVATE)` + `@Builder` 패턴 준수 (적용 대상에서)
- [ ] 신규 로깅이 LogFmt 사용
- [ ] `null` 반환 금지, `Optional` 사용
- [ ] `catch (Exception e)` 없음 (있다면 `handleUnknownFailure` 경유)
- [ ] `var` 키워드 없음 — 명시적 타입 선언

## execution discipline (실행 규율)

- [ ] 범위 밖 코드 수정 없음 (발견한 이슈는 주석 또는 `docs/context/TODOS.md`)
- [ ] 죽은 코드/미사용 import가 새로 생기지 않음

## domain risk (Domain Expert 전용)

- [ ] `paymentKey` / `orderId` / 카드번호 등이 plaintext 로그에 노출되지 않음
- [ ] 보상 / 취소 로직에 멱등성 가드 존재
- [ ] PG가 반환하는 "이미 처리됨" 계열 특수 응답(예: `ALREADY_PROCESSED_*`)이 맹목 수용되지 않고 정당성 검증을 거침
- [ ] 상태 전이가 불변식을 위반하지 않음 (예: SUCCESS → FAIL 금지)
- [ ] race window가 있는 경로에 락 / 트랜잭션 격리 고려됨
- [ ] 부분 실패 시 돈·재고 정합이 복구 가능 (보상 경로 또는 정합 스캐너 존재)
