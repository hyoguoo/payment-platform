package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.usecase.OrderedProductUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.OrderedUserUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCreateUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentCheckoutService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCheckoutServiceImpl implements PaymentCheckoutService {

    private final OrderedUserUseCase orderedUserUseCase;
    private final OrderedProductUseCase orderedProductUseCase;
    private final PaymentCreateUseCase paymentCreateUseCase;

    @Override
    @Transactional
    public CheckoutResult checkout(CheckoutCommand checkoutCommand) {
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CHECKOUT_START,
                () -> String.format("userId=%s", checkoutCommand.getUserId()));
        UserInfo userInfo = orderedUserUseCase.getUserInfoById(checkoutCommand.getUserId());
        List<ProductInfo> productInfoList = orderedProductUseCase.getProductInfoList(
                checkoutCommand.getOrderedProductList()
        );

        PaymentEvent paymentEvent = paymentCreateUseCase.createNewPaymentEvent(
                userInfo,
                checkoutCommand.getOrderedProductList(),
                productInfoList
        );

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CHECKOUT_END,
                () -> String.format("orderId=%s totalAmount=%s",
                        paymentEvent.getOrderId(),
                        paymentEvent.getTotalAmount()));

        return CheckoutResult.builder()
                .orderId(paymentEvent.getOrderId())
                .totalAmount(paymentEvent.getTotalAmount())
                .build();
    }
}
