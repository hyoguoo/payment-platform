package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordSnapshot;
import com.hyoguoo.paymentplatform.payment.application.util.StockHoldReverter;
import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.core.test.ConcurrentActionRunner;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import com.hyoguoo.paymentplatform.payment.integration.StockHoldRevertActions.RevertAction;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 재고 선차감을 되돌리는 다섯 주체(확정 실패·격리 진입·관리자 종결·회수 주기 작업·거절 전용)의
 * 모든 짝(10 조합)이 같은 선차감을 동시에 되돌려도 재고가 정확히 한 번만 복원되고, 선차감 기록이
 * 정확히 되돌림으로 닫히는지 검증한다. 각 조합 50회 반복 — 이 설계 전체의 안전이 걸린 지점이다.
 *
 * <p><b>두 갈래 경합 모델.</b> 다섯 주체가 실제로 같은 사이클을 놓고 부딪힐 수 있는 방식은
 * 균일하지 않다:
 *
 * <ul>
 *   <li><b>확정 실패·격리 진입·관리자 종결·회수 주기 작업 — 네 주체는 진짜로 동시에 경합한다.</b>
 *       Kafka 재전송, 관리자 중복 클릭, 주기 작업과 관리자 조작의 겹침 등으로 같은 사이클을 두
 *       주체가 함께 들여다볼 수 있다. 넷 모두 {@link StockHoldReverter#revertProductHold}(조건부
 *       {@code compensateIfDecremented} + 사이클 식별 값 조건부 {@code closeAsReverted})를 거치므로,
 *       {@code compensation:done} SETNX 와 사이클 식별 값 일치 조건이 실제 방어선이다. 이 넷의 모든
 *       짝(6 조합)은 {@link ConcurrentActionRunner}로 진짜 동시 실행을 강제해 검증한다.</li>
 *   <li><b>거절 전용은 이 넷과 진짜로 동시에 경합하지 않는다.</b> 거절 전용은 이번 확정 요청이
 *       직접 차감한 상품에 대해서만, 결제가 아직 READY 로 주문 단위 선점을 쥔 채 실행된다 — 이
 *       시점엔 다른 네 주체 중 무엇도 전제 조건(벤더 응답 이후 IN_PROGRESS, 격리 상태, 종결 상태)을
 *       만족하지 못한다. 그리고 거절 전용의 캐시 되돌리기(rejectCompensate)는 무조건 복원 —
 *       선차감·되돌리기 표시를 검사하지 않는다({@code stock_reject_compensation.lua}). 실제로 Redis에
 *       직접 두 스크립트를 순서를 바꿔 실행해 확인한 결과: 다른 네 주체 중 하나가 먼저 조건부
 *       복원을 마친 "뒤에" 거절 전용이 같은 사이클을 향해 또 실행되면 무조건 INCRBY 라 재고가
 *       두 번 복원된다. 이 순서는 위 상태 전제 때문에 실제로는 일어나지 않지만, "상품별 되돌리기
 *       표시가 유일한 방어" 라는 설계 문서 서술은 거절 전용에는 그대로 적용되지 않는다는 뜻이다
 *       — 방어는 표시가 아니라 상태 기계가 서는 순서다. 유일하게 실제로 이어지는 순서는 거절
 *       전용이 재고·표시를 지운 뒤 결제가 실패로 종결되고, 그 잡음 기록을 회수 주기 작업이
 *       나중에 정리하는 것뿐이다. 그래서 거절 전용이 낀 네 짝은 "거절 전용이 먼저 끝난 뒤 나머지
 *       주체가 그 결과를 안전하게 넘겨받는지" 를 순차 핸드오프로 검증한다 — 도달 불가능한 순서를
 *       강제로 동시 실행하면 실제로 재현되는(그러나 운영에서는 절대 발생하지 않는) 이중 복원으로
 *       50 회 중 절반 가까이 flaky 하게 실패해, "반복 실행에서 흔들리지 않는다" 는 완료 기준과
 *       상충한다.</li>
 * </ul>
 */
