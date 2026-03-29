# Planned Cleanup / Future Work

> 이 파일은 현재 작업 범위 밖이지만 향후 처리가 필요한 항목을 추적한다.
> discuss 단계 시작 시 다음 작업을 고를 때 이 파일을 참고한다.

---

## PaymentProcess 테이블 및 관련 로직 삭제

**배경:**
`PaymentProcess` 는 Sync 전략에서 게이트웨이 호출 in-flight 상태를 추적하고, `PaymentRecoverServiceImpl`이 PROCESSING 상태 레코드를 복구하는 데 사용했다.
그런데 ASYNC-PAYMENT-CLEANUP(2026-03-29)에서 `PaymentRecoverServiceImpl`과 `scheduler/port/PaymentRecoverService`가 삭제됐다.
이제 PaymentProcess 레코드를 소비하는 복구 메커니즘이 없어, Sync 전략에서 서버 크래시 시 PROCESSING 상태로 stuck된 레코드가 영구적으로 방치된다.

**삭제 대상:**
- `payment/domain/PaymentProcess.java` — 도메인 클래스
- `payment/application/usecase/PaymentProcessUseCase.java` — use-case 서비스
- `payment/application/port/PaymentProcessRepository.java` — 아웃바운드 포트
- `payment/infrastructure/entity/PaymentProcessEntity.java` — JPA 엔티티
- `payment/infrastructure/repository/JpaPaymentProcessRepository.java` — Spring Data 인터페이스
- `payment/infrastructure/repository/PaymentProcessRepositoryImpl.java` — 포트 구현체
- `PaymentTransactionCoordinator` 내 `createProcessingJob`, `existsByOrderId`, `completeJob`, `failJob` 호출 제거
- `executeStockDecreaseWithJobCreation` → `executeStockDecreaseForSync` 으로 단순화
- `payment_process` DB 테이블 DROP (마이그레이션 스크립트 필요)
- 관련 테스트: `PaymentTransactionCoordinatorTest`, `OutboxWorkerTest` 등에서 PaymentProcess 관련 stub 제거

**주의사항:**
- Sync 전략(`PaymentConfirmServiceImpl`)의 흐름에서 PaymentProcess 생성·완료·실패 로직을 제거해도 기능에는 영향 없음 — 복구 메커니즘이 이미 없는 상태이므로
- DB 마이그레이션: 운영 중이라면 `payment_process` 테이블에 PROCESSING 레코드가 남아 있을 수 있으므로 DROP 전 확인 필요
- `CONCERNS.md`의 "Sync strategy separates stock decrease and executePayment across two transactions" 항목과 연관 — PaymentProcess 삭제 시 해당 concern도 제거

**우선순위:** Low — 현재 기능에는 영향 없으나 dead code 정리 차원
