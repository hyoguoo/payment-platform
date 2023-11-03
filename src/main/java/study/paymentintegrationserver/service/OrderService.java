package study.paymentintegrationserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import study.paymentintegrationserver.domain.TossPayments;
import study.paymentintegrationserver.dto.order.*;
import study.paymentintegrationserver.repository.OrderInfoRepository;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final PaymentService paymentService;
    private final ProductService productService;
    private final UserService userService;
    private final OrderInfoRepository orderInfoRepository;

    public OrderCreateResponse createOrder(OrderCreateRequest orderCreateRequest) {
        OrderProduct orderProduct = orderCreateRequest.getOrderProduct();

        orderInfoRepository.save(
                orderCreateRequest.toEntity(
                        userService.getById(orderCreateRequest.getUserId()),
                        productService.getById(orderProduct.getProductId())
                ));

        return new OrderCreateResponse(orderCreateRequest.getOrderId());
    }

    public OrderConfirmResponse confirmOrder(OrderConfirmRequest orderConfirmRequest) {
        TossPayments tossPayments = paymentService.confirmPayment(orderConfirmRequest);
        return new OrderConfirmResponse(tossPayments.getOrderId(), BigDecimal.valueOf(tossPayments.getTotalAmount()));
    }
}
