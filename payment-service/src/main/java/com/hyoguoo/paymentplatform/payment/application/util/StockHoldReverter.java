package com.hyoguoo.paymentplatform.payment.application.util;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 확정 실패·격리 진입·관리자 종결·미회수 회수 판정 네 호출부가 공유하는 상품 단위 되돌리기.
 *
 * <p>주문에 속한 상품마다 선차감 흔적이 있을 때만 캐시를 되돌리고, 그 결과와 무관하게
 * 기록을 되돌림으로 닫는다. 캐시가 이미 처리됨을 돌려줘도(다른 경로가 먼저 되돌린 경우)
 * 기록은 닫는다 — 닫지 않으면 회수 작업이 매 주기 같은 기록을 집었다가 못 닫는 유령이 된다.
 *
 * <p>네 호출부가 모두 {@link #revertProductHold}를 거치므로 결과별 카운터를 여기 한 곳에서만
 * 세운다. 상품 단위로 쪼개져 호출 빈도가 상품 수만큼 늘어난 뒤라 정상 복원까지 매번 로그를
 * 남기면 시끄럽다 — 정상 복원은 카운터로만 세고, 흔적 없음·이미 처리됨처럼 되짚어볼 값이 있는
 * 결과에만 경고 로그를 남긴다.
 */
@Slf4j
@Component
public class StockHoldReverter {

    static final String REVERT_RESULT_COUNTER_NAME = "stock_hold_revert.result_total";
    static final String RESULT_TAG_REVERTED = "reverted";
    static final String RESULT_TAG_NO_TRACE = "no_trace";
    static final String RESULT_TAG_ALREADY_DONE = "already_done";

    private final Counter revertedCounter;
    private final Counter noTraceCounter;
    private final Counter alreadyDoneCounter;

    public StockHoldReverter(MeterRegistry meterRegistry) {
        this.revertedCounter = registerResultCounter(meterRegistry, RESULT_TAG_REVERTED);
        this.noTraceCounter = registerResultCounter(meterRegistry, RESULT_TAG_NO_TRACE);
        this.alreadyDoneCounter = registerResultCounter(meterRegistry, RESULT_TAG_ALREADY_DONE);
    }

    private static Counter registerResultCounter(MeterRegistry meterRegistry, String resultTag) {
        return Counter.builder(REVERT_RESULT_COUNTER_NAME)
                .description("상품 단위 재고 되돌리기 결과 건수 — reverted(복원됨)/no_trace(흔적 없음)/already_done(이미 처리됨)")
                .tag("result", resultTag)
                .register(meterRegistry);
    }

    public void revertEachProductHold(
            PaymentEvent paymentEvent,
            StockCachePort stockCachePort,
            StockHoldRecordRepository stockHoldRecordRepository) {
        String orderId = paymentEvent.getOrderId();
        for (PaymentOrder order : paymentEvent.getPaymentOrderList()) {
            stockHoldRecordRepository.findSnapshot(orderId, order)
                    .ifPresent(snapshot -> revertProductHold(
                            orderId, order, snapshot.cycleToken(), stockCachePort, stockHoldRecordRepository));
        }
    }

    /**
     * 상품 하나에 대한 되돌리기 — 이미 조회해 둔 사이클 식별 값을 그대로 받는다.
     *
     * <p>{@link #revertEachProductHold}는 {@code PaymentEvent}의 상품 목록을 반복하며 매 상품마다
     * {@code findSnapshot}으로 사이클 식별 값을 새로 조회하지만, 미회수 회수 판정(배치 스캔으로
     * 후보를 이미 사이클 식별 값과 함께 들고 있는 경우)처럼 그 조회가 이미 끝난 호출부를 위해
     * 공개 메서드로 둔다.
     */
    public void revertProductHold(
            String orderId,
            PaymentOrder order,
            String cycleToken,
            StockCachePort stockCachePort,
            StockHoldRecordRepository stockHoldRecordRepository) {
        StockRecoveryCompensationResult result = stockCachePort.compensateIfDecremented(orderId, order);
        recordResult(result, orderId, order);
        beforeClose(orderId, order);
        stockHoldRecordRepository.closeAsReverted(orderId, order, cycleToken);
    }

    /**
     * 캐시 되돌리기와 기록 닫기 사이의 지연 주입 지점 — 운영 기본값은 즉시 통과하는 no-op이다.
     * 이 창에 같은 조합의 새 차감이 끼어들어도 사이클 식별 값 조건부 닫기가 그 차감을 덮지
     * 않는지, 테스트가 서브클래싱으로 지연을 걸어 결정적으로 재현할 수 있게 열어 둔다.
     */
    protected void beforeClose(String orderId, PaymentOrder order) {
        // no-op — 운영 경로에서는 즉시 통과한다
    }

    private void recordResult(StockRecoveryCompensationResult result, String orderId, PaymentOrder order) {
        switch (result) {
            case OK -> revertedCounter.increment();
            case NO_DECREMENT -> {
                noTraceCounter.increment();
                LogFmt.warn(log, LogDomain.PAYMENT, EventType.STOCK_HOLD_REVERT_ANOMALY,
                        () -> "재고 되돌리기 흔적 없음 — orderId=" + orderId + " productId=" + order.getProductId());
            }
            case ALREADY_DONE -> {
                alreadyDoneCounter.increment();
                LogFmt.warn(log, LogDomain.PAYMENT, EventType.STOCK_HOLD_REVERT_ANOMALY,
                        () -> "재고 되돌리기 이미 처리됨 — orderId=" + orderId + " productId=" + order.getProductId());
            }
        }
    }
}
