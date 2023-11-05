package study.paymentintegrationserver;

import study.paymentintegrationserver.entity.Product;

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
}
