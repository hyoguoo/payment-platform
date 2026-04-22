package com.hyoguoo.paymentplatform.product.application.usecase;

import com.hyoguoo.paymentplatform.product.application.port.out.ProductRepository;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.exception.ProductNotFoundException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import com.hyoguoo.paymentplatform.product.presentation.port.ProductQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 조회 유스케이스.
 * ProductRepository.findById → 없으면 ProductNotFoundException, 있으면 Product 도메인 반환.
 */
@Service
@RequiredArgsConstructor
public class ProductQueryUseCase implements ProductQueryService {

    private final ProductRepository productRepository;

    @Override
    @Transactional(readOnly = true)
    public Product getById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> ProductNotFoundException.of(ProductErrorCode.PRODUCT_NOT_FOUND));
    }
}
