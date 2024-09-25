package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// TODO: 차감 / 증감이 하나의 트랜잭션에서 수행되도록 변경, 현재 에러 발생 시 논리적 오류 발생
@Service
@RequiredArgsConstructor
public class StockReductionUseCase {

    private final ProductProvider productProvider;

    public void reduceStock(List<PaymentOrder> paymentOrderList) {
        List<PaymentOrder> successfulOrders = new ArrayList<>();

        boolean allSuccess = true;
        for (PaymentOrder paymentOrder : paymentOrderList) {
            boolean success = productProvider.reduceStockWithCommit(
                    paymentOrder.getProductId(),
                    paymentOrder.getQuantity()
            );

            if (success) {
                successfulOrders.add(paymentOrder);
            } else {
                allSuccess = false;
                break;
            }
        }

        if (!allSuccess) {
            successfulOrders.forEach(paymentOrder ->
                    productProvider.increaseStockWithCommit(
                            paymentOrder.getProductId(),
                            paymentOrder.getQuantity()
                    )
            );
        }

    }

    public void increaseStockPaymentOrderListProduct(List<PaymentOrder> paymentOrderList) {
        paymentOrderList.forEach(paymentOrder ->
                productProvider.increaseStockWithCommit(
                        paymentOrder.getProductId(),
                        paymentOrder.getQuantity()
                )
        );
    }
}
