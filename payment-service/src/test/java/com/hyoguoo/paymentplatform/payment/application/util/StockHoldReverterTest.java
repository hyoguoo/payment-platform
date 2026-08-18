package com.hyoguoo.paymentplatform.payment.application.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * StockHoldReverter 단위 검증 — 되돌리기 네 경로(확정 실패·격리 진입·관리자 종결·회수 판정)가
 * 공유하는 관측 지점의 결과별 카운터와 이상 결과 로그.
 */
@DisplayName("StockHoldReverter")
class StockHoldReverterTest {

    private static final String ORDER_ID = "order-revert-001";
    private static final Long PRODUCT_ID = 501L;
    private static final String CYCLE_TOKEN = "cycle-revert-001";
    private static final String COUNTER_NAME = "stock_hold_revert.result_total";

    private StockCachePort stockCachePort;
    private StockHoldRecordRepository stockHoldRecordRepository;
    private SimpleMeterRegistry meterRegistry;
    private StockHoldReverter sut;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger sutLogger;

    private PaymentOrder order;

    @BeforeEach
    void setUp() {
        stockCachePort = Mockito.mock(StockCachePort.class);
        stockHoldRecordRepository = Mockito.mock(StockHoldRecordRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        sut = new StockHoldReverter(meterRegistry);

        sutLogger = (Logger) LoggerFactory.getLogger(StockHoldReverter.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        sutLogger.addAppender(logAppender);

        order = PaymentOrder.allArgsBuilder()
                .orderId(ORDER_ID)
                .productId(PRODUCT_ID)
                .quantity(2)
                .allArgsBuild();
    }

    @AfterEach
    void tearDown() {
        sutLogger.detachAppender(logAppender);
    }

    private double counterCount(String resultTag) {
        Counter counter = meterRegistry.find(COUNTER_NAME).tag("result", resultTag).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("정상 복원(OK)은 reverted 카운터만 올리고 로그를 남기지 않는다")
    void 정상_복원은_카운터만_오르고_로그없음() {
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willReturn(StockRecoveryCompensationResult.OK);

        sut.revertProductHold(ORDER_ID, order, CYCLE_TOKEN, stockCachePort, stockHoldRecordRepository);

        assertThat(counterCount("reverted")).isEqualTo(1.0);
        assertThat(counterCount("no_trace")).isEqualTo(0.0);
        assertThat(counterCount("already_done")).isEqualTo(0.0);
        assertThat(logAppender.list).isEmpty();
        then(stockHoldRecordRepository).should().closeAsReverted(ORDER_ID, order, CYCLE_TOKEN);
    }

    @Test
    @DisplayName("흔적 없음(NO_DECREMENT)은 no_trace 카운터를 올리고 경고 로그를 남긴다")
    void 흔적없음은_카운터_오르고_경고로그() {
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willReturn(StockRecoveryCompensationResult.NO_DECREMENT);

        sut.revertProductHold(ORDER_ID, order, CYCLE_TOKEN, stockCachePort, stockHoldRecordRepository);

        assertThat(counterCount("no_trace")).isEqualTo(1.0);
        assertThat(counterCount("reverted")).isEqualTo(0.0);
        assertThat(logAppender.list).hasSize(1);
        assertThat(logAppender.list.get(0).getFormattedMessage()).contains(ORDER_ID, PRODUCT_ID.toString());
        then(stockHoldRecordRepository).should().closeAsReverted(ORDER_ID, order, CYCLE_TOKEN);
    }

    @Test
    @DisplayName("이미 처리됨(ALREADY_DONE)은 already_done 카운터를 올리고 경고 로그를 남긴다")
    void 이미처리됨은_카운터_오르고_경고로그() {
        given(stockCachePort.compensateIfDecremented(ORDER_ID, order))
                .willReturn(StockRecoveryCompensationResult.ALREADY_DONE);

        sut.revertProductHold(ORDER_ID, order, CYCLE_TOKEN, stockCachePort, stockHoldRecordRepository);

        assertThat(counterCount("already_done")).isEqualTo(1.0);
        assertThat(counterCount("reverted")).isEqualTo(0.0);
        assertThat(logAppender.list).hasSize(1);
        then(stockHoldRecordRepository).should().closeAsReverted(ORDER_ID, order, CYCLE_TOKEN);
    }

    @Test
    @DisplayName("결과와 무관하게 기록 닫기는 항상 호출된다")
    void 결과와_무관하게_기록을_닫는다() {
        given(stockCachePort.compensateIfDecremented(any(), any()))
                .willReturn(StockRecoveryCompensationResult.NO_DECREMENT);

        sut.revertProductHold(ORDER_ID, order, CYCLE_TOKEN, stockCachePort, stockHoldRecordRepository);

        then(stockHoldRecordRepository).should().closeAsReverted(ORDER_ID, order, CYCLE_TOKEN);
    }
}
