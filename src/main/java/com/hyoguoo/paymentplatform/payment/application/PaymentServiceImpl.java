package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderProduct;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.payment.application.port.UserProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.infrastructure.repostitory.PaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentService;
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
    public CheckoutResult checkout(CheckoutCommand request) {
        UserInfo userInfoById = userProvider.getUserInfoById(request.getUserId());
        ProductInfo productInfoById = productProvider.getProductInfoById(
                request.getOrderProduct().getProductId()
        );

        PaymentEvent savedPaymentEvent = savePaymentEvent(request, userInfoById, productInfoById);
        savePaymentOrder(savedPaymentEvent, request.getOrderProduct(), productInfoById);

        return CheckoutResult.builder()
                .orderId(savedPaymentEvent.getOrderId())
                .build();
    }

    private PaymentEvent savePaymentEvent(
            CheckoutCommand request,
            UserInfo userInfoById,
            ProductInfo productInfoById
    ) {
        PaymentEvent paymentEvent = PaymentEvent.requiredBuilder()
                .userInfo(userInfoById)
                .productInfo(productInfoById)
                .checkoutCommand(request)
                .now(localDateTimeProvider.now())
                .requiredBuild();

        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    private PaymentOrder savePaymentOrder(
            PaymentEvent savedPaymentEvent,
            OrderProduct orderProduct,
            ProductInfo productInfo
    ) {
        PaymentOrder paymentOrder = PaymentOrder.requiredBuilder()
                .paymentEvent(savedPaymentEvent)
                .orderProduct(orderProduct)
                .productInfo(productInfo)
                .requiredBuild();

        return paymentOrderRepository.saveOrUpdate(paymentOrder);
    }
}
