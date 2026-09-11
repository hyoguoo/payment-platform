package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.domain.dto.OrderedProduct;
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
    void hash_고정입력_기대해시와_일치한다() {
        // 기대값 산출 방법: raw = "1:10x1,30x3" (정렬 후) 를 SHA-256 → 16진수 인코딩한 값.
        // python3 -c "import hashlib; print(hashlib.sha256('1:10x1,30x3'.encode()).hexdigest())" 로 재현 가능.
        // 이 테스트가 깨지면 산출식이 바뀐 것 — 의도한 변경이면 기대값도 함께 고친다.
        Long userId = 1L;
        List<OrderedProduct> products = List.of(
                OrderedProduct.builder().productId(30L).quantity(3).build(),
                OrderedProduct.builder().productId(10L).quantity(1).build()
        );

        String hash = hasher.hash(userId, products);

        assertThat(hash).isEqualTo("b844126412671cc2f065e1870e15d4b0cc9cb6d94a3e1e019ee3a9035fbab64a");
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
