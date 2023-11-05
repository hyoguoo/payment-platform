package study.paymentintegrationserver;

import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.entity.Product;
import study.paymentintegrationserver.entity.User;

import java.math.BigDecimal;

public class TestDataFactory {

    public static Product generateProductWithPriceAndStock(BigDecimal price, Integer stock) {
        return Product.builder()
                .name("Test Generated Product")
                .price(price)
                .description("Test Generated Product Description")
                .stock(stock)
                .build();
    }

    public static User generateUser() {
        return User.builder()
                .username("Test Generated User")
                .email("test@test.com")
                .build();
    }

    public static OrderInfo generateOrderInfoWithTotalAmountAndQuantity(User user, Product product, BigDecimal totalAmount, Integer quantity) {
        return OrderInfo.builder()
                .user(user)
                .product(product)
                .orderName("Test Generated Order Name")
                .method("Test Generated Method")
                .totalAmount(totalAmount)
                .quantity(quantity)
                .status("Test Generated Status")
                .build();
    }
}
