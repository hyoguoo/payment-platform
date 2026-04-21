package com.hyoguoo.paymentplatform.product.mock;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FakePaymentStockCachePortTest {

    private FakePaymentStockCachePort port;

    @BeforeEach
    void setUp() {
        port = new FakePaymentStockCachePort();
    }

    @Test
    @DisplayName("setStock — 최신 재고 상태 저장")
    void setStock_StoresLatestStockState() {
        port.setStock(1L, 100);

        assertThat(port.getLatestStock(1L)).isEqualTo(100);
    }

    @Test
    @DisplayName("setStock — 동일 상품 재호출 시 최신값으로 덮어쓰기")
    void setStock_Overwrite_UpdatesLatestState() {
        port.setStock(1L, 100);
        port.setStock(1L, 80);

        assertThat(port.getLatestStock(1L)).isEqualTo(80);
    }

    @Test
    @DisplayName("setStock — 이력 기록 순서 검증")
    void setStock_RecordsHistory_InOrder() {
        port.setStock(1L, 100);
        port.setStock(1L, 80);
        port.setStock(2L, 50);

        List<FakePaymentStockCachePort.SetRecord> history = port.getHistory();

        assertThat(history).hasSize(3);
        assertThat(history.get(0).productId()).isEqualTo(1L);
        assertThat(history.get(0).stock()).isEqualTo(100);
        assertThat(history.get(1).productId()).isEqualTo(1L);
        assertThat(history.get(1).stock()).isEqualTo(80);
        assertThat(history.get(2).productId()).isEqualTo(2L);
        assertThat(history.get(2).stock()).isEqualTo(50);
    }

    @Test
    @DisplayName("setStock — 호출 횟수 추적")
    void setStock_TracksCallCount() {
        port.setStock(1L, 100);
        port.setStock(1L, 80);
        port.setStock(2L, 50);

        assertThat(port.getSetCallCount()).isEqualTo(3);
        assertThat(port.getSetCallCountFor(1L)).isEqualTo(2);
        assertThat(port.getSetCallCountFor(2L)).isEqualTo(1);
    }

    @Test
    @DisplayName("미호출 상품 — getLatestStock -1 반환")
    void getLatestStock_NeverSet_ReturnsMinusOne() {
        assertThat(port.getLatestStock(999L)).isEqualTo(-1);
    }

    @Test
    @DisplayName("reset — 상태 초기화")
    void reset_ClearsAllState() {
        port.setStock(1L, 100);
        port.reset();

        assertThat(port.getSetCallCount()).isEqualTo(0);
        assertThat(port.getHistory()).isEmpty();
        assertThat(port.getLatestStock(1L)).isEqualTo(-1);
    }
}
