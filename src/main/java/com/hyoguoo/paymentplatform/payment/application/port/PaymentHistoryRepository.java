package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import java.util.Map;

public interface PaymentHistoryRepository {

    PaymentHistory save(PaymentHistory paymentHistory);

    Map<PaymentEventStatus, Long> countTransitionsByStatusWithinWindow(LocalDateTime startTime);
}
