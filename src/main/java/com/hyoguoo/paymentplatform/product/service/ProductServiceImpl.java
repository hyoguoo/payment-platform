package com.hyoguoo.paymentplatform.product.service;

import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.exception.ProductFoundException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import com.hyoguoo.paymentplatform.product.presentation.port.ProductService;
import com.hyoguoo.paymentplatform.product.service.port.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
    public Product reduceStockWithCommit(Long productId, Integer reduceStock) {
        return getByIdPessimistic(productId)
                .decrementStock(reduceStock);
    }

    @Override
    public Product increaseStockWithCommit(Long productId, Integer increaseStock) {
        return getByIdPessimistic(productId)
                .incrementStock(increaseStock);
    }

    private Product getByIdPessimistic(Long id) {
        return productRepository
                .findByIdPessimistic(id)
                .orElseThrow(() -> ProductFoundException.of(ProductErrorCode.PRODUCT_NOT_FOUND));
    }
}
