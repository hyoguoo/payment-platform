package study.paymentintegrationserver.entity;

import jakarta.persistence.*;
import lombok.Getter;
import study.paymentintegrationserver.exception.ProductErrorMessage;
import study.paymentintegrationserver.exception.ProductException;

import java.math.BigDecimal;

@Getter
@Entity
@Table(name = "product")
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
        if (this.stock < reduceStock) {
            throw ProductException.of(ProductErrorMessage.NOT_ENOUGH_STOCK);
        }

        this.stock -= reduceStock;

        return this;
    }

    public Product increaseStock(Integer increaseStock) {
        this.stock += increaseStock;

        return this;
    }

    public void validateStock(Integer quantity) {
        if (this.stock < quantity) {
            throw ProductException.of(ProductErrorMessage.NOT_ENOUGH_STOCK);
        }
    }
}
