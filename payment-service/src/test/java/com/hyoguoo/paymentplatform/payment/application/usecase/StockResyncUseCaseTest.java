package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.StockResyncOverlapStatus;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.StockResyncResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

class StockResyncUseCaseTest {

    private StockResyncUseCase stockResyncUseCase;
    private ProductPort mockProductPort;
    private StockCachePort mockStockCachePort;
    private StockHoldRecordRepository mockStockHoldRecordRepository;

    @BeforeEach
    void setUp() {
        mockProductPort = Mockito.mock(ProductPort.class);
        mockStockCachePort = Mockito.mock(StockCachePort.class);
        mockStockHoldRecordRepository = Mockito.mock(StockHoldRecordRepository.class);
        stockResyncUseCase = new StockResyncUseCase(mockProductPort, mockStockCachePort, mockStockHoldRecordRepository);
    }

    @Test
    @DisplayName("resyncStockCache — 진행 중 선차감이 없으면 product RDB(SoT) 재고로 캐시를 덮어쓰고 겹침 없음을 반환한다")
    void resyncStockCache_setsRedisToRdbStock() {
        // given — 사전·사후 모두 진행 중 선차감 0건, product RDB 가 재고 42 를 보고한다
        when(mockStockHoldRecordRepository.countNoiseByProductId(1L)).thenReturn(0L);
        when(mockProductPort.getProductInfoById(1L))
                .thenReturn(ProductInfo.builder().id(1L).stock(42).build());

        // when
        StockResyncResult result = stockResyncUseCase.resyncStockCache(1L, false);

        // then — 캐시를 RDB 값으로 set 하고 그 수량 + 겹침 없음을 반환
        assertThat(result.quantity()).isEqualTo(42);
        assertThat(result.overlapStatus()).isEqualTo(StockResyncOverlapStatus.CLEAR);
        verify(mockStockCachePort).set(1L, 42);
    }

    @Test
    @DisplayName("resyncStockCache — 미종결 선차감이 있으면 거부한다")
    void resyncStockCache_미종결_선차감이_있으면_거부한다() {
        // given — 사전 조회에서 진행 중 선차감 2건
        when(mockStockHoldRecordRepository.countNoiseByProductId(1L)).thenReturn(2L);

        // when / then — 강제 실행이 아니므로 상태 예외로 거부하고 캐시는 건드리지 않는다
        assertThatThrownBy(() -> stockResyncUseCase.resyncStockCache(1L, false))
                .isInstanceOf(PaymentStatusException.class);
        verify(mockStockCachePort, never()).set(anyLong(), anyInt());
    }

    @Test
    @DisplayName("resyncStockCache — 강제 실행이면 미종결 선차감이 있어도 덮어쓴다")
    void resyncStockCache_강제실행이면_미종결_선차감이_있어도_덮어쓴다() {
        // given — 사전·사후 모두 진행 중 선차감 2건(늘지 않음)
        when(mockStockHoldRecordRepository.countNoiseByProductId(1L)).thenReturn(2L, 2L);
        when(mockProductPort.getProductInfoById(1L))
                .thenReturn(ProductInfo.builder().id(1L).stock(42).build());

        // when
        StockResyncResult result = stockResyncUseCase.resyncStockCache(1L, true);

        // then — 예외 없이 진행하고 캐시를 덮어쓴다
        assertThat(result.quantity()).isEqualTo(42);
        verify(mockStockCachePort).set(1L, 42);
    }

