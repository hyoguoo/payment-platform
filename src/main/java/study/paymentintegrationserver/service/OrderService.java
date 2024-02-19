package study.paymentintegrationserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import study.paymentintegrationserver.dto.order.OrderCancelRequest;
import study.paymentintegrationserver.dto.order.OrderCancelResponse;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.order.OrderConfirmResponse;
import study.paymentintegrationserver.dto.order.OrderCreateRequest;
import study.paymentintegrationserver.dto.order.OrderCreateResponse;
import study.paymentintegrationserver.dto.order.OrderFindDetailResponse;
import study.paymentintegrationserver.dto.order.OrderFindResponse;
import study.paymentintegrationserver.dto.order.OrderListResponse;
import study.paymentintegrationserver.dto.order.OrderProduct;
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

    @Transactional
    public OrderCreateResponse createOrder(OrderCreateRequest orderCreateRequest) {
        OrderProduct orderProduct = orderCreateRequest.getOrderProduct();

        OrderInfo createdOrder = orderInfoRepository.save(
                orderCreateRequest.toEntity(
                        userService.getById(orderCreateRequest.getUserId()),
                        productService.getById(orderProduct.getProductId())
                )
        );

        return new OrderCreateResponse(createdOrder);
    }

    public OrderConfirmResponse confirmOrder(OrderConfirmRequest orderConfirmRequest) {
        OrderInfo orderInfo = this.getOrderInfoByOrderId(orderConfirmRequest.getOrderId());
        productService.reduceStockWithCommit(
                orderInfo.getProduct().getId(),
                orderInfo.getQuantity()
        );

        TossPaymentResponse paymentInfo = paymentService.getPaymentInfoByOrderId(
                orderConfirmRequest.getOrderId()
        );
        orderInfo.validateInProgressOrder(paymentInfo, orderConfirmRequest);
        TossPaymentResponse confirmPaymentResponse = paymentService.confirmPayment(
                TossConfirmRequest.createByOrderConfirmRequest(orderConfirmRequest)
        );
        // TODO: API 요청 부분에서 실패 시 차감 된 재고 롤백 로직 추가

        OrderInfo confirmedOrderInfo = orderInfo.confirmOrder(
                confirmPaymentResponse,
                orderConfirmRequest
        );
        orderInfoRepository.save(confirmedOrderInfo); // TODO: 별도의 @Transactional 메서드로 분리

        return new OrderConfirmResponse(confirmedOrderInfo);
    }

    @Transactional
    public OrderCancelResponse cancelOrder(OrderCancelRequest orderCancelRequest) {
        OrderInfo orderInfo = this.getOrderInfoByOrderId(orderCancelRequest.getOrderId());

        orderInfo.cancelOrder(paymentService.cancelPayment(
                orderInfo.getPaymentKey(),
                TossCancelRequest.createByOrderCancelRequest(orderCancelRequest)
        ));

        productService.increaseStockWithCommit(orderInfo.getProduct().getId(),
                orderInfo.getQuantity());

        return new OrderCancelResponse(orderInfo);
    }

    private OrderInfo getOrderInfoById(Long id) {
        return this.orderInfoRepository.findByIdWithProductAndUser(id)
                .orElseThrow(() -> OrderInfoException.of(OrderInfoErrorMessage.NOT_FOUND));
    }

    private OrderInfo getOrderInfoByOrderId(String orderId) {
        return orderInfoRepository.findByOrderIdWithProductAndUser(orderId)
                .orElseThrow(() -> OrderInfoException.of(OrderInfoErrorMessage.NOT_FOUND));
    }

    private OrderInfo getOrderInfoByOrderPessimisticLock(String orderId) {
        return orderInfoRepository.findByOrderIdPessimisticLock(orderId)
                .orElseThrow(() -> OrderInfoException.of(OrderInfoErrorMessage.NOT_FOUND));
    }
}
