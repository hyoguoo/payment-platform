package com.hyoguoo.paymentplatform.product.application;

import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.exception.ProductFoundException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import com.hyoguoo.paymentplatform.product.presentation.port.ProductService;
import com.hyoguoo.paymentplatform.product.application.port.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    @Override
    public Product getById(Long id) {
        return productRepository
                .findById(id)
                .orElseThrow(() -> ProductFoundException.of(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Override
    @Transactional
    public boolean reduceStockWithCommit(Long productId, Integer reduceStock) {
        Product product = getByIdPessimistic(productId);
        boolean result = product.decrementStock(reduceStock);
        productRepository.saveOrUpdate(product);
        return result;
    }

    @Override
    @Transactional
    public boolean increaseStockWithCommit(Long productId, Integer increaseStock) {
        Product product = getByIdPessimistic(productId);
        boolean result = product.incrementStock(increaseStock);
        productRepository.saveOrUpdate(product);
        return result;
    }

    private Product getByIdPessimistic(Long id) {
        return productRepository
                .findByIdPessimistic(id)
                .orElseThrow(() -> ProductFoundException.of(ProductErrorCode.PRODUCT_NOT_FOUND));
    }
}
