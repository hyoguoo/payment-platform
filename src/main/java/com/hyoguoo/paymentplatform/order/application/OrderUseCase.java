package com.hyoguoo.paymentplatform.order.application;

import com.hyoguoo.paymentplatform.order.application.dto.request.OrderConfirmInfo;
import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.order.exception.OrderFoundException;
import com.hyoguoo.paymentplatform.order.exception.common.OrderErrorCode;
import com.hyoguoo.paymentplatform.order.application.port.OrderInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderListResponse;

@Service
@RequiredArgsConstructor
public class OrderUseCase {

    private final OrderInfoRepository orderInfoRepository;

    @Transactional(readOnly = true)
    public OrderListResponse findOrderList(int page, int size) {
        return new OrderListResponse(
                orderInfoRepository.findAll(
                        PageRequest.of(page, size, Sort.by("id").descending())
                )
        );
    }

    @Transactional
    public OrderInfo confirmOrderInfo(
            Long id,
            OrderConfirmInfo orderConfirmInfo,
            TossPaymentInfo tossPaymentInfo,
            UserInfo userInfo,
            ProductInfo productInfo
    ) {
        return this.getOrderInfoById(id)
                .confirmOrder(
                        tossPaymentInfo,
                        orderConfirmInfo,
                        userInfo,
                        productInfo
                );
    }

    public OrderInfo saveOrUpdate(OrderInfo orderInfo) {
        return orderInfoRepository.saveOrUpdate(orderInfo);
    }

    public OrderInfo getOrderInfoById(Long id) {
        return this.orderInfoRepository.findById(id)
                .orElseThrow(() -> OrderFoundException.of(OrderErrorCode.ORDER_NOT_FOUND));
    }

    public OrderInfo getOrderInfoByOrderId(String orderId) {
        return orderInfoRepository.findByOrderId(orderId)
                .orElseThrow(() -> OrderFoundException.of(OrderErrorCode.ORDER_NOT_FOUND));
    }
}
