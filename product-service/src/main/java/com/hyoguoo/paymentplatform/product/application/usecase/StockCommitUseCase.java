package com.hyoguoo.paymentplatform.product.application.usecase;

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.product.application.port.out.PaymentStockCachePort;
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
 * <p>
 * S-2(StockCommitEvent 소비), S-3(Redis 직접 쓰기) 담당.
 * <p>
 * 불변식:
 * <ul>
 *   <li>eventUUID dedupe — EventDedupeStore.recordIfAbsent false 반환 시 즉시 return</li>
 *   <li>RDB UPDATE 성공 후에만 PaymentStockCachePort.setStock 호출 (원자성)</li>
 *   <li>RDB UPDATE 실패 시 예외 전파 — Redis SET 호출 금지</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockCommitUseCase {

    /**
     * dedupe TTL = Kafka 기본 retention(7일) + 1일 = 8일.
     * StockRestoreUseCase.DEDUPE_TTL 과 동일한 값 — StockCommitConsumer 에서
     * expiresAt null fallback 계산에도 사용한다.
     */
    public static final java.time.Duration DEDUPE_TTL = java.time.Duration.ofDays(8);

    private final StockRepository stockRepository;
    private final EventDedupeStore eventDedupeStore;
    private final PaymentStockCachePort paymentStockCachePort;

    /**
     * StockCommitEvent를 처리한다.
     * <p>
     * 처리 순서:
     * 1. eventUUID dedupe — 중복이면 return
     * 2. RDB: 재고 조회 → qty 감소 → save
     * 3. RDB UPDATE 성공 후 Redis SET
     *
     * <p>K3: orderId 타입을 String으로 변경 — producer(StockCommittedEvent)와 타입 통일.
     * payment-service의 orderId는 "order-xxx" 형태의 String이므로 long 파싱 불가.
     * orderId는 로깅·추적 용도로만 사용되므로 String 그대로 처리.
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

        int newStock = commitToRdb(productId, qty, orderId, eventUUID);

        paymentStockCachePort.setStock(productId, newStock);
        LogFmt.info(log, LogDomain.STOCK, EventType.STOCK_COMMIT_REDIS_DONE,
                () -> "productId=" + productId + " stock=" + newStock);
    }

    /**
     * RDB 재고를 qty만큼 감소시키고 저장한다.
     * 재고가 존재하지 않으면 {@link IllegalStateException}을 throw한다.
     *
     * @return 변경 후 재고 수량
     */
    private int commitToRdb(long productId, int qty, String orderId, String eventUUID) {
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
        return newQuantity;
    }
}
