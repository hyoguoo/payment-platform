package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.dto.PageSpec;
import com.hyoguoo.paymentplatform.core.common.dto.PageResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventSearchQuery;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistorySearchQuery;
import com.hyoguoo.paymentplatform.payment.application.usecase.AdminPaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistoryResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentOrderResult;
import com.hyoguoo.paymentplatform.payment.presentation.port.AdminPaymentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPaymentServiceImpl implements AdminPaymentService {

    private final AdminPaymentLoadUseCase adminPaymentLoadUseCase;

    @Override
    public PageResponse<PaymentEventResult> searchPaymentEvents(
            PaymentEventSearchQuery searchQuery,
            PageSpec pageSpec
    ) {
        PageResponse<PaymentEvent> domainPage = adminPaymentLoadUseCase.searchPaymentEvents(searchQuery, pageSpec);
        return domainPage.map(PaymentEventResult::from);
    }

    @Override
    public PaymentEventResult getPaymentEventDetail(Long eventId) {
        PaymentEvent paymentEvent = adminPaymentLoadUseCase.getPaymentEventDetail(eventId);
        return PaymentEventResult.from(paymentEvent);
    }

    @Override
    public List<PaymentOrderResult> getPaymentOrdersByEventId(Long eventId) {
        return adminPaymentLoadUseCase.getPaymentOrdersByEventId(eventId).stream()
                .map(PaymentOrderResult::from)
                .toList();
    }

    @Override
    public List<PaymentHistoryResult> getPaymentHistoriesByEventId(Long eventId) {
        return adminPaymentLoadUseCase.getPaymentHistoriesByEventId(eventId).stream()
                .map(PaymentHistoryResult::from)
                .toList();
    }

    @Override
    public PageResponse<PaymentHistoryResult> searchPaymentHistories(
            PaymentHistorySearchQuery searchQuery,
            PageSpec pageSpec
    ) {
        PageResponse<PaymentHistory> domainPage = adminPaymentLoadUseCase.searchPaymentHistories(searchQuery, pageSpec);
        return domainPage.map(PaymentHistoryResult::from);
    }
}
