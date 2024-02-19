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

    // TODO: FACADE 패턴 적용
    public OrderConfirmResponse confirmOrder(OrderConfirmRequest orderConfirmRequest) {
        OrderInfo orderInfo = getOrderInfoInProgressStatus(orderConfirmRequest);

        productService.reduceStockWithCommit(
                orderInfo.getProduct().getId(),
                orderInfo.getQuantity()
        );

        TossPaymentResponse confirmPaymentResponse = confirmPaymentWithStockRollback(
                orderConfirmRequest,
                orderInfo
        );

        OrderInfo confirmedOrderInfo = this.confirmOrderInfo(
                orderConfirmRequest,
                orderInfo,
                confirmPaymentResponse
        );

        return new OrderConfirmResponse(confirmedOrderInfo);
    }

    private OrderInfo getOrderInfoInProgressStatus(OrderConfirmRequest orderConfirmRequest) {
        OrderInfo orderInfo = this.getOrderInfoByOrderId(orderConfirmRequest.getOrderId());

        TossPaymentResponse paymentInfo = paymentService.getPaymentInfoByOrderId(
                orderConfirmRequest.getOrderId()
        );

        orderInfo.validateInProgressOrder(paymentInfo, orderConfirmRequest);

        return orderInfo;
    }

    private TossPaymentResponse confirmPaymentWithStockRollback(
            OrderConfirmRequest orderConfirmRequest,
            OrderInfo orderInfo
    ) {
        try {
            return paymentService.confirmPayment(
                    TossConfirmRequest.createByOrderConfirmRequest(orderConfirmRequest)
            );
        } catch (Exception e) {
            productService.increaseStockWithCommit(
                    orderInfo.getProduct().getId(),
                    orderInfo.getQuantity()
            );

            throw e;
        }
    }

    private OrderInfo confirmOrderInfo(
            OrderConfirmRequest orderConfirmRequest,
            OrderInfo orderInfo,
            TossPaymentResponse confirmPaymentResponse
    ) {
        OrderInfo confirmedOrderInfo = orderInfo.confirmOrder(
                confirmPaymentResponse,
                orderConfirmRequest
        );

        orderInfoRepository.save(confirmedOrderInfo);

        return confirmedOrderInfo;
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
