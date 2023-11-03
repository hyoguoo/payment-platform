package study.paymentintegrationserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import study.paymentintegrationserver.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
