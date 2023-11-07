package study.paymentintegrationserver.entity;

import jakarta.persistence.*;
import lombok.*;
import study.paymentintegrationserver.exception.ProductErrorMessage;
import study.paymentintegrationserver.exception.ProductException;

import java.math.BigDecimal;

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
