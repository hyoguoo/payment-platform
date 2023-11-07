package study.paymentintegrationserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import study.paymentintegrationserver.dto.order.*;
import study.paymentintegrationserver.dto.toss.TossCancelRequest;
import study.paymentintegrationserver.dto.toss.TossConfirmRequest;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;
import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.exception.OrderInfoErrorMessage;
import study.paymentintegrationserver.exception.OrderInfoException;
import study.paymentintegrationserver.repository.OrderInfoRepository;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final PaymentService paymentService;
    private final ProductService productService;
    private final UserService userService;
    private final OrderInfoRepository orderInfoRepository;

    @Transactional
    public OrderFindDetailResponse getOrderDetailsByIdAndUpdatePaymentInfo(Long id) {
        OrderInfo orderInfo = getOrderInfoById(id);

        paymentService
                .findPaymentInfoByOrderId(orderInfo.getOrderId())
                .ifPresent(orderInfo::updatePaymentInfo);

        return new OrderFindDetailResponse(orderInfo);
    }

    public Page<OrderFindResponse> findOrderList(Pageable pageable) {
        return orderInfoRepository.findAll(pageable)
                .map(OrderFindResponse::new);
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

    @Transactional
    public OrderCancelResponse cancelOrder(OrderCancelRequest orderCancelRequest) {
        OrderInfo orderInfo = this.getOrderInfoByOrderId(orderCancelRequest.getOrderId());

        orderInfo.cancelOrder(paymentService.cancelPayment(
                orderInfo.getPaymentKey(),
                TossCancelRequest.createByOrderCancelRequest(orderCancelRequest)
        ));

        productService.increaseStock(orderInfo.getProduct().getId(), orderInfo.getQuantity());

        return new OrderCancelResponse(orderInfo);
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
