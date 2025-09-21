package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.core.common.dto.PageResponse;
import com.hyoguoo.paymentplatform.core.common.dto.PageSpec;
import com.hyoguoo.paymentplatform.core.common.dto.SortDirection;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventSearchQuery;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistoryResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistorySearchQuery;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentOrderResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin.PaymentEventResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin.PaymentHistoryResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin.PaymentOrderResponse;
import com.hyoguoo.paymentplatform.payment.presentation.port.AdminPaymentService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/payments")
@RequiredArgsConstructor
public class PaymentAdminController {

    private final AdminPaymentService adminPaymentService;

    @GetMapping("/events")
    public String listPaymentEvents(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String orderId,
            Model model
    ) {
        PaymentEventSearchQuery searchQuery = PaymentEventSearchQuery.builder()
                .orderId(orderId)
                .build();

        PageSpec pageSpec = PageSpec.of(page, size, "createdAt", SortDirection.DESC);

        PageResponse<PaymentEventResult> pageResponse = adminPaymentService.searchPaymentEvents(searchQuery,
                pageSpec);

        model.addAttribute("events", pageResponse);
        model.addAttribute("searchQuery", searchQuery);
        model.addAttribute("statuses", PaymentEventStatus.values());

        return "admin/payment-events";
    }

    @GetMapping("/events/{eventId}")
    public String getPaymentEventDetail(
            @PathVariable Long eventId,
            Model model
    ) {
        PaymentEventResult eventResult = adminPaymentService.getPaymentEventDetail(eventId);
        List<PaymentOrderResult> orderResults = adminPaymentService.getPaymentOrdersByEventId(eventId);
        List<PaymentHistoryResult> historyResults = adminPaymentService.getPaymentHistoriesByEventId(eventId);

        PaymentEventResponse event = PaymentEventResponse.from(eventResult);

        List<PaymentOrderResponse> orders = orderResults.stream()
                .map(PaymentOrderResponse::from)
                .toList();
        List<PaymentHistoryResponse> histories = historyResults.stream()
                .map(PaymentHistoryResponse::from)
                .toList();

        model.addAttribute("event", event);
        model.addAttribute("orders", orders);
        model.addAttribute("histories", histories);

        return "admin/payment-event-detail";
    }

    @GetMapping("/history")
    public String listPaymentHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String orderId,
            Model model
    ) {
        PaymentHistorySearchQuery searchQuery = PaymentHistorySearchQuery.builder()
                .orderId(orderId)
                .build();

        PageSpec pageSpec = PageSpec.of(page, size, "changeStatusAt", SortDirection.DESC);

        PageResponse<PaymentHistoryResult> pageResponse = adminPaymentService.searchPaymentHistories(searchQuery,
                pageSpec);

        model.addAttribute("histories", pageResponse);
        model.addAttribute("searchQuery", searchQuery);
        model.addAttribute("statuses", PaymentEventStatus.values());

        return "admin/payment-history";
    }
}
