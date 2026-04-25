package com.hyoguoo.paymentplatform.pg.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-F2 RED — PgOutboxPollingWorker catch(Exception) 축소 검증.
 *
 * <p>단일 건 실패 시 루프가 계속되고 ERROR 로그 + relay_fail 카운터가 증가해야 한다.
 */
@DisplayName("PgOutboxPollingWorker")
class PgOutboxPollingWorkerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-24T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository pgOutboxRepository;
    private PgOutboxRelayService pgOutboxRelayService;
    private SimpleMeterRegistry meterRegistry;
    private PgOutboxPollingWorker pollingWorker;

    @BeforeEach
    void setUp() {
        pgOutboxRepository = mock(com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository.class);
        pgOutboxRelayService = mock(PgOutboxRelayService.class);
        meterRegistry = new SimpleMeterRegistry();
        pollingWorker = new PgOutboxPollingWorker(pgOutboxRepository, pgOutboxRelayService, FIXED_CLOCK, meterRegistry);
    }

    @Test
    @DisplayName("polling_whenRelayThrows — 단일 건 RuntimeException 발생 시 루프 계속 + relay_fail 카운터 increment")
    void polling_whenRelayThrows_shouldLogErrorAndContinue() {
        // given: 2건 pending
        PgOutbox outbox1 = PgOutbox.of(1L, PgTopics.EVENTS_CONFIRMED, "order-1",
                "{\"orderId\":\"order-1\"}", null, FIXED_NOW.minusSeconds(1), null, 0, FIXED_NOW.minusSeconds(60));
        PgOutbox outbox2 = PgOutbox.of(2L, PgTopics.EVENTS_CONFIRMED, "order-2",
                "{\"orderId\":\"order-2\"}", null, FIXED_NOW.minusSeconds(1), null, 0, FIXED_NOW.minusSeconds(60));

        when(pgOutboxRepository.findPendingBatch(anyInt(), anyInstant())).thenReturn(List.of(outbox1, outbox2));

        // 첫 번째 relay 는 RuntimeException, 두 번째는 성공
        doThrow(new RuntimeException("Kafka 발행 실패")).when(pgOutboxRelayService).relay(1L);
        doNothing().when(pgOutboxRelayService).relay(2L);

        // when
        pollingWorker.poll();

        // then: relay 2건 모두 호출 (루프 계속)
        verify(pgOutboxRelayService, times(1)).relay(1L);
        verify(pgOutboxRelayService, times(1)).relay(2L);

        // then: relay_fail 카운터 1 증가
        Counter relayFailCounter = meterRegistry.find("pg_outbox.relay_fail_total").counter();
        assertThat(relayFailCounter)
                .as("polling relay 실패 시 pg_outbox.relay_fail_total 카운터가 등록되어야 한다")
                .isNotNull();
        assertThat(relayFailCounter.count())
                .as("첫 번째 relay 실패로 카운터가 1이어야 한다")
                .isEqualTo(1.0);
    }

    // Mockito any() helper - Instant 타입용
    private static Instant anyInstant() {
        return org.mockito.ArgumentMatchers.any(Instant.class);
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
