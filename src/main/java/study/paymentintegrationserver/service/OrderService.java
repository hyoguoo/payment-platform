package study.paymentintegrationserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.order.OrderFindResponse;
import study.paymentintegrationserver.dto.order.OrderListResponse;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;
import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.exception.OrderInfoErrorMessage;
import study.paymentintegrationserver.exception.OrderInfoException;
import study.paymentintegrationserver.repository.OrderInfoRepository;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderInfoRepository orderInfoRepository;

    @Transactional(readOnly = true)
    public OrderListResponse findOrderList(int page, int size) {
        return new OrderListResponse(
                orderInfoRepository.findAllWithProductAndUser(
                        PageRequest.of(page, size, Sort.by("id").descending())
                )
        );
    }

    @Transactional(readOnly = true)
    public Slice<OrderFindResponse> findOrderListWithCursor(int size, Long cursor) {
        Slice<OrderInfo> allWithProductAndUserWithCursor = orderInfoRepository
                .findAllWithProductAndUserWithCursor(
                        PageRequest.of(0, size, Sort.by("id").descending()),
                        cursor
                );

        return allWithProductAndUserWithCursor
                .map(OrderFindResponse::new);
    }

    public OrderInfo saveOrderInfo(OrderInfo orderInfo) {
        return orderInfoRepository.save(orderInfo);
    }

    @Transactional
    public OrderInfo confirmOrderInfo(
            Long id,
            OrderConfirmRequest orderConfirmRequest,
            TossPaymentResponse confirmPaymentResponse
    ) {
        return this.getOrderInfoById(id)
                .confirmOrder(
                        confirmPaymentResponse,
                        orderConfirmRequest
                );
    }

    public OrderInfo getOrderInfoById(Long id) {
        return this.orderInfoRepository.findByIdWithProductAndUser(id)
                .orElseThrow(() -> OrderInfoException.of(OrderInfoErrorMessage.NOT_FOUND));
    }

    public OrderInfo getOrderInfoByOrderId(String orderId) {
        return orderInfoRepository.findByOrderIdWithProductAndUser(orderId)
                .orElseThrow(() -> OrderInfoException.of(OrderInfoErrorMessage.NOT_FOUND));
    }
}
