package com.hyoguoo.paymentplatform.product.application.usecase;

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository;
import com.hyoguoo.paymentplatform.product.core.common.log.EventType;
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.product.domain.Stock;
import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
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
 *   <li>차감 후 잔량이 음수가 되면 저장하지 않고 {@link ProductStockException} 으로 즉시 실패
 *   (두 번째 방어선 — 첫 번째는 payment-service 재고 선차감 게이트).</li>
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
     * @param now       현재 시각 — 만료 경계 판정 기준 (호출자 주입, 헥사고날 Clock 권한)
     * @param expiresAt dedupe TTL 만료 시각
     * @throws IllegalStateException 해당 상품 재고가 존재하지 않을 경우
     */
    @Transactional
    public void commit(String eventUUID, String orderId, long productId, int qty, Instant now, Instant expiresAt) {
        boolean firstSeen = eventDedupeStore.recordIfAbsent(eventUUID, now, expiresAt);
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
     * 차감 후 잔량이 음수가 되면 저장하지 않고 {@link ProductStockException}을 throw한다.
     *
     * <p><b>두 번째 방어선</b> — 초과 판매를 막는 장치가 payment-service 의 재고 선차감 게이트
     * (캐시 기반) 하나뿐이었다. 게이트를 우회하는 경로(관리자 수동 조정, 게이트-DB 정합이
     * 깨진 상태에서의 확정 등)가 생겨도 이 가드가 DB 잔량이 음수로 내려가는 것 자체를 막는다.
     *
     * <p><b>동시성 안전 근거</b> — 이 메서드는 조회 후 감산해 저장하는 read-then-write 이고
     * 그 자체에는 잠금이 없다. 그런데도 안전한 이유는 재고 확정 통지(payment-service 가 발행하는
     * {@code payment.events.stock-committed})가 상품번호를 Kafka 메시지 키로 써서, 같은 상품에 대한
     * 확정 커밋이 항상 같은 파티션의 단일 컨슈머 스레드에서 순서대로 처리되기 때문이다 — 즉
     * "같은 상품의 read-then-write 는 한 줄로 직렬화된다" 는 파티셔닝 전제가 잠금 없는 이 코드의
     * 유일한 안전 근거다. <b>이 나눔 기준(상품번호 파티션 키)을 바꾸면</b>(예: 처리량을 위해 다른 키로
     * 재해시하거나 컨슈머를 상품 단위 이상으로 병렬화하면) 같은 상품에 대한 두 확정이 동시에 조회한
     * 옛 잔량을 각자 감산해 저장하는 lost update 가 재발하고, 이 가드가 막으려던 초과 판매 경로가
     * 그대로 되살아난다.
     */
    private void commitToRdb(long productId, int qty, String orderId, String eventUUID) {
        Stock current = stockRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalStateException(
                        "재고 정보를 찾을 수 없음 productId=" + productId
                                + " orderId=" + orderId
                                + " eventUUID=" + eventUUID));

        int newQuantity = current.getQuantity() - qty;
        if (newQuantity < 0) {
            LogFmt.warn(log, LogDomain.STOCK, EventType.STOCK_COMMIT_INSUFFICIENT,
                    () -> "productId=" + productId + " orderId=" + orderId + " eventUUID=" + eventUUID
                            + " current=" + current.getQuantity() + " qty=" + qty);
            throw ProductStockException.of(ProductErrorCode.NOT_ENOUGH_STOCK);
        }

        Stock updated = Stock.allArgsBuilder()
                .productId(productId)
                .quantity(newQuantity)
                .allArgsBuild();
        stockRepository.save(updated);

        LogFmt.info(log, LogDomain.STOCK, EventType.STOCK_COMMIT_RDB_DONE,
                () -> "productId=" + productId + " " + current.getQuantity() + " -> " + newQuantity);
    }
}
