package study.paymentintegrationserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    private Product getByIdPessimistic(Long id) {
        return productRepository
                .findByIdPessimistic(id)
                .orElseThrow(() -> ProductException.of(ProductErrorMessage.NOT_FOUND));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Product reduceStockWithCommit(Long productId, Integer reduceStock) {
        return getByIdPessimistic(productId)
                .reduceStock(reduceStock);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Product increaseStockWithCommit(Long productId, Integer increaseStock) {
        return getByIdPessimistic(productId)
                .increaseStock(increaseStock);
    }
}
