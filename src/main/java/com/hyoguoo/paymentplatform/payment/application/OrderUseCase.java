package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderConfirmInfo;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderListResponse;

@Service
@RequiredArgsConstructor
public class OrderUseCase {

    private final PaymentOrderRepository paymentOrderRepository;

    @Transactional(readOnly = true)
    public OrderListResponse findOrderList(int page, int size) {
        return new OrderListResponse(
                paymentOrderRepository.findAll(
                        PageRequest.of(page, size, Sort.by("id").descending())
                )
        );
    }

    @Transactional
    public PaymentOrder confirmOrderInfo(
            Long id,
            OrderConfirmInfo orderConfirmInfo,
            TossPaymentInfo tossPaymentInfo,
            UserInfo userInfo,
            ProductInfo productInfo
    ) {
        return this.getOrderInfoById(id)
                .confirmOrder(
                        tossPaymentInfo,
                        orderConfirmInfo,
                        userInfo,
                        productInfo
                );
    }

    public PaymentOrder saveOrUpdate(PaymentOrder paymentOrder) {
        return paymentOrderRepository.saveOrUpdate(paymentOrder);
    }

    public PaymentOrder getOrderInfoById(Long id) {
        return this.paymentOrderRepository.findById(id)
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.ORDER_NOT_FOUND));
    }

    public PaymentOrder getOrderInfoByOrderId(String orderId) {
        return paymentOrderRepository.findByOrderId(orderId)
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.ORDER_NOT_FOUND));
    }
}
