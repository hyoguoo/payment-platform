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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재고 선차감 기록(상품 단위) — 주문번호·상품번호 조합에 유일 제약이 걸려 있다.
 * 상태 전이(재오픈 / 닫기 / 확정)는 조건부 UPDATE 로 이루어지므로 이 클래스에 전이 메서드를
 * 두지 않는다 — {@code StockHoldRecordRepositoryImpl} 이 JPA 쿼리로 직접 수행한다.
 *
 * <p>유일 제약은 Flyway 마이그레이션({@code V7__stock_hold_record.sql})이 정본이지만, 스키마를
 * 자동 생성하는 통합 테스트 기반({@code ddl-auto: create-drop})에서도 같은 제약이 서도록 여기에도
 * 명시한다 — 양쪽이 어긋나면 그 기반 위에서는 재오픈 대신 중복 삽입이 조용히 일어난다.
 */
@Getter
@Entity
@Table(
        name = "stock_hold_record",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_stock_hold_record_order_product",
                        columnNames = {"order_id", "product_id"}
                )
        },
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
}
