package study.paymentintegrationserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import study.paymentintegrationserver.entity.Product;
import study.paymentintegrationserver.exception.ProductErrorMessage;
import study.paymentintegrationserver.exception.ProductException;
import study.paymentintegrationserver.repository.ProductRepository;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    public Product getById(Long id) {
        return productRepository
                .findById(id)
                .orElseThrow(() -> ProductException.of(ProductErrorMessage.NOT_FOUND));
    }

    public Product reduceStock(Long productId, Integer reduceStock) {
        return getById(productId)
                .reduceStock(reduceStock);
    }
}
