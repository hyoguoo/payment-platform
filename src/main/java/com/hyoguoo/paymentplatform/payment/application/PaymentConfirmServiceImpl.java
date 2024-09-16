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
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import java.math.BigDecimal;
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

    private static BigDecimal calculateTotalAmount(List<PaymentOrder> paymentOrderList) {
        return paymentOrderList.stream()
                .map(PaymentOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public PaymentConfirmResult confirm(PaymentConfirmCommand paymentConfirmCommand) {
        // ========= 주문 실행 시작 =========
        PaymentEvent paymentEvent = getPaymentEventByOrderId(
                paymentConfirmCommand.getOrderId()
        );
        List<PaymentOrder> paymentOrderList = getPaymentOrderListByPaymentEventId(
                paymentEvent.getId()
        );

        paymentEvent.execute(paymentConfirmCommand.getPaymentKey());
        paymentEventRepository.saveOrUpdate(paymentEvent);

        changeExecutingStatus(paymentOrderList);
        // ========= 주문 실행 종료 =========

        // ========= 검증 시작 =========
        TossPaymentInfo tossPaymentInfo = paymentGatewayHandler.getPaymentInfoByOrderId(
                paymentConfirmCommand.getOrderId()
        );
        paymentEventRepository.saveOrUpdate(paymentEvent);

        paymentEvent.validate(paymentConfirmCommand, tossPaymentInfo);
        BigDecimal totalAmount = calculateTotalAmount(paymentOrderList);

        if (paymentConfirmCommand.getAmount().compareTo(totalAmount) != 0) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_TOTAL_AMOUNT);
        }
        // ========= 검증 종료 =========

        // ========= 재고 감소 시작 =========
        if (!reduceStockPaymentOrderListProduct(paymentOrderList)) {
            paymentEvent.fail();
            paymentOrderList.forEach(paymentOrder -> {
                paymentOrder.fail();
                paymentOrderRepository.saveOrUpdate(paymentOrder);
            });
        }
        // ========= 재고 감소 종료 =========

        // ========= 결제 승인 시작 =========
        TossConfirmGatewayCommand tossConfirmGatewayCommand = TossConfirmGatewayCommand.builder()
                .orderId(paymentConfirmCommand.getOrderId())
                .paymentKey(paymentConfirmCommand.getPaymentKey())
                .amount(paymentConfirmCommand.getAmount())
                .idempotencyKey(paymentConfirmCommand.getOrderId())
                .build();
        TossPaymentInfo tossConfirmInfo = paymentGatewayHandler.confirmPayment(
                tossConfirmGatewayCommand
        );
        // ========= 결제 승인 종료 =========

        // ========= 주문 확정 상태 변경 시작 =========
        paymentEvent.paymentDone();
        paymentEventRepository.saveOrUpdate(paymentEvent);
        paymentOrderList.forEach(PaymentOrder::paymentDone);
        paymentOrderRepository.saveAll(paymentOrderList);
        // ========= 주문 확정 상태 변경 종료 =========

        return PaymentConfirmResult.builder()
                .amount(tossConfirmInfo.getPaymentDetails().getTotalAmount())
                .orderId(tossConfirmInfo.getOrderId())
                .build();
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

    private void changeExecutingStatus(List<PaymentOrder> paymentOrderList) {
        paymentOrderList.forEach(PaymentOrder::execute);
        paymentOrderRepository.saveAll(paymentOrderList);
    }

    private PaymentEvent getPaymentEventByOrderId(String orderId) {
        return paymentEventRepository
                .findByOrderId(orderId)
                .orElseThrow(
                        () -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND)
                );
    }

    private List<PaymentOrder> getPaymentOrderListByPaymentEventId(Long paymentEventId) {
        return paymentOrderRepository.findByPaymentEventId(paymentEventId);
    }
}