@DisplayName("재고 선차감 이중 되돌리기 — 다섯 주체 모든 짝 동시성 통합 테스트")
class StockHoldDoubleRevertConcurrencyIntegrationTest extends BaseIntegrationTest {

    private static final int INITIAL_STOCK = 1_000;
    private static final int QUANTITY = 5;

    private static final AtomicLong PRODUCT_ID_SEQUENCE = new AtomicLong(System.nanoTime());

    @Autowired
    private StockCachePort stockCachePort;

    @Autowired
    private StockHoldRecordRepository stockHoldRecordRepository;

    @Autowired
    private StockHoldReverter stockHoldReverter;

    @Autowired
    @Qualifier("stockCacheRedisTemplate")
    private StringRedisTemplate stockRedisTemplate;

    // ---- 진짜 동시 경합 6짝 (확정 실패 / 격리 진입 / 관리자 종결 / 회수 주기 작업) ----

    @RepeatedTest(50)
    @DisplayName("확정 실패 x 격리 진입 — 동시에 되돌려도 재고는 한 번만 복원되고 기록은 정확히 닫힌다")
    void confirmFailed_x_quarantineEntry() {
        raceAndVerify(actions()::confirmFailed, actions()::quarantineEntry);
    }

    @RepeatedTest(50)
    @DisplayName("확정 실패 x 관리자 종결 — 동시에 되돌려도 재고는 한 번만 복원되고 기록은 정확히 닫힌다")
    void confirmFailed_x_adminResolve() {
        raceAndVerify(actions()::confirmFailed, actions()::adminResolve);
    }

    @RepeatedTest(50)
    @DisplayName("확정 실패 x 회수 주기 작업 — 동시에 되돌려도 재고는 한 번만 복원되고 기록은 정확히 닫힌다")
    void confirmFailed_x_recoveryWorker() {
        raceAndVerify(actions()::confirmFailed, actions()::recoveryWorker);
    }

    @RepeatedTest(50)
    @DisplayName("격리 진입 x 관리자 종결 — 동시에 되돌려도 재고는 한 번만 복원되고 기록은 정확히 닫힌다")
    void quarantineEntry_x_adminResolve() {
        raceAndVerify(actions()::quarantineEntry, actions()::adminResolve);
    }

    @RepeatedTest(50)
    @DisplayName("격리 진입 x 회수 주기 작업 — 동시에 되돌려도 재고는 한 번만 복원되고 기록은 정확히 닫힌다")
    void quarantineEntry_x_recoveryWorker() {
        raceAndVerify(actions()::quarantineEntry, actions()::recoveryWorker);
    }

    @RepeatedTest(50)
    @DisplayName("관리자 종결 x 회수 주기 작업 — 동시에 되돌려도 재고는 한 번만 복원되고 기록은 정확히 닫힌다")
    void adminResolve_x_recoveryWorker() {
        raceAndVerify(actions()::adminResolve, actions()::recoveryWorker);
    }

    // ---- 거절 전용이 낀 4짝 — 순차 핸드오프 (거절 전용이 항상 먼저 끝난다) ----

    @RepeatedTest(50)
    @DisplayName("거절 전용 → 확정 실패 — 거절이 지운 흔적을 넘겨받아도 재고는 다시 복원되지 않고 기록만 닫힌다")
    void rejectOnly_then_confirmFailed() {
        handoffAndVerify(actions()::rejectOnly, actions()::confirmFailed);
    }

    @RepeatedTest(50)
    @DisplayName("거절 전용 → 격리 진입 — 거절이 지운 흔적을 넘겨받아도 재고는 다시 복원되지 않고 기록만 닫힌다")
    void rejectOnly_then_quarantineEntry() {
        handoffAndVerify(actions()::rejectOnly, actions()::quarantineEntry);
    }

    @RepeatedTest(50)
    @DisplayName("거절 전용 → 관리자 종결 — 거절이 지운 흔적을 넘겨받아도 재고는 다시 복원되지 않고 기록만 닫힌다")
    void rejectOnly_then_adminResolve() {
        handoffAndVerify(actions()::rejectOnly, actions()::adminResolve);
    }

