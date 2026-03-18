# Phase 2: Sync Adapter - Context

**Gathered:** 2026-03-15
**Status:** Ready for planning

<domain>
## Phase Boundary

기존 confirm 플로우의 동작이 포트 래핑 후에도 100% 동일하게 유지되어 비동기 어댑터 도입의 회귀 기준선이 확립된다.

SYNC-01/02/03 요구사항이 대상이며, 재고 감소·Toss API 호출·상태 전이의 실제 동작을 변경하지 않는다.

</domain>

<decisions>
## Implementation Decisions

### Phase 2 작업 범위
- **추가 구현 없음** — `SyncConfirmAdapter` 구현 + 관련 단위 테스트 + 통합 테스트가 Phase 1에서 이미 완료됨
- Phase 2 작업은 REQUIREMENTS.md SYNC-01/02/03 체크마크 업데이트와 상태 공식화에 한정됨

### 명시적 `spring.payment.async-strategy=sync` 테스트
- **별도 테스트 추가 불필요** — `matchIfMissing=true`가 "미설정 = sync 명시 설정"을 동일하게 커버
- 현재 `PaymentControllerTest`의 전체 통합 테스트 슈트(성공/재고부족/재시도가능오류/재시도불가오류/타임아웃)가 회귀 기준선으로 충분

### SYNC-03 불변 검증
- 별도 테스트 추가 없음 — `PaymentConfirmServiceImpl` 내부 코드는 Phase 1에서 변경되지 않았음이 코드베이스에서 직접 확인됨
- `SyncConfirmAdapter`가 위임만 수행하는 구조가 기존 코드 유지를 구조적으로 보장

### Claude's Discretion
- REQUIREMENTS.md 체크마크 업데이트 시 SYNC-01/02/03 세 항목 모두 일괄 완료 처리

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `SyncConfirmAdapter` (`infrastructure/adapter/`): 이미 구현 완료 — `PaymentConfirmServiceImpl`에 위임 후 `ResponseType.SYNC_200` 반환
- `SyncConfirmAdapterTest`: 단위 테스트 완료 — 위임 검증 + `@ConditionalOnProperty` 어노테이션 검증
- `PaymentControllerMvcTest.confirmPayment_SyncAdapter_Returns200()`: WebMvcTest — `SYNC_200` → HTTP 200 분기 검증
- `PaymentControllerTest` (IntegrationTest): 5개 시나리오(성공, 재고부족, 재시도가능, 재시도불가, 타임아웃) 회귀 기준선 커버

### Established Patterns
- `@ConditionalOnProperty(name="spring.payment.async-strategy", havingValue="sync", matchIfMissing=true)`: Phase 1에서 첫 도입, OutboxConfirmAdapter/KafkaConfirmAdapter도 동일 패턴 사용
- `PaymentConfirmServiceImpl`은 `@Service`만 유지, `implements PaymentConfirmService` 제거됨 — 어댑터가 유일한 포트 구현체

### Integration Points
- Phase 2에서 변경되는 코드 없음 — 기존 연결 구조 그대로 유지

</code_context>

<specifics>
## Specific Ideas

No specific requirements — open to standard approaches

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 02-sync-adapter*
*Context gathered: 2026-03-15*
