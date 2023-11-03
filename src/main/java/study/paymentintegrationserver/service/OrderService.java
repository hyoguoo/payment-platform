package study.paymentintegrationserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import study.paymentintegrationserver.domain.TossPayments;
import study.paymentintegrationserver.dto.order.OrderConfirmResponse;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.order.OrderCreateRequest;
import study.paymentintegrationserver.dto.order.OrderCreateResponse;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final PaymentService paymentService;

    public OrderCreateResponse createOrder(OrderCreateRequest orderCreateRequest) {
        return new OrderCreateResponse(orderCreateRequest.getOrderId());
    }

    public OrderConfirmResponse confirmOrder(OrderConfirmRequest orderConfirmRequest) {
        TossPayments tossPayments = paymentService.confirmPayment(orderConfirmRequest);
        return new OrderConfirmResponse(tossPayments.getOrderId(), BigDecimal.valueOf(tossPayments.getTotalAmount()));
    }
}
