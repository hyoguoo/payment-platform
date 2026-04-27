package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * StockOutboxRelayService 시간 결정성 검증.
 *
 * <p>fixed clock 을 {@link LocalDateTimeProvider} 로 주입하면
 * relay() 시 markProcessed 에 전달되는 processedAt 이 fixed 시각과 일치해야 한다.
 */
@DisplayName("StockOutboxRelayService — 시간 결정성 (fixed LocalDateTimeProvider)")
class StockOutboxRelayServiceClockTest {

    private static final String TOPIC = "payment.events.stock-committed";
    private static final String KEY = "42";
    private static final String PAYLOAD = "{\"productId\":42}";

    /** fixed clock: 2026-04-24T09:00:00Z */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-24T09:00:00Z");
    private static final LocalDateTime FIXED_LOCAL = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    private StockOutboxRepository stockOutboxRepository;
    private StockOutboxPublisherPort stockOutboxPublisherPort;
    private StockOutboxRelayService sut;

    @BeforeEach
    void setUp() {
        stockOutboxRepository = Mockito.mock(StockOutboxRepository.class);
        stockOutboxPublisherPort = Mockito.mock(StockOutboxPublisherPort.class);

        // fixed clock 주입으로 processedAt 결정성 확보
        LocalDateTimeProvider fixedProvider = new LocalDateTimeProvider() {
            @Override
            public LocalDateTime now() {
                return FIXED_LOCAL;
            }

            @Override
            public Instant nowInstant() {
                return FIXED_INSTANT;
            }
        };

        sut = new StockOutboxRelayService(stockOutboxRepository, stockOutboxPublisherPort, fixedProvider);
    }

    /**
     * markProcessed 에 전달되는 processedAt 이 fixed provider.now() 와 일치.
     */
    @Test
    @DisplayName("relay — markProcessed 인자 processedAt 이 fixed provider.now() 와 일치")
    void relay_markProcessedTimestampMatchesFixedClock() {
        // given
        StockOutbox pending = StockOutbox.of(1L, TOPIC, KEY, PAYLOAD, null,
                LocalDateTime.now(), null, 0, LocalDateTime.now());
        Mockito.when(stockOutboxRepository.findById(1L)).thenReturn(Optional.of(pending));

        // when
        sut.relay(1L);

        // then
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        then(stockOutboxRepository).should(times(1)).markProcessed(eq(1L), captor.capture());

        assertThat(captor.getValue()).isEqualTo(FIXED_LOCAL);
    }
}
