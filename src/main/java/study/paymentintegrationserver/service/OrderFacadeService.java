package study.paymentintegrationserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import study.paymentintegrationserver.dto.order.OrderCancelRequest;
import study.paymentintegrationserver.dto.order.OrderCancelResponse;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.order.OrderConfirmResponse;
import study.paymentintegrationserver.dto.order.OrderCreateRequest;
import study.paymentintegrationserver.dto.order.OrderCreateResponse;
import study.paymentintegrationserver.dto.order.OrderFindDetailResponse;
import study.paymentintegrationserver.dto.order.OrderProduct;
import study.paymentintegrationserver.dto.toss.TossCancelRequest;
import study.paymentintegrationserver.dto.toss.TossConfirmRequest;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;
import study.paymentintegrationserver.entity.OrderInfo;

@Service
@RequiredArgsConstructor
public class OrderFacadeService {

    private final PaymentService paymentService;
    private final ProductService productService;
    private final UserService userService;
    private final OrderService orderService;

    @Transactional
    public OrderCreateResponse createOrder(OrderCreateRequest orderCreateRequest) {
        OrderProduct orderProduct = orderCreateRequest.getOrderProduct();

        OrderInfo createdOrder = orderService.saveOrderInfo(
                orderCreateRequest.toEntity(
                        userService.getById(orderCreateRequest.getUserId()),
                        productService.getById(orderProduct.getProductId())
                )
        );

        return new OrderCreateResponse(createdOrder);
    }

    public OrderConfirmResponse confirmOrder(OrderConfirmRequest orderConfirmRequest) {
        OrderInfo orderInfo = this.getOrderInfoInProgressStatus(orderConfirmRequest);

        productService.reduceStockWithCommit(
                orderInfo.getProduct().getId(),
                orderInfo.getQuantity()
        );

        OrderInfo confirmedOrderInfo = this.confirmPaymentAndOrderInfoWithStockRollback(
                orderConfirmRequest,
                orderInfo
        );

        return new OrderConfirmResponse(confirmedOrderInfo);
    }

    private OrderInfo getOrderInfoInProgressStatus(OrderConfirmRequest orderConfirmRequest) {
        OrderInfo orderInfo = orderService.getOrderInfoByOrderId(orderConfirmRequest.getOrderId());

        TossPaymentResponse paymentInfo = paymentService.getPaymentInfoByOrderId(
                orderConfirmRequest.getOrderId()
        );

        orderInfo.validateInProgressOrder(paymentInfo, orderConfirmRequest);

        return orderInfo;
    }


    private OrderInfo confirmPaymentAndOrderInfoWithStockRollback(
            OrderConfirmRequest orderConfirmRequest,
            OrderInfo orderInfo
    ) {
        try {
            TossPaymentResponse confirmPaymentResponse = paymentService.confirmPayment(
                    TossConfirmRequest.createByOrderConfirmRequest(orderConfirmRequest)
            );

            return orderService.confirmOrderInfo(
                    orderInfo.getId(),
                    orderConfirmRequest,
                    confirmPaymentResponse
            );
        } catch (Exception e) {
            productService.increaseStockWithCommit(
                    orderInfo.getProduct().getId(),
                    orderInfo.getQuantity()
            );

            throw e;
        }
    }

    @Transactional
    public OrderCancelResponse cancelOrder(OrderCancelRequest orderCancelRequest) {
        OrderInfo orderInfo = orderService.getOrderInfoByOrderId(orderCancelRequest.getOrderId());

        orderInfo.cancelOrder(
                paymentService.cancelPayment(
                        orderInfo.getPaymentKey(),
                        TossCancelRequest.createByOrderCancelRequest(orderCancelRequest)
                )
        );

        productService.increaseStockWithCommit(
                orderInfo.getProduct().getId(),
                orderInfo.getQuantity()
        );

        return new OrderCancelResponse(orderInfo);
    }

    @Transactional
    public OrderFindDetailResponse getOrderDetailsByIdAndUpdatePaymentInfo(Long id) {
        OrderInfo orderInfo = orderService.getOrderInfoById(id);

        paymentService
                .findPaymentInfoByOrderId(orderInfo.getOrderId())
                .ifPresent(orderInfo::updatePaymentInfo);

        return new OrderFindDetailResponse(orderInfo);
    }
}
