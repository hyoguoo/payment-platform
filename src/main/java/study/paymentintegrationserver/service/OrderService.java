package study.paymentintegrationserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import study.paymentintegrationserver.domain.TossPayments;
import study.paymentintegrationserver.dto.order.*;
import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.exception.OrderInfoErrorMessage;
import study.paymentintegrationserver.exception.OrderInfoException;
import study.paymentintegrationserver.repository.OrderInfoRepository;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final PaymentService paymentService;
    private final ProductService productService;
    private final UserService userService;
    private final OrderInfoRepository orderInfoRepository;

    @Transactional
    public OrderCreateResponse createOrder(OrderCreateRequest orderCreateRequest) {
        OrderProduct orderProduct = orderCreateRequest.getOrderProduct();

        orderInfoRepository.save(
                orderCreateRequest.toEntity(
                        userService.getById(orderCreateRequest.getUserId()),
                        productService.getById(orderProduct.getProductId())
                ));

        return new OrderCreateResponse(orderCreateRequest.getOrderId());
    }

    @Transactional
    public OrderConfirmResponse confirmOrder(OrderConfirmRequest orderConfirmRequest) {
        OrderInfo orderInfo = this.getOrderInfo(orderConfirmRequest.getOrderId());
        TossPayments tossPayments = paymentService.confirmPayment(orderConfirmRequest);
        orderInfo.confirmOrder(tossPayments);
        return new OrderConfirmResponse(tossPayments.getOrderId(), BigDecimal.valueOf(tossPayments.getTotalAmount()));
    }

    private OrderInfo getOrderInfo(String orderId) {
        return orderInfoRepository.findByOrderId(orderId)
                .orElseThrow(() -> OrderInfoException.of(OrderInfoErrorMessage.NOT_FOUND));
    }
}
