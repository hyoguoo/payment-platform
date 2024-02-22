package study.paymentintegrationserver.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import study.paymentintegrationserver.exception.ProductErrorMessage;
import study.paymentintegrationserver.exception.ProductException;

@Getter
@Entity
@Builder
@Table(name = "product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "stock", nullable = false)
    private Integer stock;

    public BigDecimal calculateTotalPrice(Integer quantity) {
        return this.price.multiply(BigDecimal.valueOf(quantity));
    }

    public Product reduceStock(Integer reduceStock) {
        if (reduceStock < 0) {
            throw ProductException.of(ProductErrorMessage.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK);
        }
        if (this.stock < reduceStock) {
            throw ProductException.of(ProductErrorMessage.NOT_ENOUGH_STOCK);
        }

        this.stock -= reduceStock;

        return this;
    }

    public Product increaseStock(Integer increaseStock) {
        if (increaseStock < 0) {
            throw ProductException.of(ProductErrorMessage.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK);
        }
        this.stock += increaseStock;

        return this;
    }

    public void validateStock(Integer quantity) {
        if (quantity < 0) {
            throw ProductException.of(ProductErrorMessage.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK);
        }

        if (this.stock < quantity) {
            throw ProductException.of(ProductErrorMessage.NOT_ENOUGH_STOCK);
        }
    }
}
