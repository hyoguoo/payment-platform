package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentFailureUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator.StockDecrementResult;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.StockRetentionMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.StockCacheUnavailableException;
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
 *
 * <p>executeConfirmTx 실패 시 decrementStock 으로 차감한 재고는 보상하지 않고 차감 상태를
 * 그대로 유지한다(토큰도 유지) — 동시 confirm·재확정 과매도를 0으로 만들기 위함이다. 대신
 * {@link StockRetentionMetrics}로 미복구 상태를 가시화한 뒤 원본 예외를 그대로 전파한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxAsyncConfirmService implements PaymentConfirmService {

    private final PaymentTransactionCoordinator transactionCoordinator;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentFailureUseCase paymentFailureUseCase;
    private final StockRetentionMetrics stockRetentionMetrics;

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
                transactionCoordinator.decrementStock(
                        command.getOrderId(), paymentEvent.getPaymentOrderList());

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
                // 재고가 모자란 것이 아니라 재고를 확인조차 못 한 상태다. 같은 오류로 응답하면
                // 호출자가 "다시 시도해도 소용없는 실패"로 오해하므로 일시적 장애로 신호한다.
                throw StockCacheUnavailableException.of(PaymentErrorCode.STOCK_CACHE_UNAVAILABLE);
            }
            case SUCCESS -> executeConfirmTxWithStockRetention(
                    paymentEvent, command.getPaymentKey(), command.getOrderId());
        }

        return PaymentConfirmAsyncResult.builder()
                .orderId(command.getOrderId())
                .amount(command.getAmount())
                .build();
    }

    /**
     * executeConfirmTx 를 호출하고, 실패 시 decrementStock 에서 차감한 재고를 보상하지 않는다.
     *
     * <p>재고와 토큰 차감 상태를 그대로 유지해야 동일 orderId 재확정이 {@code ALREADY_DONE}으로
     * 흡수되어 동시 confirm·재확정 과매도가 0이 된다. 미복구 상태는 메트릭+error 로그로
     * 가시화한 뒤 원본 예외를 그대로 전파한다.
     */
    private void executeConfirmTxWithStockRetention(
            PaymentEvent paymentEvent, String paymentKey, String orderId) {
        try {
            transactionCoordinator.executeConfirmTx(paymentEvent, paymentKey, orderId);
        } catch (RuntimeException txException) {
            stockRetentionMetrics.record();
            LogFmt.error(log, LogDomain.PAYMENT, EventType.STOCK_RETENTION_UNRECOVERED,
                    () -> String.format(
                            "orderId=%s executeConfirmTx 실패로 선차감 재고 미복구 유지(보상 안 함) error=%s",
                            orderId, txException.getMessage()));
            throw txException;
        }
    }
}
