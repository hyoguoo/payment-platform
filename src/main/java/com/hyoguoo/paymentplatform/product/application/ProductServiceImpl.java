package com.hyoguoo.paymentplatform.product.application;

import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;
import com.hyoguoo.paymentplatform.product.application.port.ProductRepository;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.exception.ProductFoundException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import com.hyoguoo.paymentplatform.product.presentation.port.ProductService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
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
                    LogFmt.info(log, LogDomain.PRODUCT, EventType.PRODUCT_STOCK_DECREASE_REQUEST,
                            () -> String.format("products=%s stock=%s",
                                    productStockCommand.getProductId(),
                                    productStockCommand.getStock()));
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
                    LogFmt.info(log, LogDomain.PRODUCT, EventType.PRODUCT_STOCK_INCREASE_REQUEST,
                            () -> String.format("products=%s stock=%s",
                                    productStockCommand.getProductId(),
                                    productStockCommand.getStock()));
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
