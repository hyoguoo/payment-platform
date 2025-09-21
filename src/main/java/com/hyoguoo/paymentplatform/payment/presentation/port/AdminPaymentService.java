package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.core.common.dto.PageSpec;
import com.hyoguoo.paymentplatform.core.common.dto.PageResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventSearchQuery;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistorySearchQuery;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistoryResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentOrderResult;
import java.util.List;

public interface AdminPaymentService {

    PageResponse<PaymentEventResult> searchPaymentEvents(PaymentEventSearchQuery searchQuery, PageSpec pageSpec);

    PaymentEventResult getPaymentEventDetail(Long eventId);

    List<PaymentOrderResult> getPaymentOrdersByEventId(Long eventId);

    List<PaymentHistoryResult> getPaymentHistoriesByEventId(Long eventId);

    PageResponse<PaymentHistoryResult> searchPaymentHistories(PaymentHistorySearchQuery searchQuery, PageSpec pageSpec);
}
