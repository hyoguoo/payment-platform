package com.hyoguoo.paymentplatform.payment.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.service.StockOutboxRelayService;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * StockOutboxWorker 단위 테스트 — 생성자 @Value 주입을 검증하므로 ReflectionTestUtils 미사용.
 */
@DisplayName("StockOutboxWorker 테스트")
class StockOutboxWorkerTest {

    private static final long OUTBOX_ID_1 = 1L;
    private static final long OUTBOX_ID_2 = 2L;

    private StockOutboxRepository mockStockOutboxRepository;
    private StockOutboxRelayService mockStockOutboxRelayService;
    private StockOutboxWorker stockOutboxWorker;

    @BeforeEach
    void setUp() {
        mockStockOutboxRepository = Mockito.mock(StockOutboxRepository.class);
        mockStockOutboxRelayService = Mockito.mock(StockOutboxRelayService.class);

        stockOutboxWorker = new StockOutboxWorker(
                mockStockOutboxRepository,
                mockStockOutboxRelayService,
                50,
                false
        );
    }

    @Test
    @DisplayName("process - PENDING 없음: StockOutboxRelayService를 호출하지 않는다")
    void process_noPendingRecords_doesNotCallRelayService() {
        // given
        given(mockStockOutboxRepository.findPendingBatch(anyInt()))
                .willReturn(Collections.emptyList());

        // when
        stockOutboxWorker.process();

        // then
        then(mockStockOutboxRelayService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("process - PENDING 2건: StockOutboxRelayService.relay()를 2회 위임한다")
    void process_pendingRecords_delegatesToRelayService() {
        // given
        List<StockOutbox> pending = List.of(
                createPendingOutbox(OUTBOX_ID_1),
                createPendingOutbox(OUTBOX_ID_2)
        );
        given(mockStockOutboxRepository.findPendingBatch(anyInt())).willReturn(pending);

        // when
        stockOutboxWorker.process();

        // then
        then(mockStockOutboxRelayService).should(times(2)).relay(anyLong());
        then(mockStockOutboxRelayService).should(times(1)).relay(OUTBOX_ID_1);
        then(mockStockOutboxRelayService).should(times(1)).relay(OUTBOX_ID_2);
    }

    private StockOutbox createPendingOutbox(long id) {
        return StockOutbox.of(
                id,
                "test-topic",
                "test-key",
                "{}",
                null,
                LocalDateTime.now(),
                null,
                0,
                LocalDateTime.now()
        );
    }
}
