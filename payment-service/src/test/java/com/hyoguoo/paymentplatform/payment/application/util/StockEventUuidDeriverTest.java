package com.hyoguoo.paymentplatform.payment.application.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * StockEventUuidDeriver — (orderId, productId, prefix) 기반 결정론적 UUID v3 도출.
 * 동일 입력 → 동일 UUID, 다른 입력 → 다른 UUID 불변식 검증.
 */
@DisplayName("StockEventUuidDeriver 결정론적 UUID v3 도출")
class StockEventUuidDeriverTest {

    private static final String ORDER_ID = "order-uuid-001";
    private static final long PRODUCT_ID_1 = 100L;
    private static final long PRODUCT_ID_2 = 200L;
    private static final String PREFIX_COMMIT = "stock-commit";
    private static final String PREFIX_RESTORE = "stock-restore";

    // -----------------------------------------------------------------------
    // TC-K1-1: 동일 (orderId, productId, prefix) → 결정론적 동일 UUID
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("동일 (orderId, productId, prefix) 두 번 호출 시 동일 UUID 반환")
    void derive_sameSeed_shouldReturnSameUuid() {
        String uuid1 = StockEventUuidDeriver.derive(ORDER_ID, PRODUCT_ID_1, PREFIX_COMMIT);
        String uuid2 = StockEventUuidDeriver.derive(ORDER_ID, PRODUCT_ID_1, PREFIX_COMMIT);

        assertThat(uuid1).isEqualTo(uuid2);
    }

    // -----------------------------------------------------------------------
    // TC-K1-2: 다른 productId → 다른 UUID
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("다른 productId → 서로 다른 UUID 반환 (multi-product dedupe 분리)")
    void derive_differentProductId_shouldReturnDifferentUuid() {
        String uuid1 = StockEventUuidDeriver.derive(ORDER_ID, PRODUCT_ID_1, PREFIX_COMMIT);
        String uuid2 = StockEventUuidDeriver.derive(ORDER_ID, PRODUCT_ID_2, PREFIX_COMMIT);

        assertThat(uuid1).isNotEqualTo(uuid2);
    }

    // -----------------------------------------------------------------------
    // TC-K1-3: 다른 prefix → 다른 UUID (commit vs restore 분리)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("다른 prefix → 서로 다른 UUID 반환 (commit vs restore 분리)")
    void derive_differentPrefix_shouldReturnDifferentUuid() {
        String uuid1 = StockEventUuidDeriver.derive(ORDER_ID, PRODUCT_ID_1, PREFIX_COMMIT);
        String uuid2 = StockEventUuidDeriver.derive(ORDER_ID, PRODUCT_ID_1, PREFIX_RESTORE);

        assertThat(uuid1).isNotEqualTo(uuid2);
    }

    // -----------------------------------------------------------------------
    // TC-K1-4: UUID 포맷 검증 (8-4-4-4-12 형식)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("도출된 UUID가 표준 UUID 포맷(8-4-4-4-12)을 따른다")
    void derive_shouldReturnValidUuidFormat() {
        String uuid = StockEventUuidDeriver.derive(ORDER_ID, PRODUCT_ID_1, PREFIX_COMMIT);

        assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
