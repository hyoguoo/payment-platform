package com.hyoguoo.paymentplatform.product.application;

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;
import com.hyoguoo.paymentplatform.product.application.port.ProductRepository;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.exception.ProductFoundException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import com.hyoguoo.paymentplatform.product.presentation.port.ProductService;
import java.util.List;
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
    public void decreaseStockForOrders(List<ProductStockCommand> productStockCommandList) {
        List<Product> productList = productStockCommandList.stream().map(productStockCommand -> {
                    Product product = getByIdPessimistic(productStockCommand.getProductId());
                    product.decrementStock(productStockCommand.getStock());

                    return product;
                })
                .toList();

        productRepository.saveAll(productList);
    }

    @Override
    @Transactional
    public void increaseStockForOrders(List<ProductStockCommand> productStockCommandList) {
        List<Product> productList = productStockCommandList.stream().map(productStockCommand -> {
                    Product product = getByIdPessimistic(productStockCommand.getProductId());
                    product.incrementStock(productStockCommand.getStock());

                    return product;
                })
                .toList();

        productRepository.saveAll(productList);
    }

    private Product getByIdPessimistic(Long id) {
        return productRepository
                .findByIdPessimistic(id)
                .orElseThrow(() -> ProductFoundException.of(ProductErrorCode.PRODUCT_NOT_FOUND));
    }
}
