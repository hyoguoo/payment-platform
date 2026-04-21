package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * ADR-05 Phase 1 LVAL — payment-service 진입 전 금액 위변조 선검증.
 * Phase 2 벤더 재조회 + 2자 금액 대조는 pg-service(ADR-21(v)) 담당.
 */
@Component
public class PaymentLvalValidator {

    /**
     * 요청 금액과 저장된 PaymentEvent의 totalAmount를 대조한다.
     * 불일치 시 {@link PaymentValidException}(4xx) 발생.
     *
     * @param event           이미 load된 PaymentEvent
     * @param requestedAmount 클라이언트가 전달한 결제 금액
     */
    public void validate(PaymentEvent event, BigDecimal requestedAmount) {
        if (requestedAmount.compareTo(event.getTotalAmount()) != 0) {
            throw PaymentValidException.of(PaymentErrorCode.AMOUNT_MISMATCH);
        }
    }
}
