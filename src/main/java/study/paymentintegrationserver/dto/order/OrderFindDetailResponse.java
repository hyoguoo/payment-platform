package study.paymentintegrationserver.dto.order;

import lombok.Getter;
import study.paymentintegrationserver.domain.TossPayments;
import study.paymentintegrationserver.entity.OrderInfo;

import java.math.BigDecimal;
import java.util.List;

@Getter
public class OrderFindDetailResponse {

    private final Long id;
    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
    private final String requestedAt;
    private final String approvedAt;
    private final String status;
    private final String productName;
    private final String userName;
    private final String lastTransactionKey;
    private final String method;
    private final List<TossPayments.Cancel> cancels;

    public OrderFindDetailResponse(OrderInfo orderInfo, TossPayments tossPayments) {
        this.id = orderInfo.getId();
        this.orderId = orderInfo.getOrderId();
        this.amount = orderInfo.getTotalAmount();
        this.paymentKey = orderInfo.getPaymentKey();
        this.requestedAt = orderInfo.getRequestedAt();
        this.approvedAt = orderInfo.getApprovedAt();
        this.status = orderInfo.getStatus();
        this.productName = orderInfo.getProduct().getName();
        this.userName = orderInfo.getUser().getUsername();
        this.lastTransactionKey = tossPayments.getLastTransactionKey();
        this.method = tossPayments.getMethod();
        this.cancels = tossPayments.getCancels();
    }
}
