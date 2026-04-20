package com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * <a href="https://developers.nicepay.co.kr/nicepay/payment/v1">NicePay REST API 응답 객체</a>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class NicepayPaymentApiResponse {

    // 2021-01-01T00:00:00.000+0900 Pattern
    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private String resultCode;
    private String resultMsg;
    private String tid;
    private String cancelledTid;
    private String orderId;
    private BigDecimal amount;
    private BigDecimal balanceAmt;
    private String goodsName;
    private String method;
    private String status;
    private String paidAt;
    private String failedAt;
    private String cancelledAt;
    private String payMethod;
    private String useEscrow;
    private String currency;
}
