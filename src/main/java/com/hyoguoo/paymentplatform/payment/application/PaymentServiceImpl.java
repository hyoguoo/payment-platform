package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.service.port.UUIDProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderCancelInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderConfirmInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderCreateInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderCancelResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderConfirmResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderCreateResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderFindDetailResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderListResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderProduct;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayHandler;
import com.hyoguoo.paymentplatform.payment.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.payment.application.port.UserProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final UUIDProvider uuidProvider;
    private final PaymentGatewayHandler paymentGatewayHandler;
    private final ProductProvider productProvider;
    private final UserProvider userProvider;
    private final OrderUseCase orderUseCase;

    @Transactional
    public OrderCreateResponse createOrder(OrderCreateInfo orderCreateInfo) {
        OrderProduct orderProduct = orderCreateInfo.getOrderProduct();

        PaymentOrder paymentOrder = orderCreateInfo.toDomain(
                userProvider.getUserInfoById(orderCreateInfo.getUserId()),
                productProvider.getProductInfoById(orderProduct.getProductId())
        );
        PaymentOrder createdOrder = orderUseCase.saveOrUpdate(paymentOrder);

        return new OrderCreateResponse(createdOrder);
    }

    @Transactional
    public OrderConfirmResponse confirmOrder(OrderConfirmInfo orderConfirmInfo) {
        UserInfo userInfo = userProvider.getUserInfoById(orderConfirmInfo.getUserId());
        PaymentOrder paymentOrder = this.getOrderInfoInProgressStatus(orderConfirmInfo, userInfo);

        productProvider.reduceStockWithCommit(
                paymentOrder.getProductId(),
                paymentOrder.getQuantity()
        );

        PaymentOrder confirmedPaymentOrder = this.confirmPaymentAndOrderInfoWithStockRollback(
                orderConfirmInfo,
                paymentOrder,
                userInfo,
                productProvider.getProductInfoById(paymentOrder.getProductId())
        );

        orderUseCase.saveOrUpdate(confirmedPaymentOrder);

        return new OrderConfirmResponse(confirmedPaymentOrder);
    }

    private PaymentOrder getOrderInfoInProgressStatus(
            OrderConfirmInfo orderConfirmInfo,
            UserInfo userInfo
    ) {
        PaymentOrder paymentOrder = orderUseCase.getOrderInfoByOrderId(orderConfirmInfo.getOrderId());
        ProductInfo productInfo = productProvider.getProductInfoById(paymentOrder.getProductId());

        TossPaymentInfo paymentInfo = paymentGatewayHandler.getPaymentInfoByOrderId(
                orderConfirmInfo.getOrderId()
        );

        paymentOrder.validateInProgressOrder(paymentInfo, orderConfirmInfo, userInfo, productInfo);

        return paymentOrder;
    }


    private PaymentOrder confirmPaymentAndOrderInfoWithStockRollback(
            OrderConfirmInfo orderConfirmInfo,
            PaymentOrder paymentOrder,
            UserInfo userInfo,
            ProductInfo productInfo
    ) {
        try {
            TossConfirmInfo tossConfirmInfo = TossConfirmInfo.builder()
                    .orderId(orderConfirmInfo.getOrderId())
                    .amount(orderConfirmInfo.getAmount())
                    .paymentKey(orderConfirmInfo.getPaymentKey())
                    .idempotencyKey(uuidProvider.generateUUID())
                    .build();

            TossPaymentInfo confirmPaymentResponse = paymentGatewayHandler.confirmPayment(
                    tossConfirmInfo
            );

            return orderUseCase.confirmOrderInfo(
                    paymentOrder.getId(),
                    orderConfirmInfo,
                    confirmPaymentResponse,
                    userInfo,
                    productInfo
            );
        } catch (Exception e) {
            productProvider.increaseStockWithCommit(
                    paymentOrder.getProductId(),
                    paymentOrder.getQuantity()
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
        PaymentOrder paymentOrder = orderUseCase.getOrderInfoByOrderId(orderCancelInfo.getOrderId());

        TossCancelInfo tossCancelInfo = TossCancelInfo.builder()
                .cancelReason(orderCancelInfo.getCancelReason())
                .paymentKey(paymentOrder.getPaymentKey())
                .idempotencyKey(uuidProvider.generateUUID())
                .build();

        paymentOrder.cancelOrder(
                paymentGatewayHandler.cancelPayment(tossCancelInfo)
        );

        productProvider.increaseStockWithCommit(
                paymentOrder.getProductId(),
                paymentOrder.getQuantity()
        );

        return new OrderCancelResponse(paymentOrder);
    }

    @Transactional
    public OrderFindDetailResponse getOrderDetailsByIdAndUpdatePaymentInfo(Long id) {
        PaymentOrder paymentOrder = orderUseCase.getOrderInfoById(id);

        paymentGatewayHandler
                .findPaymentInfoByOrderId(paymentOrder.getOrderId())
                .ifPresent(paymentOrder::updatePaymentInfo);

        return new OrderFindDetailResponse(paymentOrder);
    }
}
