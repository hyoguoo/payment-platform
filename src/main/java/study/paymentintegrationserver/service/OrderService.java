package study.paymentintegrationserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;
import study.paymentintegrationserver.dto.order.*;
import study.paymentintegrationserver.dto.toss.TossConfirmRequest;
import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.exception.OrderInfoErrorMessage;
import study.paymentintegrationserver.exception.OrderInfoException;
import study.paymentintegrationserver.repository.OrderInfoRepository;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final String ORDER_ID_PREFIX = "ORDER-";
    private final PaymentService paymentService;
    private final ProductService productService;
    private final UserService userService;
    private final OrderInfoRepository orderInfoRepository;

    private static String generateOrderId() {
        return ORDER_ID_PREFIX + System.currentTimeMillis();
    }

    @Transactional
    public OrderFindDetailResponse getOrderDetailsByIdAndUpdatePaymentInfo(Long id) {
        OrderInfo orderInfo = getOrderInfoById(id);

        Optional<TossPaymentResponse> paymentInfo = paymentService.findPaymentInfoByOrderId(orderInfo.getOrderId());

        OrderFindDetailResponse orderFindDetailResponse = new OrderFindDetailResponse(orderInfo);
        paymentInfo.ifPresent(payments -> {
            orderFindDetailResponse.addTossPayments(payments);
            orderInfo.updatePaymentInfo(payments);
        });

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
                        productService.getById(orderProduct.getProductId()),
                        generateOrderId()
                ));

        return new OrderCreateResponse(createdOrder);
    }

    @Transactional
    public OrderConfirmResponse confirmOrder(OrderConfirmRequest orderConfirmRequest) {
        OrderInfo orderInfo = this.getOrderInfoByOrderId(orderConfirmRequest.getOrderId());
        TossPaymentResponse paymentInfo = paymentService.getPaymentInfoByOrderId(orderConfirmRequest.getOrderId());

        orderInfo.validateOrderInfo(paymentInfo, orderConfirmRequest);

        OrderInfo confirmedOrderInfo = orderInfo.confirmOrder(
                paymentService.confirmPayment(TossConfirmRequest.createByOrderConfirmRequest(orderConfirmRequest)),
                orderConfirmRequest
        );
        productService.reduceStock(confirmedOrderInfo.getProduct().getId(), confirmedOrderInfo.getQuantity());

        return new OrderConfirmResponse(confirmedOrderInfo);
    }

    private OrderInfo getOrderInfoById(Long id) {
        return this.orderInfoRepository.findById(id)
                .orElseThrow(() -> OrderInfoException.of(OrderInfoErrorMessage.NOT_FOUND));
    }

    private OrderInfo getOrderInfoByOrderId(String orderId) {
        return orderInfoRepository.findByOrderId(orderId)
                .orElseThrow(() -> OrderInfoException.of(OrderInfoErrorMessage.NOT_FOUND));
    }
}
