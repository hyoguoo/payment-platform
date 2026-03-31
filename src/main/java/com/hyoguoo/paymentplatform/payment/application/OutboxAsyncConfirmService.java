package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult.ResponseType;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentFailureUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;

import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "spring.payment.async-strategy",
        havingValue = "outbox"
)
public class OutboxAsyncConfirmService implements PaymentConfirmService {

    private final PaymentTransactionCoordinator transactionCoordinator;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentFailureUseCase paymentFailureUseCase;
    private final PaymentConfirmPublisherPort confirmPublisher;

    @Override
    @Transactional(rollbackFor = PaymentOrderedProductStockException.class)
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

        // executePayment(READY→IN_PROGRESS) + 재고 감소 + Outbox 생성을 단일 트랜잭션으로 처리
        // → TX1/TX2 분리로 인한 서버 크래시 시 재고 미감소 상태 결제 확인 방지
        try {
            transactionCoordinator.executePaymentAndStockDecreaseWithOutbox(
                    paymentEvent, command.getPaymentKey(),
                    command.getOrderId(), paymentEvent.getPaymentOrderList()
            );
        } catch (PaymentOrderedProductStockException e) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.STOCK_DECREASE_FAIL,
                    () -> String.format("orderId=%s", command.getOrderId()));
            paymentFailureUseCase.handleStockFailure(paymentEvent, e.getMessage());
            throw e;
        }

        confirmPublisher.publish(
                command.getOrderId(),
                paymentEvent.getBuyerId(),
                paymentEvent.getTotalAmount(),
                command.getPaymentKey()
        );

        return PaymentConfirmAsyncResult.builder()
                .responseType(ResponseType.ASYNC_202)
                .orderId(command.getOrderId())
                .amount(command.getAmount())
                .build();
    }
}
