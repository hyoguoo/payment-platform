package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentExpirationSkipMetrics;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.application.port.in.PaymentExpirationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentExpirationServiceImpl implements PaymentExpirationService {

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentExpirationSkipMetrics paymentExpirationSkipMetrics;

    /**
     * 만료 대상 READY 결제를 건별 독립 트랜잭션으로 만료한다.
     *
     * <p>이 메서드 자체는 트랜잭션 경계를 갖지 않는다. 실제 만료(상태 전이 + 저장)는
     * {@link PaymentCommandUseCase#expirePayment}(별도 빈의 {@code @Transactional} 메서드)가
     * 건별로 커밋/롤백한다. 따라서 한 건의 만료 실패(예: order 가 EXECUTING 으로 잔류해
     * expire 가드를 통과 못하는 stranded READY)는 그 건만 롤백되고 나머지 정상 건 만료를
     * 막지 않는다(poison-pill 격리). 격리된 실패는 {@link PaymentExpirationSkipMetrics}
     * 로 가시화한다.
     */
    @Override
    public void expireOldReadyPayments() {
        List<PaymentEvent> expiredPayments = paymentLoadUseCase.getReadyPaymentsOlder();
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_EXPIRATION_START, () ->
                String.format("Expiring %d old READY payments", expiredPayments.size()));

        expiredPayments.forEach(this::expirePaymentEventIsolated);
    }

    private void expirePaymentEventIsolated(PaymentEvent paymentEvent) {
        try {
            PaymentEvent expiredPaymentEvent = paymentCommandUseCase.expirePayment(paymentEvent);
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_EXPIRED, () ->
                    String.format("Payment expired - orderId=%s, paymentEventId=%d",
                            expiredPaymentEvent.getOrderId(), expiredPaymentEvent.getId()));
        } catch (RuntimeException e) {
            paymentExpirationSkipMetrics.recordSkip(paymentEvent.getOrderId(), e);
        }
    }
}
