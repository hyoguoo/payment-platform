package com.hyoguoo.paymentplatform.payment.infrastructure.internal;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentInfrastructureMapper;
import com.hyoguoo.paymentplatform.product.presentation.ProductInternalReceiver;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoResponse;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductStockRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 결제 서비스 내 product 컨텍스트 직접 호출 어댑터.
 * product.adapter.type=internal(기본값)인 경우에만 활성화.
 * MSA Phase 3 이후 ProductHttpAdapter(http 타입)로 교체 예정.
 */
@Component
@ConditionalOnProperty(name = "product.adapter.type", havingValue = "internal", matchIfMissing = true)
@RequiredArgsConstructor
public class InternalProductAdapter implements ProductPort {

    private final ProductInternalReceiver productInternalReceiver;

    @Override
    public ProductInfo getProductInfoById(Long productId) {
        ProductInfoResponse productInfoResponse = productInternalReceiver.getProductInfoById(
                productId);

        return PaymentInfrastructureMapper.toProductInfo(productInfoResponse);
    }

    @Override
    public void increaseStockForOrders(
            List<OrderedProductStockCommand> orderedProductStockCommandList
    ) {
        List<ProductStockRequest> productStockRequestList = orderedProductStockCommandList.stream()
                .map(PaymentInfrastructureMapper::toProductStockRequest)
                .toList();
        productInternalReceiver.increaseStockForOrders(productStockRequestList);
    }
}
