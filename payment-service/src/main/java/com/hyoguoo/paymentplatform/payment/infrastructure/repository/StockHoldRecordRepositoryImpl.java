package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordCandidate;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordSnapshot;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.StockHoldRecordEntity;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * StockHoldRecordRepository 포트 JPA 어댑터.
 *
 * <p>{@link #openHold}는 호출마다 새 사이클 식별 값을 발급해, 확정을 제외한 나머지는 이미
 * 잡음이든 되돌림이든 상태와 무관하게 잡음으로 되돌린다. 이 갱신이 {@link #closeAsReverted}의
 * 뒤늦은 닫기 경합을 막는 유일한 장치다 — 캐시 되돌리기와 기록 닫기 사이에 새 차감이 들어와
 * openHold 가 다시 불리면 사이클 식별 값이 바뀌고, 옛 값을 쥔 뒤늦은 닫기는 반영되지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class StockHoldRecordRepositoryImpl implements StockHoldRecordRepository {

    private final JpaStockHoldRecordRepository jpaStockHoldRecordRepository;
    private final Clock clock;

    /**
     * 삽입 시도를 먼저 하고, 실패(행이 이미 있음)하면 재오픈을 시도한다.
     *
     * <p>순서가 반대(재오픈 먼저)면 행이 없을 때의 조건부 UPDATE 가 0건으로 끝나면서
     * MySQL InnoDB 가 REPEATABLE READ 팬텀 방지를 위해 갭 락을 잡고, 뒤이은 INSERT 가 그
     * 갭에서 삽입 의도 락을 요청하며 같은 순서로 실행되는 동시 호출과 데드락을 일으킨다.
     * 삽입을 먼저 하면 행이 없는 경우 갭 락 없이 바로 레코드 락으로 끝나 이 경합이 사라진다.
     * 삽입이 실패하면 그 시점엔 행이 반드시 존재하므로(유일 제약 위반), 재오픈은 항상 실제
     * 레코드를 대상으로 하는 단순 갱신이 되어 확정 상태만 아니면 성공한다.
     */
    @Override
    @Transactional
    public String openHold(String orderId, PaymentOrder paymentOrder) {
        Long productId = paymentOrder.getProductId();
        Integer quantity = paymentOrder.getQuantity();

        Optional<String> inserted = tryInsertAsNoise(orderId, productId, quantity);
        if (inserted.isPresent()) {
            return inserted.get();
        }

        Optional<String> reopened = tryReopenAsNoise(orderId, productId, quantity);
        if (reopened.isPresent()) {
            return reopened.get();
        }

        // 재오픈도 반영되지 않았다 — 행은 존재하지만 확정 상태라 조건에서 제외됐다는 뜻이다.
        return jpaStockHoldRecordRepository.findByOrderIdAndProductId(orderId, productId)
                .map(StockHoldRecordEntity::getCycleToken)
                .orElseThrow(() -> new IllegalStateException(
                        "StockHoldRecordRepositoryImpl.openHold: 삽입·재오픈 모두 실패했는데 조회도 안 되는 행 — "
                                + "orderId=" + orderId + ", productId=" + productId));
    }

    private Optional<String> tryReopenAsNoise(String orderId, Long productId, Integer quantity) {
        String cycleToken = UUID.randomUUID().toString();
        int affected = jpaStockHoldRecordRepository.reopenAsNoise(orderId, productId, quantity, cycleToken);
        return affected > 0 ? Optional.of(cycleToken) : Optional.empty();
    }

    private Optional<String> tryInsertAsNoise(String orderId, Long productId, Integer quantity) {
        String cycleToken = UUID.randomUUID().toString();
        int affected = jpaStockHoldRecordRepository.insertIgnoreNoise(
                orderId, productId, quantity, cycleToken, LocalDateTime.now(clock));
        return affected > 0 ? Optional.of(cycleToken) : Optional.empty();
    }

    @Override
    public Optional<StockHoldRecordSnapshot> findSnapshot(String orderId, PaymentOrder paymentOrder) {
        return jpaStockHoldRecordRepository.findByOrderIdAndProductId(orderId, paymentOrder.getProductId())
                .map(entity -> new StockHoldRecordSnapshot(entity.getStatus(), entity.getCycleToken()));
    }

    @Override
    @Transactional
    public boolean closeAsReverted(String orderId, PaymentOrder paymentOrder, String cycleToken) {
        int affected = jpaStockHoldRecordRepository.closeAsRevertedIfCycleMatches(
                orderId, paymentOrder.getProductId(), cycleToken);
        return affected > 0;
    }

    @Override
    @Transactional
    public void commitAllByOrderId(String orderId) {
        jpaStockHoldRecordRepository.commitAllByOrderId(orderId);
    }

    @Override
    public List<StockHoldRecordCandidate> findNoiseCandidates(int limit) {
        return jpaStockHoldRecordRepository
                .findByStatusOrderByIdAsc(StockHoldRecordStatus.NOISE, PageRequest.of(0, limit))
                .stream()
                .map(entity -> new StockHoldRecordCandidate(
                        entity.getOrderId(), entity.getProductId(), entity.getQuantity(), entity.getCycleToken()))
                .toList();
    }

    @Override
    public long countNoise() {
        return jpaStockHoldRecordRepository.countByStatus(StockHoldRecordStatus.NOISE);
    }
}
