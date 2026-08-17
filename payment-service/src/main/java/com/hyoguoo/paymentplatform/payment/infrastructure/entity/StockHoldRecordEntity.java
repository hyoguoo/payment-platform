package com.hyoguoo.paymentplatform.payment.infrastructure.entity;

import com.hyoguoo.paymentplatform.payment.core.common.infrastructure.BaseEntity;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재고 선차감 기록(상품 단위) — 주문번호·상품번호 조합에 유일 제약이 걸려 있다.
 * 상태 전이(재오픈 / 닫기 / 확정)는 조건부 UPDATE 로 이루어지므로 이 클래스에 전이 메서드를
 * 두지 않는다 — {@code StockHoldRecordRepositoryImpl} 이 JPA 쿼리로 직접 수행한다.
 */
@Getter
@Entity
@Table(
        name = "stock_hold_record",
        indexes = {
                @Index(name = "idx_stock_hold_record_status", columnList = "status")
        }
)
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StockHoldRecordEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_id", nullable = false, length = 100)
    private String orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StockHoldRecordStatus status;

    @Column(name = "cycle_token", nullable = false, length = 36)
    private String cycleToken;

    /**
     * 새 사이클을 여는 잡음 상태 행을 만든다.
     * 외부 호출자는 이 factory 를 거친다 — {@code builder()} 직접 호출 금지.
     *
     * @param orderId    결제 주문 ID
     * @param productId  상품 ID
     * @param quantity   차감 수량
     * @param cycleToken 이번 사이클을 식별하는 값
     */
    public static StockHoldRecordEntity openNoise(String orderId, Long productId, Integer quantity, String cycleToken) {
        return StockHoldRecordEntity.builder()
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .status(StockHoldRecordStatus.NOISE)
                .cycleToken(cycleToken)
                .build();
    }
}