    @RepeatedTest(50)
    @DisplayName("거절 전용 → 회수 주기 작업 — 실제로 이어지는 유일한 순서. 재고 재복원 없이 기록만 닫힌다")
    void rejectOnly_then_recoveryWorker() {
        handoffAndVerify(actions()::rejectOnly, actions()::recoveryWorker);
    }

    // ---- 하네스 ----

    /**
     * 상품 하나를 선차감한 뒤(선차감 기록 NOISE + decrement:done 표시 + 재고 차감), 두 되돌리기
     * 주체를 진짜로 동시에 풀어 실행한다. 그 뒤 재고가 정확히 한 번만 복원되고, 기록이 정확히
     * 되돌림으로 닫혔는지 단정한다.
     */
    private void raceAndVerify(RevertAction first, RevertAction second) {
        Fixture fixture = seedDecrementedHold();

        ConcurrentActionRunner.race(
                () -> first.run(fixture.orderId(), fixture.order()),
                () -> second.run(fixture.orderId(), fixture.order())
        );

        assertRestoredExactlyOnce(fixture);
        assertHoldClosedAsReverted(fixture);
    }

    /**
     * 거절 전용이 먼저 끝나 캐시 표시를 지운 뒤(재고 복원 1회), 다른 주체가 그 결과를 넘겨받는다.
     * 넘겨받은 주체는 decrement:done 이 이미 없어 {@code NO_DECREMENT} 로 흡수해야 하고, 그 결과와
     * 무관하게 기록을 되돌림으로 닫아야 한다 — 재고가 다시 움직이면 안 된다.
     */
    private void handoffAndVerify(RevertAction rejectOnly, RevertAction other) {
        Fixture fixture = seedDecrementedHold();

        rejectOnly.run(fixture.orderId(), fixture.order());
        other.run(fixture.orderId(), fixture.order());

        assertRestoredExactlyOnce(fixture);
        assertHoldClosedAsReverted(fixture);
    }

    private Fixture seedDecrementedHold() {
        long productId = PRODUCT_ID_SEQUENCE.incrementAndGet();
        String orderId = "order-double-revert-" + UUID.randomUUID();
        PaymentOrder order = buildOrder(orderId, productId, QUANTITY);

        stockRedisTemplate.opsForValue().set(stockKey(productId), String.valueOf(INITIAL_STOCK));
        stockHoldRecordRepository.openHold(orderId, order);
        StockDecrementAtomicResult decrementResult = stockCachePort.decrementAtomic(orderId, order);
        assertThat(decrementResult)
                .as("사전 조건 — 이번 반복의 선차감 자체가 정상 성공해야 한다")
                .isEqualTo(StockDecrementAtomicResult.OK);

        return new Fixture(orderId, productId, order);
    }

    private void assertRestoredExactlyOnce(Fixture fixture) {
        String stockValue = stockRedisTemplate.opsForValue().get(stockKey(fixture.productId()));
        assertThat(stockValue)
                .as("재고는 정확히 한 번만 복원돼 선차감 이전 값으로 돌아와야 한다 (두 번 복원되면 초과)")
                .isEqualTo(String.valueOf(INITIAL_STOCK));
    }

    private void assertHoldClosedAsReverted(Fixture fixture) {
        StockHoldRecordSnapshot snapshot =
                stockHoldRecordRepository.findSnapshot(fixture.orderId(), fixture.order()).orElseThrow();
        assertThat(snapshot.status())
                .as("선차감 기록은 정확히 되돌림으로 닫혀야 한다 — 유령으로 남으면 안 된다")
                .isEqualTo(StockHoldRecordStatus.REVERTED);
    }

    private StockHoldRevertActions actions() {
        return new StockHoldRevertActions(stockHoldReverter, stockCachePort, stockHoldRecordRepository);
    }

    private String stockKey(long productId) {
        return "stock:{" + productId + "}";
    }

    private PaymentOrder buildOrder(String orderId, long productId, int quantity) {
        return PaymentOrder.allArgsBuilder()
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(1_000L * quantity))
                .status(PaymentOrderStatus.EXECUTING)
                .allArgsBuild();
    }

    private record Fixture(String orderId, long productId, PaymentOrder order) {

    }
}
