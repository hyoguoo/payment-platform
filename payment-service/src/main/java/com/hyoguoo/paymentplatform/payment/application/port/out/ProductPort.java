package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;

public interface ProductPort {

    ProductInfo getProductInfoById(Long productId);
}
