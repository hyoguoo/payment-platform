package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentCheckoutService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCheckoutServiceImpl implements PaymentCheckoutService {

    private final UserUseCase userUseCase;
    private final StockReductionUseCase stockReductionUseCase;
    private final PaymentCreateUseCase paymentCreateUseCase;

    @Override
    @Transactional
    public CheckoutResult checkout(CheckoutCommand checkoutCommand) {
        UserInfo userInfo = userUseCase.getUserInfoById(checkoutCommand.getUserId());
        List<ProductInfo> productInfoList = stockReductionUseCase.getProductInfoList(
                checkoutCommand.getOrderedProductList()
        );

        PaymentEvent paymentEvent = paymentCreateUseCase.saveNewPaymentEvent(
                userInfo,
                checkoutCommand.getOrderedProductList(),
                productInfoList
        );

        return CheckoutResult.builder()
                .orderId(paymentEvent.getOrderId())
                .totalAmount(paymentEvent.getTotalAmount())
                .build();
    }
}
