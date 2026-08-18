package com.hyoguoo.paymentplatform.payment.integration;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.util.StockHoldReverter;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;

/**
 * 재고 선차감을 되돌리는 다섯 주체의 실제 호출 경로를 그대로 재현하는 바인딩.
 *
 * <p>실제 운영 빈(주입받은 {@link StockHoldReverter}/{@link StockCachePort}/
 * {@link StockHoldRecordRepository})을 그대로 감싸 각 주체가 부르는 메서드를 그대로 호출한다 —
 * 새 되돌리기 로직을 만들지 않는다.
 *
 * <ul>
 *   <li>{@link #confirmFailed} — {@code PaymentConfirmResultUseCase.handleFailed}</li>
 *   <li>{@link #quarantineEntry} — {@code PaymentConfirmResultUseCase.handleQuarantined}
 *       (확정 실패와 같은 {@link StockHoldReverter#revertEachProductHold} 호출을 공유한다)</li>
 *   <li>{@link #adminResolve} — {@code QuarantineResolveUseCase}
 *       (역시 {@link StockHoldReverter#revertEachProductHold} 를 공유한다)</li>
 *   <li>{@link #recoveryWorker} — {@code StockHoldRecoveryUseCase.recover}
 *       (배치로 미리 읽어 둔 사이클 식별 값으로 {@link StockHoldReverter#revertProductHold} 를
 *       직접 호출한다 — 여기서는 그 배치 스캔을 {@code findSnapshot} 한 번으로 대신한다)</li>
 *   <li>{@link #rejectOnly} — {@code PaymentTransactionCoordinator.rejectDecrementedProducts}
 *       (캐시만 되돌리고 선차감 기록은 건드리지 않는다 — 유일하게 DB 를 부르지 않는 주체)</li>
 * </ul>
 *
 * <p><b>거절 전용은 다른 넷과 진짜로 동시에 경합하지 않는다.</b> 거절 전용은 이번 확정 요청이
 * 직접 차감한 상품에 대해서만, 결제가 아직 READY 상태로 주문 단위 선점을 쥔 채 실행된다 — 이
 * 시점에는 다른 네 주체 중 어느 것도 전제 조건(벤더 응답 이후 IN_PROGRESS, 격리 상태 QUARANTINED,
 * 종결 상태)을 만족하지 못해 같은 사이클을 건드릴 수 없다. 유일하게 실제로 이어지는 순서는
 * 거절 전용이 먼저 끝난 뒤, 결제가 실패로 종결되고 나서 주기 작업이 남은 잡음 기록을 정리하는
 * 것뿐이다 — 그래서 거절 전용이 낀 짝은 진짜 동시 레이스가 아니라 순차 핸드오프로 검증한다
 * (호출부 테스트 클래스의 handoff 헬퍼 참고).
 */
final class StockHoldRevertActions {

    @FunctionalInterface
    interface RevertAction {

        void run(String orderId, PaymentOrder order);
    }

    private final StockHoldReverter stockHoldReverter;
    private final StockCachePort stockCachePort;
    private final StockHoldRecordRepository stockHoldRecordRepository;

    StockHoldRevertActions(
            StockHoldReverter stockHoldReverter,
            StockCachePort stockCachePort,
            StockHoldRecordRepository stockHoldRecordRepository) {
        this.stockHoldReverter = stockHoldReverter;
        this.stockCachePort = stockCachePort;
        this.stockHoldRecordRepository = stockHoldRecordRepository;
    }

    void confirmFailed(String orderId, PaymentOrder order) {
        stockHoldReverter.revertEachProductHold(
                singleOrderEvent(orderId, order), stockCachePort, stockHoldRecordRepository);
    }

    void quarantineEntry(String orderId, PaymentOrder order) {
        confirmFailed(orderId, order);
    }

    void adminResolve(String orderId, PaymentOrder order) {
        confirmFailed(orderId, order);
    }

    void recoveryWorker(String orderId, PaymentOrder order) {
        stockHoldRecordRepository.findSnapshot(orderId, order)
                .ifPresent(snapshot -> stockHoldReverter.revertProductHold(
                        orderId, order, snapshot.cycleToken(), stockCachePort, stockHoldRecordRepository));
    }

    void rejectOnly(String orderId, PaymentOrder order) {
        stockCachePort.rejectCompensate(orderId, order);
    }

    private static PaymentEvent singleOrderEvent(String orderId, PaymentOrder order) {
        return PaymentEvent.allArgsBuilder()
                .orderId(orderId)
                .paymentOrderList(List.of(order))
                .allArgsBuild();
    }
}
