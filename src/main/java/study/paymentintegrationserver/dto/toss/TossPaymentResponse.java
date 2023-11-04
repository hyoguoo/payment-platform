package study.paymentintegrationserver.dto.toss;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * <a href="https://docs.tosspayments.com/reference">Toss Payments API 응답 객체(Version 2022-11-16)</a>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public final class TossPaymentResponse {

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
