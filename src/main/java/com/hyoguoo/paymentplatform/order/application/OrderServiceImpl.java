package com.hyoguoo.paymentplatform.order.application;

import com.hyoguoo.paymentplatform.core.common.service.port.UUIDProvider;
import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.order.presentation.port.OrderService;
import com.hyoguoo.paymentplatform.order.application.port.PaymentHandler;
import com.hyoguoo.paymentplatform.order.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.order.application.port.UserProvider;
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
import study.paymentintegrationserver.dto.order.OrderListResponse;
import study.paymentintegrationserver.dto.order.OrderProduct;
import study.paymentintegrationserver.dto.toss.TossCancelRequest;
import study.paymentintegrationserver.dto.toss.TossConfirmRequest;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final UUIDProvider uuidProvider;
    private final PaymentHandler paymentHandler;
    private final ProductProvider productProvider;
    private final UserProvider userProvider;
    private final OrderUseCase orderUseCase;

    @Transactional
    public OrderCreateResponse createOrder(OrderCreateRequest orderCreateRequest) {
        OrderProduct orderProduct = orderCreateRequest.getOrderProduct();

        OrderInfo orderInfo = orderCreateRequest.toDomain(
                userProvider.getUserInfoById(orderCreateRequest.getUserId()),
                productProvider.getProductInfoById(orderProduct.getProductId())
        );
        OrderInfo createdOrder = orderUseCase.saveOrUpdate(orderInfo);

        return new OrderCreateResponse(createdOrder);
    }

    public OrderConfirmResponse confirmOrder(OrderConfirmRequest orderConfirmRequest) {
        OrderInfo orderInfo = this.getOrderInfoInProgressStatus(orderConfirmRequest);

        productProvider.reduceStockWithCommit(
                orderInfo.getProductId(),
                orderInfo.getQuantity()
        );

        OrderInfo confirmedOrderInfo = this.confirmPaymentAndOrderInfoWithStockRollback(
                orderConfirmRequest,
                orderInfo
        );

        return new OrderConfirmResponse(confirmedOrderInfo);
    }

    private OrderInfo getOrderInfoInProgressStatus(OrderConfirmRequest orderConfirmRequest) {
        OrderInfo orderInfo = orderUseCase.getOrderInfoByOrderId(orderConfirmRequest.getOrderId());

        TossPaymentInfo paymentInfo = paymentHandler.getPaymentInfoByOrderId(
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
            TossPaymentInfo confirmPaymentResponse = paymentHandler.confirmPayment(
                    TossConfirmRequest.createByOrderConfirmRequest(orderConfirmRequest),
                    uuidProvider.generateUUID()
            );

            return orderUseCase.confirmOrderInfo(
                    orderInfo.getId(),
                    orderConfirmRequest,
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
        // TODO: Implement this method
        return null;
    }

    @Transactional
    public OrderCancelResponse cancelOrder(OrderCancelRequest orderCancelRequest) {
        OrderInfo orderInfo = orderUseCase.getOrderInfoByOrderId(orderCancelRequest.getOrderId());

        orderInfo.cancelOrder(
                paymentHandler.cancelPayment(
                        orderInfo.getPaymentKey(),
                        uuidProvider.generateUUID(),
                        TossCancelRequest.createByOrderCancelRequest(orderCancelRequest)
                )
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
