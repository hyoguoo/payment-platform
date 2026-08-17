package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.infrastructure.entity.StockHoldRecordEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaStockHoldRecordRepository extends JpaRepository<StockHoldRecordEntity, Long> {

    Optional<StockHoldRecordEntity> findByOrderIdAndProductId(String orderId, Long productId);

    /**
     * 확정 상태를 제외한 나머지는 상태와 무관하게 잡음으로 되돌리며 사이클 식별 값을 새로
     * 발급한다 — 이미 잡음인 행에 다시 호출돼도 값을 갱신해, 뒤늦은 닫기가 옛 값을 쥔 채
     * 반영되는 것을 막는다. 행이 아직 없으면 반영 행 수 0.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE StockHoldRecordEntity e SET e.status = 'NOISE', e.quantity = :quantity, e.cycleToken = :cycleToken "
            + "WHERE e.orderId = :orderId AND e.productId = :productId AND e.status <> 'COMMITTED'")
    int reopenAsNoise(@Param("orderId") String orderId, @Param("productId") Long productId,
                      @Param("quantity") Integer quantity, @Param("cycleToken") String cycleToken);

    /**
     * order_id + product_id UNIQUE INSERT IGNORE — 반영 행 수를 반환한다.
     * IGNORE 로 중복 행이 있어도 예외 없이 통과하고, 반영 행 수 0 으로 호출자가 중복 여부를 판정한다.
     * native query 사용 이유: JPQL 은 INSERT IGNORE 를 지원하지 않는다.
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO stock_hold_record "
            + "(order_id, product_id, quantity, status, cycle_token, created_at, updated_at) "
            + "VALUES (:orderId, :productId, :quantity, 'NOISE', :cycleToken, :now, :now)",
            nativeQuery = true)
    int insertIgnoreNoise(@Param("orderId") String orderId, @Param("productId") Long productId,
                          @Param("quantity") Integer quantity, @Param("cycleToken") String cycleToken,
                          @Param("now") LocalDateTime now);

    /**
     * 조회 시점 사이클 식별 값이 지금도 일치할 때만 되돌림으로 닫는다. 확정 상태는 조건에서
     * 제외해, 사이클 식별 값이 우연히 같더라도 확정 행을 건드리지 않는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE StockHoldRecordEntity e SET e.status = 'REVERTED' "
            + "WHERE e.orderId = :orderId AND e.productId = :productId "
            + "AND e.cycleToken = :cycleToken AND e.status <> 'COMMITTED'")
    int closeAsRevertedIfCycleMatches(@Param("orderId") String orderId, @Param("productId") Long productId,
                                      @Param("cycleToken") String cycleToken);

    /**
     * 그 주문의 잡음 상태 기록을 모두 확정으로 바꾼다. 벤더 승인 반영 트랜잭션 안에서
     * 호출된다는 것이 호출자 측 전제다 — 그 트랜잭션은 같은 영속성 컨텍스트 안에서
     * {@code PaymentEvent} 를 DONE 으로 전이시킨 변경도 함께 들고 있다.
     *
     * <p>{@code flushAutomatically = true} 가 필수다. {@code clearAutomatically = true} 는 이
     * 벌크 UPDATE 실행 직후 영속성 컨텍스트 전체를 비우는데, flush 없이 비우면 아직 flush 되지
     * 않은 PaymentEvent 의 DONE 전이가 영속성 컨텍스트와 함께 버려져 DB 에 반영되지 않는다 —
     * 결제가 조용히 IN_PROGRESS 로 남는 사고가 된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE StockHoldRecordEntity e SET e.status = 'COMMITTED' WHERE e.orderId = :orderId AND e.status = 'NOISE'")
    int commitAllByOrderId(@Param("orderId") String orderId);
}
