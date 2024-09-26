package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderedProductUseCase {

    private final ProductProvider productProvider;

    public void decreaseStockForOrders(
            List<PaymentOrder> paymentOrderList
    ) {
        List<OrderedProductStockCommand> orderedProductStockCommandList = getOrderedProductStockCommands(
                paymentOrderList
        );
        productProvider.decreaseStockForOrders(orderedProductStockCommandList);
    }

    public void increaseStockForOrders(
            List<PaymentOrder> paymentOrderList
    ) {
        List<OrderedProductStockCommand> orderedProductStockCommandList = getOrderedProductStockCommands(
                paymentOrderList
        );
        productProvider.increaseStockForOrders(orderedProductStockCommandList);
    }

    private List<OrderedProductStockCommand> getOrderedProductStockCommands(
            List<PaymentOrder> paymentOrderList
    ) {
        return paymentOrderList.stream()
                .map(paymentOrder -> OrderedProductStockCommand.builder()
                        .productId(paymentOrder.getProductId())
                        .stock(paymentOrder.getQuantity())
                        .build())
                .toList();
    }

    public List<ProductInfo> getProductInfoList(List<OrderedProduct> orderedProductList) {
        return orderedProductList.stream()
                .map(orderedProduct ->
                        productProvider.getProductInfoById(orderedProduct.getProductId()))
                .toList();
    }
}
