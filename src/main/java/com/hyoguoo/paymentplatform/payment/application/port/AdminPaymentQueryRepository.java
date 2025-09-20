package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventSearchQuery;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistorySearchQuery;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminPaymentQueryRepository {

    Page<PaymentEvent> searchPaymentEvents(PaymentEventSearchQuery searchQuery, Pageable pageable);

    Page<PaymentHistory> searchPaymentHistories(PaymentHistorySearchQuery searchQuery, Pageable pageable);

    Optional<PaymentEvent> findPaymentEventWithOrdersById(Long eventId);

    List<PaymentOrder> findPaymentOrdersByEventId(Long eventId);

    List<PaymentHistory> findPaymentHistoriesByEventId(Long eventId);
}
