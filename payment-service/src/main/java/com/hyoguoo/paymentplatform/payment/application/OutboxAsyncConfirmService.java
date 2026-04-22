package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentFailureUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator.StockDecrementResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 확정 비동기 오케스트레이터.
 *
 * <p>TX 경계를 caller(본 서비스)에서 직접 조립한다: Redis DECR(TX 외부) → 결과 분기
 * (REJECTED/CACHE_DOWN/SUCCESS) → SUCCESS인 경우에만 coordinator.executeConfirmTx() @Transactional
 * 내에서 event 전이 + outbox PENDING을 원자 커밋. Kafka 발행은 TX 밖에서 별도.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxAsyncConfirmService implements PaymentConfirmService {

    private final PaymentTransactionCoordinator transactionCoordinator;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentFailureUseCase paymentFailureUseCase;
    private final PaymentConfirmPublisherPort confirmPublisher;

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
            case SUCCESS -> transactionCoordinator.executeConfirmTx(
                    paymentEvent, command.getPaymentKey(), command.getOrderId());
        }

        confirmPublisher.publish(
                command.getOrderId(),
                paymentEvent.getBuyerId(),
                paymentEvent.getTotalAmount(),
                command.getPaymentKey()
        );

        return PaymentConfirmAsyncResult.builder()
                .orderId(command.getOrderId())
                .amount(command.getAmount())
                .build();
    }
}
