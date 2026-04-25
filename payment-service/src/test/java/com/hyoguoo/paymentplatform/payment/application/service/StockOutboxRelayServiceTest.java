package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * T-J1 RED: StockOutboxRelayService
 * - relay(id) — outbox row 로드 → publisher.send → markProcessed
 * - processedAt != null → skip (멱등성)
 * - outbox 없으면 no-op
 */
@DisplayName("StockOutboxRelayService — relay 멱등성 + 발행 검증")
class StockOutboxRelayServiceTest {

    private static final String TOPIC = "payment.events.stock-committed";
    private static final String KEY = "42";
    private static final String PAYLOAD = "{\"productId\":42,\"qty\":3}";

    private StockOutboxRepository stockOutboxRepository;
    private StockOutboxPublisherPort stockOutboxPublisherPort;
    private StockOutboxRelayService sut;

    @BeforeEach
    void setUp() {
        stockOutboxRepository = Mockito.mock(StockOutboxRepository.class);
        stockOutboxPublisherPort = Mockito.mock(StockOutboxPublisherPort.class);
        // K5: LocalDateTimeProvider 주입 (기존 테스트는 시간 값 검증 불필요 → 시스템 시각 그대로)
        LocalDateTimeProvider systemProvider = new LocalDateTimeProvider() {
            @Override
            public java.time.LocalDateTime now() {
                return LocalDateTime.now();
            }
        };
        sut = new StockOutboxRelayService(stockOutboxRepository, stockOutboxPublisherPort, systemProvider);
    }

    // -----------------------------------------------------------------------
    // TC-J1-1: outbox row 존재 + processedAt=null → publisher.send 1회 + markProcessed 1회
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("relay — pending outbox → publisher.send 1회 호출 + markProcessed 호출")
    void relay_whenPending_shouldPublishAndMarkProcessed() {
        // given
        StockOutbox outbox = StockOutbox.create(TOPIC, KEY, PAYLOAD, LocalDateTime.now());
        StockOutbox saved = StockOutbox.of(1L, TOPIC, KEY, PAYLOAD, null, LocalDateTime.now(), null, 0, LocalDateTime.now());
        Mockito.when(stockOutboxRepository.findById(1L)).thenReturn(Optional.of(saved));

        // when
        sut.relay(1L);

        // then
        then(stockOutboxPublisherPort)
                .should(times(1))
                .send(eq(TOPIC), eq(KEY), eq(PAYLOAD));
        then(stockOutboxRepository)
                .should(times(1))
                .markProcessed(eq(1L), any(LocalDateTime.class));
    }

    // -----------------------------------------------------------------------
    // TC-J1-2: processedAt != null → skip (중복 방지)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("relay — processedAt 이미 설정 → publisher.send 호출 없음 (멱등성)")
    void relay_whenAlreadyProcessed_shouldSkip() {
        // given
        StockOutbox processed = StockOutbox.of(2L, TOPIC, KEY, PAYLOAD, null,
                LocalDateTime.now(), LocalDateTime.now(), 1, LocalDateTime.now());
        Mockito.when(stockOutboxRepository.findById(2L)).thenReturn(Optional.of(processed));

        // when
        sut.relay(2L);

        // then
        then(stockOutboxPublisherPort).should(never()).send(anyString(), anyString(), anyString());
        then(stockOutboxRepository).should(never()).markProcessed(any(), any());
    }

    // -----------------------------------------------------------------------
    // TC-J1-3: outbox row 없음 → no-op
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("relay — row 없음 → no-op")
    void relay_whenNotFound_shouldNoOp() {
        // given
        Mockito.when(stockOutboxRepository.findById(99L)).thenReturn(Optional.empty());

        // when
        sut.relay(99L);

        // then
        then(stockOutboxPublisherPort).should(never()).send(anyString(), anyString(), anyString());
        then(stockOutboxRepository).should(never()).markProcessed(any(), any());
    }

    // -----------------------------------------------------------------------
    // TC-J1-4: publisher 예외 → markProcessed 호출 없음 (row 미갱신)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("relay — publisher 예외 시 markProcessed 미호출 (row 보존)")
    void relay_whenPublishFails_shouldNotMarkProcessed() {
        // given
        StockOutbox pending = StockOutbox.of(3L, TOPIC, KEY, PAYLOAD, null,
                LocalDateTime.now(), null, 0, LocalDateTime.now());
        Mockito.when(stockOutboxRepository.findById(3L)).thenReturn(Optional.of(pending));
        Mockito.doThrow(new RuntimeException("Kafka down"))
                .when(stockOutboxPublisherPort)
                .send(anyString(), anyString(), anyString());

        // when
        try {
            sut.relay(3L);
        } catch (RuntimeException ignored) {
            // 예외 전파는 워커가 catch
        }

        // then
        then(stockOutboxRepository).should(never()).markProcessed(any(), any());
    }
}
