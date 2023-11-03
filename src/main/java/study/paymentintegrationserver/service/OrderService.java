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

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final PaymentService paymentService;
    private final ProductService productService;
    private final UserService userService;
    private final OrderInfoRepository orderInfoRepository;

    public OrderFindDetailResponse getPaymentInfo(Long orderId) {
        OrderInfo orderInfo = this.orderInfoRepository.findById(orderId)
                .orElseThrow(() -> OrderInfoException.of(OrderInfoErrorMessage.NOT_FOUND));

        Optional<TossPayments> paymentInfo = paymentService.getPaymentInfoByOrderId(orderInfo.getOrderId());

        OrderFindDetailResponse orderFindDetailResponse = new OrderFindDetailResponse(orderInfo);
        paymentInfo.ifPresent(orderFindDetailResponse::addTossPayments);

        return orderFindDetailResponse;
    }

    public List<OrderFindResponse> findOrderList() {
        return orderInfoRepository.findAll().stream()
                .map(OrderFindResponse::new)
                .toList();
    }

    @Transactional
    public OrderCreateResponse createOrder(OrderCreateRequest orderCreateRequest) {
        OrderProduct orderProduct = orderCreateRequest.getOrderProduct();

        OrderInfo createdOrder = orderInfoRepository.save(
                orderCreateRequest.toEntity(
                        userService.getById(orderCreateRequest.getUserId()),
                        productService.getById(orderProduct.getProductId())
                ));

        return new OrderCreateResponse(createdOrder);
    }

    @Transactional
    public OrderConfirmResponse confirmOrder(OrderConfirmRequest orderConfirmRequest) {
        OrderInfo orderInfo = this.getOrderInfo(orderConfirmRequest.getOrderId());
        TossPayments tossPayments = paymentService.confirmPayment(orderConfirmRequest);

        OrderInfo confirmedOrderInfo = orderInfo.confirmOrder(tossPayments);
        return new OrderConfirmResponse(confirmedOrderInfo);
    }

    private OrderInfo getOrderInfo(String orderId) {
        return orderInfoRepository.findByOrderId(orderId)
                .orElseThrow(() -> OrderInfoException.of(OrderInfoErrorMessage.NOT_FOUND));
    }
}