    @ParameterizedTest(name = "force={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("resyncStockCache — 사전조회가 실패하면 강제실행이어도 거부한다")
    void resyncStockCache_사전조회가_실패하면_강제실행이어도_거부한다(boolean force) {
        // given — 사전 건수 조회 자체가 실패
        when(mockStockHoldRecordRepository.countNoiseByProductId(1L))
                .thenThrow(new IllegalStateException("count query failed"));

        // when / then — 판정 불가를 통과로 접지 않는다. 강제 실행 여부와 무관하게 거부하고 캐시는 건드리지 않는다
        assertThatThrownBy(() -> stockResyncUseCase.resyncStockCache(1L, force))
                .isInstanceOf(IllegalStateException.class);
        verify(mockStockCachePort, never()).set(anyLong(), anyInt());
    }

    @Test
    @DisplayName("resyncStockCache — 사후 건수가 늘면 겹침을 알린다")
    void resyncStockCache_사후_건수가_늘면_겹침을_알린다() {
        // given — 사전 0건, 사후 1건(덮어쓰는 사이 새 선차감이 들어왔다)
        when(mockStockHoldRecordRepository.countNoiseByProductId(1L)).thenReturn(0L, 1L);
        when(mockProductPort.getProductInfoById(1L))
                .thenReturn(ProductInfo.builder().id(1L).stock(42).build());

        // when
        StockResyncResult result = stockResyncUseCase.resyncStockCache(1L, false);

        // then
        assertThat(result.overlapStatus()).isEqualTo(StockResyncOverlapStatus.OVERLAPPED);
    }

    @Test
    @DisplayName("resyncStockCache — 강제실행 경로에서 사후 건수가 늘면 겹침을 알린다")
    void resyncStockCache_강제실행_경로에서_사후_건수가_늘면_겹침을_알린다() {
        // given — 강제 실행이라 사전 건수가 이미 2건, 사후엔 3건으로 더 늘었다.
        // 겹침 판정이 "사전 0건"에 묶여 있으면 이 경로는 영영 겹침을 못 잡는다.
        when(mockStockHoldRecordRepository.countNoiseByProductId(1L)).thenReturn(2L, 3L);
        when(mockProductPort.getProductInfoById(1L))
                .thenReturn(ProductInfo.builder().id(1L).stock(42).build());

        // when
        StockResyncResult result = stockResyncUseCase.resyncStockCache(1L, true);

        // then
        assertThat(result.overlapStatus()).isEqualTo(StockResyncOverlapStatus.OVERLAPPED);
    }

    @Test
    @DisplayName("resyncStockCache — 사후 건수가 그대로면 겹침없음으로 끝난다")
    void resyncStockCache_사후_건수가_그대로면_겹침없음으로_끝난다() {
        // given — 강제 실행 경로, 사전·사후 모두 2건으로 그대로다
        when(mockStockHoldRecordRepository.countNoiseByProductId(1L)).thenReturn(2L, 2L);
        when(mockProductPort.getProductInfoById(1L))
                .thenReturn(ProductInfo.builder().id(1L).stock(42).build());

        // when
        StockResyncResult result = stockResyncUseCase.resyncStockCache(1L, true);

        // then
        assertThat(result.overlapStatus()).isEqualTo(StockResyncOverlapStatus.CLEAR);
    }

    @Test
    @DisplayName("resyncStockCache — 사후조회가 실패해도 정상 종료한다")
    void resyncStockCache_사후조회가_실패해도_정상_종료한다() {
        // given — 사전 조회는 성공(0건)하지만 덮어쓴 뒤 재확인이 실패한다
        when(mockStockHoldRecordRepository.countNoiseByProductId(1L))
                .thenReturn(0L)
                .thenThrow(new IllegalStateException("recheck failed"));
        when(mockProductPort.getProductInfoById(1L))
                .thenReturn(ProductInfo.builder().id(1L).stock(42).build());

        // when — 예외가 밖으로 전파되지 않고 정상 종료한다. 덮어쓰기는 이미 끝났기 때문이다
        StockResyncResult result = stockResyncUseCase.resyncStockCache(1L, false);

        // then — 겹침 여부는 판정 불가이므로 미상으로 남는다
        assertThat(result.quantity()).isEqualTo(42);
        assertThat(result.overlapStatus()).isEqualTo(StockResyncOverlapStatus.UNKNOWN);
    }
}
