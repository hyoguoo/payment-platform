package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.payment.application.port.UserProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentEventRepository paymentEventRepository;
    private final UserProvider userProvider;
    private final ProductProvider productProvider;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Override
    @Transactional
    public CheckoutResult checkout(CheckoutCommand request) {
        PaymentEvent paymentEvent = PaymentEvent.requiredBuilder()
                .userInfo(userProvider.getUserInfoById(request.getUserId()))
                .productInfo(
                        productProvider.getProductInfoById(
                                request.getOrderProduct().getProductId()
                        )
                )
                .checkoutCommand(request)
                .now(localDateTimeProvider.now())
                .requiredBuild();

        PaymentEvent savedPaymentEvent = paymentEventRepository.saveOrUpdate(paymentEvent);

        return CheckoutResult.builder()
                .orderId(savedPaymentEvent.getOrderId())
                .build();
    }
}
