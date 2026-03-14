package com.hyoguoo.paymentplatform.payment.infrastructure.adapter;

import com.hyoguoo.paymentplatform.payment.application.PaymentConfirmServiceImpl;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult.ResponseType;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "spring.payment.async-strategy",
        havingValue = "sync",
        matchIfMissing = true
)
public class SyncConfirmAdapter implements PaymentConfirmService {

    private final PaymentConfirmServiceImpl paymentConfirmServiceImpl;

    @Override
    public PaymentConfirmAsyncResult confirm(PaymentConfirmCommand paymentConfirmCommand) {
        PaymentConfirmResult result = paymentConfirmServiceImpl.confirm(paymentConfirmCommand);

        return PaymentConfirmAsyncResult.builder()
                .responseType(ResponseType.SYNC_200)
                .orderId(result.getOrderId())
                .amount(result.getAmount())
                .build();
    }
}
