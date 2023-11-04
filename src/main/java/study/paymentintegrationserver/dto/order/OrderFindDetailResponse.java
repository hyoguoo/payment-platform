package study.paymentintegrationserver.dto.order;

import lombok.Getter;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;
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
    private String lastTransactionKey;
    private String method;
    private List<TossPaymentResponse.Cancel> cancels;

    public OrderFindDetailResponse(OrderInfo orderInfo) {
        this.id = orderInfo.getId();
        this.orderId = orderInfo.getOrderId();
        this.amount = orderInfo.getTotalAmount();
        this.paymentKey = orderInfo.getPaymentKey();
        this.requestedAt = orderInfo.getRequestedAt();
        this.approvedAt = orderInfo.getApprovedAt();
        this.status = orderInfo.getStatus();
        this.productName = orderInfo.getProduct().getName();
        this.userName = orderInfo.getUser().getUsername();
    }

    public OrderFindDetailResponse addTossPayments(TossPaymentResponse tossPaymentResponse) {
        this.lastTransactionKey = tossPaymentResponse.getLastTransactionKey();
        this.method = tossPaymentResponse.getMethod();
        this.cancels = tossPaymentResponse.getCancels();
        return this;
    }
}
