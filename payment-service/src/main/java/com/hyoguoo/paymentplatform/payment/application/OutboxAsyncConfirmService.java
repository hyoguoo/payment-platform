package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentFailureUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator.StockDecrementResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 확정 비동기 오케스트레이터.
 *
 * <p>TX 경계를 caller(본 서비스)에서 직접 조립한다: Redis DECR(TX 외부) → 결과 분기
 * (REJECTED/CACHE_DOWN/SUCCESS) → SUCCESS인 경우에만 coordinator.executeConfirmTx() @Transactional
 * 내에서 event 전이 + outbox PENDING을 원자 커밋. Kafka 발행은 TX 밖에서 별도.
 *
 * <p>executeConfirmTx 실패 시 decrementStock 으로 차감한 재고를 redis-stock INCR 로 보상한다.
 * try 블록 외부 변수 재할당을 막기 위해 보상 로직은 private 메서드로 추출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxAsyncConfirmService implements PaymentConfirmService {

    private final PaymentTransactionCoordinator transactionCoordinator;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentFailureUseCase paymentFailureUseCase;
    private final StockCachePort stockCachePort;

    @Override
    public PaymentConfirmAsyncResult confirm(PaymentConfirmCommand command)
            throws PaymentOrderedProductStockException {
        PaymentEvent paymentEvent =
                paymentLoadUseCase.getPaymentEventByOrderId(command.getOrderId());

        paymentEvent.validateConfirmRequest(
                command.getUserId(),
                command.getAmount(),
                command.getOrderId(),
                command.getPaymentKey()
        );

        StockDecrementResult stockResult =
                transactionCoordinator.decrementStock(paymentEvent.getPaymentOrderList());

        switch (stockResult) {
            case REJECTED -> {
                LogFmt.warn(log, LogDomain.PAYMENT, EventType.STOCK_DECREASE_FAIL,
                        () -> String.format("orderId=%s reason=stock_rejected", command.getOrderId()));
                paymentFailureUseCase.handleStockFailure(paymentEvent, "재고 부족으로 인한 결제 실패");
                throw PaymentOrderedProductStockException.of(
                        PaymentErrorCode.ORDERED_PRODUCT_STOCK_NOT_ENOUGH);
            }
            case CACHE_DOWN -> {
                LogFmt.warn(log, LogDomain.PAYMENT, EventType.STOCK_DECREASE_FAIL,
                        () -> String.format("orderId=%s reason=cache_down", command.getOrderId()));
                transactionCoordinator.markStockCacheDownQuarantine(paymentEvent);
                throw PaymentOrderedProductStockException.of(
                        PaymentErrorCode.ORDERED_PRODUCT_STOCK_NOT_ENOUGH);
            }
            case SUCCESS -> executeConfirmTxWithStockCompensation(
                    paymentEvent, command.getPaymentKey(), command.getOrderId());
        }

        return PaymentConfirmAsyncResult.builder()
                .orderId(command.getOrderId())
                .amount(command.getAmount())
                .build();
    }

    /**
     * executeConfirmTx 를 호출하고, 실패 시 decrementStock 에서 차감한 재고를 보상한다.
     *
     * <p>caller 측 try/catch 로 보상한다. 보상 increment 가 실패해도 원본 예외는 그대로 전파한다.
     */
    private void executeConfirmTxWithStockCompensation(
            PaymentEvent paymentEvent, String paymentKey, String orderId) {
        try {
            transactionCoordinator.executeConfirmTx(paymentEvent, paymentKey, orderId);
        } catch (RuntimeException txException) {
            compensateStock(paymentEvent.getPaymentOrderList(), orderId, txException);
            throw txException;
        }
    }

    private void compensateStock(
            List<PaymentOrder> paymentOrderList, String orderId, RuntimeException txException) {
        LogFmt.error(log, LogDomain.PAYMENT, EventType.STOCK_COMPENSATE_FAIL,
                () -> String.format("orderId=%s executeConfirmTx 실패로 재고 보상 수행 error=%s",
                        orderId, txException.getMessage()));
        for (PaymentOrder order : paymentOrderList) {
            try {
                stockCachePort.increment(order.getProductId(), order.getQuantity());
                LogFmt.warn(log, LogDomain.PAYMENT, EventType.STOCK_COMPENSATE_SUCCESS,
                        () -> String.format(
                                "orderId=%s productId=%d quantity=%d 재고 보상 완료",
                                orderId, order.getProductId(), order.getQuantity()));
            } catch (RuntimeException compensateException) {
                LogFmt.error(log, LogDomain.PAYMENT, EventType.STOCK_COMPENSATE_FAIL,
                        () -> String.format(
                                "orderId=%s productId=%d quantity=%d 재고 보상 실패 수동복구필요 error=%s",
                                orderId, order.getProductId(), order.getQuantity(),
                                compensateException.getMessage()));
            }
        }
    }
}
