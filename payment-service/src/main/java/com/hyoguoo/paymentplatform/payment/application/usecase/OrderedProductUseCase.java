package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderedProductUseCase {

    private final ProductPort productPort;

    public List<ProductInfo> getProductInfoList(List<OrderedProduct> orderedProductList) {
        return orderedProductList.stream()
                .map(orderedProduct ->
                        productPort.getProductInfoById(orderedProduct.getProductId()))
                .toList();
    }
}
