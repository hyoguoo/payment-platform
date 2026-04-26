package com.hyoguoo.paymentplatform.payment.infrastructure.listener;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.service.StockOutboxRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * StockOutboxImmediateEventHandler — StockOutboxReadyEvent 수신 시
 * StockOutboxRelayService.relay(outboxId) 가 1회 호출되는지 검증한다.
 */
@DisplayName("StockOutboxImmediateEventHandler — AFTER_COMMIT relay 위임 검증")
class StockOutboxImmediateEventHandlerTest {

    private StockOutboxRelayService relayService;
    private StockOutboxImmediateEventHandler sut;

    @BeforeEach
    void setUp() {
        relayService = Mockito.mock(StockOutboxRelayService.class);
        sut = new StockOutboxImmediateEventHandler(relayService);
    }

    // -----------------------------------------------------------------------
    // TC-J1-5: StockOutboxReadyEvent 수신 → relay(outboxId) 1회 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handle — StockOutboxReadyEvent 수신 시 relay(outboxId) 1회 호출")
    void handle_shouldDelegateToRelayService() {
        // given
        StockOutboxReadyEvent event = new StockOutboxReadyEvent(42L);

        // when
        sut.handle(event);

        // then
        then(relayService)
                .should(times(1))
                .relay(42L);
    }
}
