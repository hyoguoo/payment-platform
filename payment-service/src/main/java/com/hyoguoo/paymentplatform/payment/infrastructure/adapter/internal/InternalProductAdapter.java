package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.internal;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductLookupPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentInfrastructureMapper;
import com.hyoguoo.paymentplatform.product.presentation.ProductInternalReceiver;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoResponse;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductStockRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 상품 컨텍스트 내부 호출 어댑터 (Phase 1 모놀리스 경계).
 * Phase 3 이후 RemoteProductAdapter(HTTP/gRPC)로 교체 예정.
 */
@Component("productLookupAdapter")
@RequiredArgsConstructor
public class InternalProductAdapter implements ProductLookupPort {

    private final ProductInternalReceiver productInternalReceiver;

    @Override
    public ProductInfo getProductInfoById(Long productId) {
        ProductInfoResponse productInfoResponse = productInternalReceiver.getProductInfoById(productId);
        return PaymentInfrastructureMapper.toProductInfo(productInfoResponse);
    }

    @Override
    public void decreaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList) {
        List<ProductStockRequest> productStockRequestList = orderedProductStockCommandList.stream()
                .map(PaymentInfrastructureMapper::toProductStockRequest)
                .toList();
        productInternalReceiver.decreaseStockForOrders(productStockRequestList);
    }

    @Override
    public void increaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList) {
        List<ProductStockRequest> productStockRequestList = orderedProductStockCommandList.stream()
                .map(PaymentInfrastructureMapper::toProductStockRequest)
                .toList();
        productInternalReceiver.increaseStockForOrders(productStockRequestList);
    }
}
