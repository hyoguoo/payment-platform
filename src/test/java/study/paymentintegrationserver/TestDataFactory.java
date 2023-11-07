package study.paymentintegrationserver;

import study.paymentintegrationserver.dto.order.OrderCancelRequest;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;
import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.entity.Product;
import study.paymentintegrationserver.entity.User;

import java.math.BigDecimal;
import java.util.List;

public class TestDataFactory {

    public static Product generateProductWithPriceAndStock(BigDecimal price, Integer stock) {
        return Product.builder()
                .id(1L)
                .name("Test Generated Product")
                .price(price)
                .description("Test Generated Product Description")
                .stock(stock)
                .build();
    }

    public static User generateUser() {
        return User.builder()
                .id(1L)
                .username("Test Generated User")
                .email("test@test.com")
                .build();
    }

    public static OrderInfo generateOrderInfoWithTotalAmountAndQuantity(User user, Product product, BigDecimal totalAmount, Integer quantity) {
        return OrderInfo.builder()
                .id(1L)
                .user(user)
                .product(product)
                .orderName("Test Generated Order Name")
                .method("Test Generated Method")
                .totalAmount(totalAmount)
                .quantity(quantity)
                .status("Test Generated Status")
                .build();
    }

    public static OrderConfirmRequest generateOrderConfirmRequest(Long userId, String orderId, BigDecimal amount, String paymentKey) {
        return new OrderConfirmRequest(
                userId,
                orderId,
                amount,
                paymentKey
        );
    }

    public static OrderCancelRequest generateOrderCancelRequest(String orderId) {
        return new OrderCancelRequest(orderId, "cancel reason");
    }

    public static TossPaymentResponse generateDonePaymentResponse(String paymentKey, String orderId, String orderName, BigDecimal totalAmount) {
        return TossPaymentResponse.builder()
                .version("2022-11-16")
                .paymentKey(paymentKey)
                .type("NORMAL")
                .orderId(orderId)
                .orderName(orderName)
                .currency("KRW")
                .method("카드")
                .totalAmount(totalAmount.longValue())
                .balanceAmount(totalAmount.longValue())
                .status("DONE")
                .requestedAt("2023-11-07T00:03:16+09:00")
                .approvedAt("2023-11-07T00:03:39+09:00")
                .lastTransactionKey("testogu59fri8fgjrikf")
                .suppliedAmount(totalAmount.multiply(BigDecimal.valueOf(0.9)).longValue())
                .vat(totalAmount.multiply(BigDecimal.valueOf(0.1)).longValue())
                .receipt(new TossPaymentResponse.Receipt("https://dashboard.tosspayments.com/receipt/test"))
                .checkout(new TossPaymentResponse.Checkout("https://api.tosspayments.com/v1/payments/test"))
                .country("KR")
                .build();
    }

    public static TossPaymentResponse generateCancelPaymentResponse(String paymentKey, String orderId, String orderName, BigDecimal totalAmount) {
        return TossPaymentResponse.builder()
                .version("2022-11-16")
                .paymentKey(paymentKey)
                .type("NORMAL")
                .orderId(orderId)
                .orderName(orderName)
                .currency("KRW")
                .method("카드")
                .totalAmount(totalAmount.longValue())
                .balanceAmount(0)
                .status("CANCELED")
                .requestedAt("2023-11-07T00:03:16+09:00")
                .approvedAt("2023-11-07T00:03:39+09:00")
                .lastTransactionKey("testogu59fri8fgjrikf")
                .cancels(List.of(new TossPaymentResponse.Cancel[]{new TossPaymentResponse.Cancel(50000.0, "테스트 결제 취소", 0.0, 0, 0.0, 0.0, "2023-11-07T00:03:39+09:00", "testogu59fri8fgjrikf", "testogu59fri8fgjrikf")}))
                .receipt(new TossPaymentResponse.Receipt("https://dashboard.tosspayments.com/receipt/test"))
                .checkout(new TossPaymentResponse.Checkout("https://api.tosspayments.com/v1/payments/test"))
                .country("KR")
                .build();
    }
}
