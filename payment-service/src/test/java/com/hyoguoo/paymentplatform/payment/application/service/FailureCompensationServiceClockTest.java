package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockOutboxRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * K5 RED: FailureCompensationService 시간 결정성 검증.
 *
 * <p>fixed clock을 {@link LocalDateTimeProvider}로 주입하면
 * stock_outbox의 createdAt / StockRestoreEvent.occurredAt 이 fixed 시각과 일치해야 한다.
 * 현재 직접 호출(Instant.now() / LocalDateTime.now())이라 fixed clock 주입 경로 없음
 * → {@link LocalDateTimeProvider#nowInstant()} 메서드 추가 전에는 컴파일 에러(RED).
 */
@DisplayName("FailureCompensationService — K5 시간 결정성 (fixed LocalDateTimeProvider)")
class FailureCompensationServiceClockTest {

    private static final String ORDER_ID = "order-k5-comp-001";
    private static final long PRODUCT_ID = 99L;
    private static final int QTY = 2;

    /** fixed clock: 2026-04-24T09:00:00Z */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-24T09:00:00Z");
    private static final LocalDateTime FIXED_LOCAL = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    private FakeStockOutboxRepository stockOutboxRepository;
    private CapturingApplicationEventPublisher eventPublisher;
    private FailureCompensationService sut;

    @BeforeEach
    void setUp() {
        stockOutboxRepository = new FakeStockOutboxRepository();
        eventPublisher = new CapturingApplicationEventPublisher();

        // K5: LocalDateTimeProvider — fixed clock 주입 (nowInstant() 메서드 필요)
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

        sut = new FailureCompensationService(
                eventPublisher,
                stockOutboxRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                fixedProvider
        );
    }

    /**
     * TC-K5-COMP-1: StockOutbox.createdAt 이 fixed provider.now() 와 일치.
     */
    @Test
    @DisplayName("compensate — stock_outbox.createdAt 이 fixed provider.now() 와 일치")
    void compensate_stockOutboxCreatedAtMatchesFixedClock() {
        // when
        sut.compensate(ORDER_ID, PRODUCT_ID, QTY);

        // then
        assertThat(stockOutboxRepository.savedCount()).isEqualTo(1);
        StockOutbox saved = stockOutboxRepository.allSaved().get(0);
        assertThat(saved.getAvailableAt()).isEqualTo(FIXED_LOCAL);
    }

    /**
     * TC-K5-COMP-2: StockRestoreEvent.occurredAt(Instant) 이 fixed provider.nowInstant() 와 일치.
     * Jackson WRITE_DATES_AS_TIMESTAMPS(기본 true) → occurredAt 은 epoch seconds(double) 로 직렬화됨.
     * epoch seconds 비교로 검증한다.
     */
    @Test
    @DisplayName("compensate — StockRestoreEvent.occurredAt 이 fixed provider.nowInstant() 와 일치")
    void compensate_restoreEventOccurredAtMatchesFixedInstant() throws Exception {
        // when
        sut.compensate(ORDER_ID, PRODUCT_ID, QTY);

        // then
        assertThat(stockOutboxRepository.savedCount()).isEqualTo(1);
        StockOutbox saved = stockOutboxRepository.allSaved().get(0);

        com.fasterxml.jackson.databind.ObjectMapper om =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        com.fasterxml.jackson.databind.JsonNode node = om.readTree(saved.getPayload());
        // occurredAt 필드가 fixed instant 의 epoch seconds 와 일치
        // Jackson WRITE_DATES_AS_TIMESTAMPS=true → 숫자(double) 직렬화
        double epochSeconds = node.get("occurredAt").asDouble();
        long actualEpochSecond = (long) epochSeconds;
        assertThat(actualEpochSecond).isEqualTo(FIXED_INSTANT.getEpochSecond());
    }

    // ---- helper ----

    static class CapturingApplicationEventPublisher implements ApplicationEventPublisher {

        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        public List<StockOutboxReadyEvent> capturedReadyEvents() {
            return events.stream()
                    .filter(e -> e instanceof StockOutboxReadyEvent)
                    .map(e -> (StockOutboxReadyEvent) e)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }
}
