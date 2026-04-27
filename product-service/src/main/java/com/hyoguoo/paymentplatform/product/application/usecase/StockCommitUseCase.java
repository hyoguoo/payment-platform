package com.hyoguoo.paymentplatform.product.application.usecase;

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository;
import com.hyoguoo.paymentplatform.product.core.common.log.EventType;
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.product.domain.Stock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 확정 커밋 유스케이스.
 *
 * <p>product RDB 가 재고의 single source of truth 이며, redis-stock 캐시는 payment-service 가
 * 자기 책임으로 관리한다. 본 use case 는 RDB 차감만 수행하고 Redis 동기화 책임은 가지지 않는다.
 *
 * <p>불변식:
 * <ul>
 *   <li>eventUUID dedupe — EventDedupeStore.recordIfAbsent 가 false 를 반환하면 즉시 return.</li>
 *   <li>재고 row 미존재 시 IllegalStateException 으로 즉시 실패.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockCommitUseCase {

    /**
     * dedupe TTL = Kafka 기본 retention(7일) + 1일 = 8일.
     * StockCommitConsumer 의 expiresAt null fallback 계산에도 사용한다.
     * payment-service {@code EventDedupeStoreRedisAdapter} 의 P8D 와 정렬.
     */
    public static final java.time.Duration DEDUPE_TTL = java.time.Duration.ofDays(8);

    private final StockRepository stockRepository;
    private final EventDedupeStore eventDedupeStore;

    /**
     * StockCommitEvent 를 처리한다.
     *
     * <p>처리 순서:
     * <ol>
     *   <li>eventUUID dedupe — 중복이면 return.</li>
     *   <li>RDB 재고 row 조회 → qty 감소 → save.</li>
     * </ol>
     *
     * <p>orderId 는 producer(StockCommittedEvent) 와 타입을 통일해 String 으로 둔다 —
     * payment-service 의 orderId 는 "order-xxx" 형태라 long 으로 파싱할 수 없다.
     * 여기서는 로깅·추적 용도로만 사용한다.
     *
     * @param eventUUID 이벤트 식별자 (dedupe 키)
     * @param orderId   주문 ID (String, 추적 메타데이터)
     * @param productId 상품 ID
     * @param qty       확정 차감 수량
     * @param expiresAt dedupe TTL 만료 시각
     * @throws IllegalStateException 해당 상품 재고가 존재하지 않을 경우
     */
    @Transactional
    public void commit(String eventUUID, String orderId, long productId, int qty, Instant expiresAt) {
        boolean firstSeen = eventDedupeStore.recordIfAbsent(eventUUID, expiresAt);
        if (!firstSeen) {
            LogFmt.info(log, LogDomain.STOCK, EventType.STOCK_COMMIT_DUPLICATE,
                    () -> "eventUUID=" + eventUUID + " orderId=" + orderId + " productId=" + productId);
            return;
        }

        commitToRdb(productId, qty, orderId, eventUUID);
    }

    /**
     * RDB 재고를 qty만큼 감소시키고 저장한다.
     * 재고가 존재하지 않으면 {@link IllegalStateException}을 throw한다.
     */
    private void commitToRdb(long productId, int qty, String orderId, String eventUUID) {
        Stock current = stockRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalStateException(
                        "재고 정보를 찾을 수 없음 productId=" + productId
                                + " orderId=" + orderId
                                + " eventUUID=" + eventUUID));

        int newQuantity = current.getQuantity() - qty;
        Stock updated = Stock.allArgsBuilder()
                .productId(productId)
                .quantity(newQuantity)
                .allArgsBuild();
        stockRepository.save(updated);

        LogFmt.info(log, LogDomain.STOCK, EventType.STOCK_COMMIT_RDB_DONE,
                () -> "productId=" + productId + " " + current.getQuantity() + " -> " + newQuantity);
    }
}
