package com.hyoguoo.paymentplatform.core.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdempotencyKeyHasherTest {

    private IdempotencyKeyHasher hasher;

    @BeforeEach
    void setUp() {
        hasher = new IdempotencyKeyHasher();
    }

    @Test
    void hash_동일한_userId와_상품목록_동일한_해시_반환() {
        Long userId = 1L;
        List<OrderedProduct> products = List.of(
                OrderedProduct.builder().productId(10L).quantity(2).build(),
                OrderedProduct.builder().productId(20L).quantity(1).build()
        );

        String hash1 = hasher.hash(userId, products);
        String hash2 = hasher.hash(userId, products);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void hash_상품_순서가_달라도_동일한_해시_반환() {
        Long userId = 1L;
        List<OrderedProduct> products1 = List.of(
                OrderedProduct.builder().productId(10L).quantity(2).build(),
                OrderedProduct.builder().productId(20L).quantity(1).build()
        );
        List<OrderedProduct> products2 = List.of(
                OrderedProduct.builder().productId(20L).quantity(1).build(),
                OrderedProduct.builder().productId(10L).quantity(2).build()
        );

        String hash1 = hasher.hash(userId, products1);
        String hash2 = hasher.hash(userId, products2);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void hash_다른_userId_다른_해시_반환() {
        List<OrderedProduct> products = List.of(
                OrderedProduct.builder().productId(10L).quantity(2).build()
        );

        String hash1 = hasher.hash(1L, products);
        String hash2 = hasher.hash(2L, products);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void hash_다른_상품목록_다른_해시_반환() {
        Long userId = 1L;
        List<OrderedProduct> products1 = List.of(
                OrderedProduct.builder().productId(10L).quantity(2).build()
        );
        List<OrderedProduct> products2 = List.of(
                OrderedProduct.builder().productId(10L).quantity(3).build()
        );

        String hash1 = hasher.hash(userId, products1);
        String hash2 = hasher.hash(userId, products2);

        assertThat(hash1).isNotEqualTo(hash2);
    }
}
