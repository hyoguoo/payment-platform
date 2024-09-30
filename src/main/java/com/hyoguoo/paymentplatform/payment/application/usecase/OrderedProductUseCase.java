package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderedProductUseCase {

    private final ProductProvider productProvider;

    public void decreaseStockForOrders(
            List<PaymentOrder> paymentOrderList
    ) throws PaymentOrderedProductStockException {
        List<OrderedProductStockCommand> orderedProductStockCommandList = getOrderedProductStockCommands(
                paymentOrderList
        );
        try {
            productProvider.decreaseStockForOrders(orderedProductStockCommandList);
        } catch (Exception e) {
            throw PaymentOrderedProductStockException.of(PaymentErrorCode.ORDERED_PRODUCT_STOCK_NOT_ENOUGH);
        }
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
