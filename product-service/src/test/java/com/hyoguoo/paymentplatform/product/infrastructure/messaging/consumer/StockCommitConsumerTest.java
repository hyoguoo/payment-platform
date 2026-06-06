package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto.StockCommittedMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * StockCommitConsumer 단위 테스트.
 * <p>
 * Mock StockCommitUseCase — 메시지 파싱·위임 검증.
 * T13: Clock 주입으로 expiresAt=null, occurredAt=null 케이스를 결정적으로 제어한다.
 * P5: consume 진입 시 단일 now = clock.instant() 산출 — commit 인자와 resolveExpiresAt fallback base가 동일 Instant 공유.
 */
@ExtendWith(MockitoExtension.class)
class StockCommitConsumerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private StockCommitUseCase stockCommitUseCase;

    private StockCommitConsumer stockCommitConsumer;

    @BeforeEach
    void setUp() {
        stockCommitConsumer = new StockCommitConsumer(FIXED_CLOCK, stockCommitUseCase);
    }

    @Test
    @DisplayName("consume 호출 시 StockCommitUseCase.commit 1회 위임")
    void consume_ShouldDelegateToStockCommitUseCase() {
        // given
        long productId = 10L;
        String orderId = "order-1000";  // orderId 는 String 으로 통일
        String eventUUID = "event-uuid-consumer-test";
        int qty = 7;
        Instant occurredAt = Instant.now();
        Instant expiresAt = occurredAt.plusSeconds(86400);

        StockCommittedMessage message = new StockCommittedMessage(
                productId,
                qty,
                eventUUID,
                occurredAt,
                orderId,
                expiresAt
        );

        // when
        stockCommitConsumer.consume(message);

        // then: usecase 1회 위임 — orderId 는 String 그대로 전달된다.
        // now 인자는 consume() 진입 시 clock.instant()로 산출 — FIXED_CLOCK 이므로 FIXED_INSTANT
        verify(stockCommitUseCase, times(1))
                .commit(eventUUID, orderId, productId, qty, FIXED_INSTANT, expiresAt);
    }

    @Test
    @DisplayName("expiresAt=null, occurredAt=fixed → expiresAt = occurredAt + 8d 로 fallback")
    void consume_whenExpiresAtNull_shouldFallbackFromOccurredAt() {
        // given
        long productId = 10L;
        String orderId = "order-1000";  // orderId 는 String 으로 통일
        String eventUUID = "event-uuid-fallback-test";
        int qty = 3;
        Instant occurredAt = Instant.parse("2026-01-01T00:00:00Z");

        StockCommittedMessage message = new StockCommittedMessage(
                productId,
                qty,
                eventUUID,
                occurredAt,
                orderId,
                null  // expiresAt null — Producer 미전송 케이스
        );

        // when
        stockCommitConsumer.consume(message);

        // then: expiresAt = occurredAt + DEDUPE_TTL(8d), now = FIXED_INSTANT (clock.instant())
        ArgumentCaptor<Instant> expiresAtCaptor = forClass(Instant.class);
        verify(stockCommitUseCase, times(1))
                .commit(
                        org.mockito.ArgumentMatchers.eq(eventUUID),
                        org.mockito.ArgumentMatchers.eq(orderId),
                        org.mockito.ArgumentMatchers.eq(productId),
                        org.mockito.ArgumentMatchers.eq(qty),
                        org.mockito.ArgumentMatchers.eq(FIXED_INSTANT),
                        expiresAtCaptor.capture()
                );

        Instant expectedExpiresAt = occurredAt.plus(StockCommitUseCase.DEDUPE_TTL);
        assertThat(expiresAtCaptor.getValue()).isEqualTo(expectedExpiresAt);
    }

    /**
     * P5 RED 테스트: clock.instant() 가 호출마다 1ms씩 전진하는 tick Clock 을 주입했을 때,
     * expiresAt=null/occurredAt=null 케이스에서 commit 의 now 인자와 resolveExpiresAt fallback base 가
     * 동일 Instant 여야 한다 (단일 진입점 동일 시각 D1).
     * 현재 구현(resolveExpiresAt 내부에서 clock.instant() 별도 호출)은 now != fallback base 가 되어 FAIL.
     */
    @Test
    @DisplayName("consume_clock주입now_useCaseCommit에전달: 단일 now 산출 — commit now 인자와 resolveExpiresAt fallback base가 동일 Instant")
    void consume_clock주입now_useCaseCommit에전달() {
        // given — 호출마다 1ms 전진하는 tick Clock
        AtomicInteger tickCount = new AtomicInteger(0);
        Clock tickClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return FIXED_INSTANT.plus(Duration.ofMillis(tickCount.getAndIncrement()));
            }
        };
        StockCommitConsumer tickConsumer = new StockCommitConsumer(tickClock, stockCommitUseCase);

        // expiresAt=null, occurredAt=null → resolveExpiresAt 내부에서 clock.instant() 호출 경로 진입
        StockCommittedMessage message = new StockCommittedMessage(
                10L,
                1,
                "event-uuid-p5",
                null,  // occurredAt null
                "order-p5",
                null   // expiresAt null
        );

        // when
        tickConsumer.consume(message);

        // then: commit 에 전달된 now 와 expiresAt - DEDUPE_TTL (= fallback base) 가 동일 Instant 여야 한다.
        // 단일 산출이면 now == fallback base → expiresAt = now + DEDUPE_TTL → expiresAt - DEDUPE_TTL == now
        ArgumentCaptor<Instant> nowCaptor = forClass(Instant.class);
        ArgumentCaptor<Instant> expiresAtCaptor = forClass(Instant.class);
        verify(stockCommitUseCase, times(1))
                .commit(
                        eq("event-uuid-p5"),
                        eq("order-p5"),
                        eq(10L),
                        eq(1),
                        nowCaptor.capture(),
                        expiresAtCaptor.capture()
                );

        Instant capturedNow = nowCaptor.getValue();
        Instant capturedExpiresAt = expiresAtCaptor.getValue();
        Instant fallbackBase = capturedExpiresAt.minus(StockCommitUseCase.DEDUPE_TTL);
        assertThat(fallbackBase).isEqualTo(capturedNow);
    }

    @Test
    @DisplayName("expiresAt=null, occurredAt=null → expiresAt = clock.instant() + 8d (고정 시각 기반)")
    void consume_whenBothNull_shouldFallbackFromClock() {
        // given — T13: Clock.fixed 주입으로 fallback 시각을 결정적으로 제어
        long productId = 10L;
        String orderId = "order-1000";
        String eventUUID = "event-uuid-bothnull-test";
        int qty = 1;

        StockCommittedMessage message = new StockCommittedMessage(
                productId,
                qty,
                eventUUID,
                null,  // occurredAt null
                orderId,
                null   // expiresAt null
        );

        // when
        stockCommitConsumer.consume(message);

        // then: expiresAt = FIXED_INSTANT + DEDUPE_TTL (clock.instant() 기반으로 결정적), now = FIXED_INSTANT
        ArgumentCaptor<Instant> expiresAtCaptor = forClass(Instant.class);
        verify(stockCommitUseCase, times(1))
                .commit(
                        org.mockito.ArgumentMatchers.eq(eventUUID),
                        org.mockito.ArgumentMatchers.eq(orderId),
                        org.mockito.ArgumentMatchers.eq(productId),
                        org.mockito.ArgumentMatchers.eq(qty),
                        org.mockito.ArgumentMatchers.eq(FIXED_INSTANT),
                        expiresAtCaptor.capture()
                );

        Instant expectedExpiresAt = FIXED_INSTANT.plus(StockCommitUseCase.DEDUPE_TTL);
        assertThat(expiresAtCaptor.getValue()).isEqualTo(expectedExpiresAt);
    }
}
