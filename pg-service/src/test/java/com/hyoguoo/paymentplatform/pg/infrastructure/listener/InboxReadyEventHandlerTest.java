package com.hyoguoo.paymentplatform.pg.infrastructure.listener;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hyoguoo.paymentplatform.pg.domain.event.PgInboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgInboxChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InboxReadyEventHandler 단위 테스트.
 *
 * <p>PCS-11 — AFTER_COMMIT 시점에 PgInboxChannel.offerNow 호출 검증.
 * PLAN § PCS-11 테스트 케이스:
 * - onInboxReady_channelOfferSucceeds_noWarnLog
 * - onInboxReady_channelFull_logsWarn
 * - onInboxReady_callsChannelOfferWithCorrectId
 */
@DisplayName("InboxReadyEventHandler")
@ExtendWith(MockitoExtension.class)
class InboxReadyEventHandlerTest {

    @Mock
    private PgInboxChannel channel;

    private InboxReadyEventHandler handler;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger handlerLogger;

    @BeforeEach
    void setUp() {
        handler = new InboxReadyEventHandler(channel);
        handlerLogger = (Logger) LoggerFactory.getLogger(InboxReadyEventHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("onInboxReady_channelOfferSucceeds_noWarnLog — offerNow true 반환 시 warn 로그 미발생")
    void onInboxReady_channelOfferSucceeds_noWarnLog() {
        when(channel.offerNow(100L)).thenReturn(true);

        handler.handle(new PgInboxReadyEvent(100L));

        List<ILoggingEvent> warnLogs = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warnLogs).isEmpty();
    }

    @Test
    @DisplayName("onInboxReady_channelFull_logsWarn — offerNow false 반환 시 WARN 로그 1회 발생, 예외 없이 종료")
    void onInboxReady_channelFull_logsWarn() {
        when(channel.offerNow(200L)).thenReturn(false);

        // 예외 없이 종료해야 함 — Polling Worker fallback 보장
        handler.handle(new PgInboxReadyEvent(200L));

        List<ILoggingEvent> warnLogs = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warnLogs).hasSize(1);
    }

    @Test
    @DisplayName("onInboxReady_callsChannelOfferWithCorrectId — 이벤트의 inboxId 로 offerNow 가 호출됨")
    void onInboxReady_callsChannelOfferWithCorrectId() {
        when(channel.offerNow(999L)).thenReturn(true);

        handler.handle(new PgInboxReadyEvent(999L));

        verify(channel).offerNow(999L);
    }

    @Test
    @DisplayName("handle_FakeChannel_offerNow_검증 — FakeChannel 로 실제 offerNow 동작 검증 (채널 size 증가)")
    void handle_FakeChannel_offerNow_검증() {
        PgInboxChannel fakeChannel = new PgInboxChannel(1024, new SimpleMeterRegistry());
        fakeChannel.registerMetrics();
        InboxReadyEventHandler fakeHandler = new InboxReadyEventHandler(fakeChannel);

        fakeHandler.handle(new PgInboxReadyEvent(777L));

        assertThat(fakeChannel.size()).isEqualTo(1);
    }
}
