package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.core.common.service.port.UUIDProvider;
import com.hyoguoo.paymentplatform.core.common.aspect.annotation.PublishDomainEvent;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCreateUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final UUIDProvider uuidProvider;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Transactional
    @PublishDomainEvent(action = "created")
    public PaymentEvent createNewPaymentEvent(
            UserInfo userInfo,
            List<OrderedProduct> orderedProductList,
            List<ProductInfo> productInfoList
    ) {
        PaymentEvent savedPaymentEvent = saveNewPaymentEvent(
                userInfo,
                productInfoList
        );

        List<PaymentOrder> paymentOrderList = saveNewPaymentOrderList(
                savedPaymentEvent,
                orderedProductList,
                productInfoList
        );

        savedPaymentEvent.addPaymentOrderList(paymentOrderList);

        return savedPaymentEvent;
    }

    private PaymentEvent saveNewPaymentEvent(
            UserInfo userInfo,
            List<ProductInfo> productInfoList
    ) {
        PaymentEvent paymentEvent = PaymentEvent.create(
                userInfo,
                productInfoList,
                uuidProvider.generateUUID(),
                localDateTimeProvider.now()
        );

        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    private List<PaymentOrder> saveNewPaymentOrderList(
            PaymentEvent savedPaymentEvent,
            List<OrderedProduct> orderedProductList,
            List<ProductInfo> productInfoList
    ) {
        List<PaymentOrder> paymentOrderList = productInfoList.stream()
                .map(productInfo -> {
                    OrderedProduct matchedOrderedProduct = findMatchingOrderedProduct(
                            orderedProductList,
                            productInfo
                    );

                    return createPaymentOrder(
                            savedPaymentEvent,
                            productInfo,
                            matchedOrderedProduct
                    );
                })
                .toList();
        return paymentOrderRepository.saveAll(paymentOrderList);
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

    private PaymentOrder createPaymentOrder(
            PaymentEvent savedPaymentEvent,
            ProductInfo productInfo,
            OrderedProduct matchedOrderedProduct
    ) {
        return PaymentOrder.create(
                savedPaymentEvent,
                matchedOrderedProduct,
                productInfo
        );
    }
}
