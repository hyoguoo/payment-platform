package com.hyoguoo.paymentplatform.order.application;

import com.hyoguoo.paymentplatform.core.common.service.port.UUIDProvider;
import com.hyoguoo.paymentplatform.order.application.dto.request.OrderCancelInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.OrderConfirmInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.OrderCreateInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.TossCancelInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.TossConfirmInfo;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderCancelResponse;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderConfirmResponse;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderCreateResponse;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderFindDetailResponse;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderListResponse;
import com.hyoguoo.paymentplatform.order.application.dto.vo.OrderProduct;
import com.hyoguoo.paymentplatform.order.application.port.PaymentHandler;
import com.hyoguoo.paymentplatform.order.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.order.application.port.UserProvider;
import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.order.presentation.port.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final UUIDProvider uuidProvider;
    private final PaymentHandler paymentHandler;
    private final ProductProvider productProvider;
    private final UserProvider userProvider;
    private final OrderUseCase orderUseCase;

    @Transactional
    public OrderCreateResponse createOrder(OrderCreateInfo orderCreateInfo) {
        OrderProduct orderProduct = orderCreateInfo.getOrderProduct();

        OrderInfo orderInfo = orderCreateInfo.toDomain(
                userProvider.getUserInfoById(orderCreateInfo.getUserId()),
                productProvider.getProductInfoById(orderProduct.getProductId())
        );
        OrderInfo createdOrder = orderUseCase.saveOrUpdate(orderInfo);

        return new OrderCreateResponse(createdOrder);
    }

    @Transactional
    public OrderConfirmResponse confirmOrder(OrderConfirmInfo orderConfirmInfo) {
        OrderInfo orderInfo = this.getOrderInfoInProgressStatus(orderConfirmInfo);

        productProvider.reduceStockWithCommit(
                orderInfo.getProductId(),
                orderInfo.getQuantity()
        );

        OrderInfo confirmedOrderInfo = this.confirmPaymentAndOrderInfoWithStockRollback(
                orderConfirmInfo,
                orderInfo
        );

        orderUseCase.saveOrUpdate(confirmedOrderInfo);

        return new OrderConfirmResponse(confirmedOrderInfo);
    }

    private OrderInfo getOrderInfoInProgressStatus(OrderConfirmInfo orderConfirmInfo) {
        OrderInfo orderInfo = orderUseCase.getOrderInfoByOrderId(orderConfirmInfo.getOrderId());

        TossPaymentInfo paymentInfo = paymentHandler.getPaymentInfoByOrderId(
                orderConfirmInfo.getOrderId()
        );

        orderInfo.validateInProgressOrder(paymentInfo, orderConfirmInfo);

        return orderInfo;
    }


    private OrderInfo confirmPaymentAndOrderInfoWithStockRollback(
            OrderConfirmInfo orderConfirmInfo,
            OrderInfo orderInfo
    ) {
        try {
            TossConfirmInfo tossConfirmInfo = TossConfirmInfo.builder()
                    .orderId(orderConfirmInfo.getOrderId())
                    .amount(orderConfirmInfo.getAmount())
                    .paymentKey(orderConfirmInfo.getPaymentKey())
                    .idempotencyKey(uuidProvider.generateUUID())
                    .build();

            TossPaymentInfo confirmPaymentResponse = paymentHandler.confirmPayment(
                    tossConfirmInfo
            );

            return orderUseCase.confirmOrderInfo(
                    orderInfo.getId(),
                    orderConfirmInfo,
                    confirmPaymentResponse
            );
        } catch (Exception e) {
            productProvider.increaseStockWithCommit(
                    orderInfo.getProductId(),
                    orderInfo.getQuantity()
            );

            throw e;
        }
    }

    @Override
    public OrderListResponse findOrderList(int page, int size) {
        return orderUseCase.findOrderList(page, size);
    }

    @Transactional
    public OrderCancelResponse cancelOrder(OrderCancelInfo orderCancelInfo) {
        OrderInfo orderInfo = orderUseCase.getOrderInfoByOrderId(orderCancelInfo.getOrderId());

        TossCancelInfo tossCancelInfo = TossCancelInfo.builder()
                .cancelReason(orderCancelInfo.getCancelReason())
                .paymentKey(orderInfo.getPaymentKey())
                .idempotencyKey(uuidProvider.generateUUID())
                .build();

        orderInfo.cancelOrder(
                paymentHandler.cancelPayment(tossCancelInfo)
        );

        productProvider.increaseStockWithCommit(
                orderInfo.getProductId(),
                orderInfo.getQuantity()
        );

        return new OrderCancelResponse(orderInfo);
    }

    @Transactional
    public OrderFindDetailResponse getOrderDetailsByIdAndUpdatePaymentInfo(Long id) {
        OrderInfo orderInfo = orderUseCase.getOrderInfoById(id);

        paymentHandler
                .findPaymentInfoByOrderId(orderInfo.getOrderId())
                .ifPresent(orderInfo::updatePaymentInfo);

        return new OrderFindDetailResponse(orderInfo);
    }
}
