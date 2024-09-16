package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.payment.application.port.UserProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.infrastructure.repostitory.PaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final UserProvider userProvider;
    private final ProductProvider productProvider;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Override
    @Transactional
    public CheckoutResult checkout(CheckoutCommand checkoutCommand) {
        UserInfo userInfo = userProvider.getUserInfoById(checkoutCommand.getUserId());
        List<ProductInfo> productInfoList = getProductInfoList(checkoutCommand);

        PaymentEvent savedPaymentEvent = savePaymentEvent(
                userInfo,
                productInfoList
        );
        savePaymentOrderList(
                savedPaymentEvent,
                checkoutCommand.getOrderedProductList(),
                productInfoList
        );

        return CheckoutResult.builder()
                .orderId(savedPaymentEvent.getOrderId())
                .build();
    }

    private List<ProductInfo> getProductInfoList(CheckoutCommand checkoutCommand) {
        return checkoutCommand.getOrderedProductList().stream()
                .map(orderedProduct ->
                        productProvider.getProductInfoById(orderedProduct.getProductId()))
                .toList();
    }

    private PaymentEvent savePaymentEvent(
            UserInfo userInfo,
            List<ProductInfo> productInfoList
    ) {
        PaymentEvent paymentEvent = PaymentEvent.requiredBuilder()
                .userInfo(userInfo)
                .productInfoList(productInfoList)
                .now(localDateTimeProvider.now())
                .requiredBuild();

        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    private void savePaymentOrderList(
            PaymentEvent savedPaymentEvent,
            List<OrderedProduct> orderedProductList,
            List<ProductInfo> productInfoList
    ) {
        productInfoList.forEach(productInfo -> {
            OrderedProduct matchedOrderedProduct = findMatchingOrderedProduct(
                    orderedProductList,
                    productInfo
            );

            savePaymentOrder(savedPaymentEvent, productInfo, matchedOrderedProduct);
        });
    }

    private OrderedProduct findMatchingOrderedProduct(
            List<OrderedProduct> orderedProductList,
            ProductInfo productInfo
    ) {
        return orderedProductList.stream()
                .filter(orderedProduct ->
                        orderedProduct.getProductId().equals(productInfo.getId()))
                .findFirst()
                .orElseThrow();
    }

    private void savePaymentOrder(
            PaymentEvent savedPaymentEvent,
            ProductInfo productInfo,
            OrderedProduct matchedOrderedProduct
    ) {
        PaymentOrder paymentOrder = PaymentOrder.requiredBuilder()
                .paymentEvent(savedPaymentEvent)
                .orderedProduct(matchedOrderedProduct)
                .productInfo(productInfo)
                .requiredBuild();

        paymentOrderRepository.saveOrUpdate(paymentOrder);
    }
}
