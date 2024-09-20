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
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

        validatePaymentEvent(paymentConfirmCommand, paymentEvent);

        handleProductStockReduction(paymentEvent);

        TossPaymentInfo tossConfirmInfo = confirmPaymentWithGateway(paymentConfirmCommand);

        PaymentEvent completedPayment = completePayment(paymentEvent, tossConfirmInfo);

        return PaymentConfirmResult.builder()
                .amount(completedPayment.getTotalAmount())
                .orderId(completedPayment.getOrderId())
                .build();
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
            paymentEvent.fail();
            paymentEventRepository.saveOrUpdate(paymentEvent);
        }
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

    private TossPaymentInfo confirmPaymentWithGateway(PaymentConfirmCommand paymentConfirmCommand) {
        TossConfirmGatewayCommand tossConfirmGatewayCommand = TossConfirmGatewayCommand.builder()
                .orderId(paymentConfirmCommand.getOrderId())
                .paymentKey(paymentConfirmCommand.getPaymentKey())
                .amount(paymentConfirmCommand.getAmount())
                .idempotencyKey(paymentConfirmCommand.getOrderId())
                .build();

        return paymentGatewayHandler.confirmPayment(
                tossConfirmGatewayCommand
        );
    }

    private PaymentEvent completePayment(
            PaymentEvent paymentEvent,
            TossPaymentInfo tossConfirmInfo
    ) {
        paymentEvent.paymentDone(tossConfirmInfo.getPaymentDetails().getApprovedAt());
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }
}
