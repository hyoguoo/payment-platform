package com.hyoguoo.paymentplatform.payment.infrasturcture.dto.response;

import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * <a href="https://docs.tosspayments.com/reference">Toss Payments API 응답 객체(Version
 * 2022-11-16)</a>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class TossPaymentResponse {

    // 2023-01-01T00:00:00+09:00 Pattern
    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private String version;
    private String paymentKey;
    private String type;
    private String orderId;
    private String orderName;
    private String mId;
    private String currency;
    private String method;
    private double totalAmount;
    private double balanceAmount;
    private String status;
    private String requestedAt;
    private String approvedAt;
    private boolean useEscrow;
    private String lastTransactionKey;
    private double suppliedAmount;
    private double vat;
    private boolean cultureExpense;
    private double taxFreeAmount;
    private int taxExemptionAmount;
    private List<Cancel> cancels;
    private MobilePhone mobilePhone;
    private String receiptUrl;
    private GiftCertificate giftCertificate;
    private Transfer transfer;
    private Receipt receipt;
    private Checkout checkout;
    private EasyPay easyPay;
    private String country;
    private Failure failure;
    private CashReceipt cashReceipt;

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cancel {

        private double cancelAmount;
        private String cancelReason;
        private double taxFreeAmount;
        private int taxExemptionAmount;
        private double refundableAmount;
        private double easyPayDiscountAmount;
        private String canceledAt;
        private String transactionKey;
        private String receiptKey;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MobilePhone {

        private String customerMobilePhone;
        private String settlementStatus;

    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GiftCertificate {

        private String approveNo;
        private String settlementStatus;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Transfer {

        private String bankCode;
        private String settlementStatus;
    }


    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Receipt {

        private String url;
    }


    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Checkout {

        private String url;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EasyPay {

        private String provider;
        private double amount;
        private double discountAmount;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Failure {

        private String code;
        private String message;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CashReceipt {

        private String type;
        private String receiptKey;
        private String issueNumber;
        private String businessNumber;
        private String transactionType;
        private double amount;
        private double taxFreeAmount;
        private String issueStatus;
        private Failure failure;
        private String customerIdentityNumber;
        private String requestedAt;
    }
}
