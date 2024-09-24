package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayHandler;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.exception.NonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.RetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

//TODO: 결제 검증 시 Toss Payment 결제 상태 검사 추가
@Service
@RequiredArgsConstructor
public class PaymentConfirmServiceImpl implements PaymentConfirmService {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentGatewayHandler paymentGatewayHandler;
    private final ProductProvider productProvider;

    @Override
    public PaymentConfirmResult confirm(PaymentConfirmCommand paymentConfirmCommand) {
        PaymentEvent paymentEvent = executePayment(paymentConfirmCommand);

        handleProductStockReduction(paymentEvent);

        try {
            validatePaymentEvent(paymentConfirmCommand, paymentEvent);

            TossPaymentInfo tossConfirmInfo = confirmPaymentWithGateway(paymentConfirmCommand);

            PaymentEvent completedPayment = completePayment(paymentEvent, tossConfirmInfo);

            return PaymentConfirmResult.builder()
                    .amount(completedPayment.getTotalAmount())
                    .orderId(completedPayment.getOrderId())
                    .build();
        } catch (RetryableException e) {
            handleRetryableFailure(paymentEvent);
            throw new RuntimeException(e);
        } catch (RuntimeException | NonRetryableException e) {
            handleNonRetryableFailure(paymentEvent);
            throw new RuntimeException(e);
        }
    }

    private PaymentEvent executePayment(PaymentConfirmCommand paymentConfirmCommand) {
        PaymentEvent paymentEvent = getPaymentEventByOrderId(
                paymentConfirmCommand.getOrderId()
        );

        paymentEvent.execute(paymentConfirmCommand.getPaymentKey());
        paymentEventRepository.saveOrUpdate(paymentEvent);

        return paymentEvent;
    }

    private PaymentEvent getPaymentEventByOrderId(String orderId) {
        return paymentEventRepository
                .findByOrderId(orderId)
                .orElseThrow(
                        () -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND)
                );
    }

    private void validatePaymentEvent(
            PaymentConfirmCommand paymentConfirmCommand,
            PaymentEvent paymentEvent
    ) {
        TossPaymentInfo tossPaymentInfo = paymentGatewayHandler.getPaymentInfoByOrderId(
                paymentConfirmCommand.getOrderId()
        );

        paymentEvent.validate(paymentConfirmCommand, tossPaymentInfo);
    }

    private void handleProductStockReduction(PaymentEvent paymentEvent) {
        List<PaymentOrder> paymentOrderList = paymentEvent.getPaymentOrderList();
        if (!reduceStockPaymentOrderListProduct(paymentOrderList)) {
            failPayment(paymentEvent);
        }
    }

    private void failPayment(PaymentEvent paymentEvent) {
        paymentEvent.fail();
        paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    private boolean reduceStockPaymentOrderListProduct(List<PaymentOrder> paymentOrderList) {
        List<PaymentOrder> successfulOrders = new ArrayList<>();

        boolean allSuccess = true;
        for (PaymentOrder paymentOrder : paymentOrderList) {
            boolean success = productProvider.reduceStockWithCommit(
                    paymentOrder.getProductId(),
                    paymentOrder.getQuantity()
            );

            if (success) {
                successfulOrders.add(paymentOrder);
            } else {
                allSuccess = false;
                break;
            }
        }

        if (!allSuccess) {
            successfulOrders.forEach(paymentOrder ->
                    productProvider.increaseStockWithCommit(
                            paymentOrder.getProductId(),
                            paymentOrder.getQuantity()
                    )
            );
            return false;
        }

        return true;
    }

    private TossPaymentInfo confirmPaymentWithGateway(PaymentConfirmCommand paymentConfirmCommand)
            throws RetryableException, NonRetryableException {
        TossConfirmGatewayCommand tossConfirmGatewayCommand = TossConfirmGatewayCommand.builder()
                .orderId(paymentConfirmCommand.getOrderId())
                .paymentKey(paymentConfirmCommand.getPaymentKey())
                .amount(paymentConfirmCommand.getAmount())
                .idempotencyKey(paymentConfirmCommand.getOrderId())
                .build();

        TossPaymentInfo tossPaymentInfo = paymentGatewayHandler.confirmPayment(
                tossConfirmGatewayCommand
        );

        PaymentConfirmResultStatus paymentConfirmResultStatus = tossPaymentInfo.getPaymentConfirmResultStatus();
        if (paymentConfirmResultStatus == PaymentConfirmResultStatus.SUCCESS) {
            return tossPaymentInfo;
        } else if (paymentConfirmResultStatus == PaymentConfirmResultStatus.RETRYABLE_FAILURE) {
            throw PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);
        } else if (paymentConfirmResultStatus == PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE) {
            throw PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR);
        } else {
            throw new IllegalArgumentException();
        }
    }

    private PaymentEvent completePayment(
            PaymentEvent paymentEvent,
            TossPaymentInfo tossConfirmInfo
    ) {
        paymentEvent.paymentDone(tossConfirmInfo.getPaymentDetails().getApprovedAt());
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    private void handleNonRetryableFailure(PaymentEvent paymentEvent) {
        failPayment(paymentEvent);
        increaseStockPaymentOrderListProduct(paymentEvent.getPaymentOrderList());
    }

    private void increaseStockPaymentOrderListProduct(List<PaymentOrder> paymentOrderList) {
        paymentOrderList.forEach(paymentOrder ->
                productProvider.increaseStockWithCommit(
                        paymentOrder.getProductId(),
                        paymentOrder.getQuantity()
                )
        );
    }

    private void handleRetryableFailure(PaymentEvent paymentEvent) {
        paymentEvent.unknown(); // unknown 처리시 비동기로 재시도하게 됨
        paymentEventRepository.saveOrUpdate(paymentEvent);
    }
}
