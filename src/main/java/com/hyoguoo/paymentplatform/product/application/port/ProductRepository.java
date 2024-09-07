package com.hyoguoo.paymentplatform.product.application.port;

import com.hyoguoo.paymentplatform.product.domain.Product;
import java.util.Optional;

public interface ProductRepository {

    Optional<Product> findById(Long id);

    Optional<Product> findByIdPessimistic(Long id);

    Product saveOrUpdate(Product product);
}
