package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.dto.PageResponse;
import com.hyoguoo.paymentplatform.core.common.dto.PageSpec;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventSearchQuery;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistorySearchQuery;
import com.hyoguoo.paymentplatform.payment.application.port.AdminPaymentQueryRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminPaymentLoadUseCase {

    private final AdminPaymentQueryRepository adminPaymentQueryRepository;

    @Transactional(readOnly = true)
    public PageResponse<PaymentEvent> searchPaymentEvents(
            PaymentEventSearchQuery searchQuery,
            PageSpec pageSpec
    ) {
        return adminPaymentQueryRepository.searchPaymentEvents(
                searchQuery,
                pageSpec
        );
    }

    @Transactional(readOnly = true)
    public PaymentEvent getPaymentEventDetail(Long eventId) {
        return adminPaymentQueryRepository.findPaymentEventWithOrdersById(eventId)
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<PaymentOrder> getPaymentOrdersByEventId(Long eventId) {
        return adminPaymentQueryRepository.findPaymentOrdersByEventId(eventId);
    }

    @Transactional(readOnly = true)
    public List<PaymentHistory> getPaymentHistoriesByEventId(Long eventId) {
        return adminPaymentQueryRepository.findPaymentHistoriesByEventId(eventId);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentHistory> searchPaymentHistories(
            PaymentHistorySearchQuery searchQuery,
            PageSpec pageSpec
    ) {
        return adminPaymentQueryRepository.searchPaymentHistories(
                searchQuery,
                pageSpec
        );
    }
}
