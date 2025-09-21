package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.core.common.dto.PageResponse;
import com.hyoguoo.paymentplatform.core.common.dto.PageSpec;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventSearchQuery;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistorySearchQuery;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;
import java.util.Optional;

public interface AdminPaymentQueryRepository {

    PageResponse<PaymentEvent> searchPaymentEvents(PaymentEventSearchQuery searchQuery, PageSpec pageSpec);

    PageResponse<PaymentHistory> searchPaymentHistories(PaymentHistorySearchQuery searchQuery, PageSpec pageSpec);

    Optional<PaymentEvent> findPaymentEventWithOrdersById(Long eventId);

    List<PaymentOrder> findPaymentOrdersByEventId(Long eventId);

    List<PaymentHistory> findPaymentHistoriesByEventId(Long eventId);
}
