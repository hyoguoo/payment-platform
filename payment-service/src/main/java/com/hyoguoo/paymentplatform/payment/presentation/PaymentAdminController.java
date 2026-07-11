package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.core.common.dto.PageResponse;
import com.hyoguoo.paymentplatform.payment.core.common.dto.PageSpec;
import com.hyoguoo.paymentplatform.payment.core.common.dto.SortDirection;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventSearchQuery;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistoryResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistorySearchQuery;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentOrderResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin.PaymentEventResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin.PaymentHistoryResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin.PaymentOrderResponse;
import com.hyoguoo.paymentplatform.payment.presentation.port.AdminPaymentService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentRecoveryAdminService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/payments")
@RequiredArgsConstructor
public class PaymentAdminController {

    private final AdminPaymentService adminPaymentService;
    private final PaymentRecoveryAdminService paymentRecoveryAdminService;

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

    /**
     * 격리(QUARANTINED) 결제를 안전 종결(FAILED)로 복구한다. {@code reason} 은 안전 종결 사유
     * 감사 기록이라 필수 파라미터다(누락 시 {@code MissingServletRequestParameterException} ->
     * 400, 공백 값·비격리 상태·CAS 충돌은 유스케이스가 거부한다).
     * <p>
     * 유스케이스 거부({@link PaymentValidException}/{@link PaymentStatusException})는 관리자
     * 폼 UX 를 위해 raw JSON 400 대신 상세 화면으로 리다이렉트하며 flash 메시지로 사유를 전달한다.
     */
    @PostMapping("/events/{eventId}/resolve-quarantine")
    public String resolveQuarantine(
            @PathVariable Long eventId,
            @RequestParam String orderId,
            @RequestParam String reason,
            RedirectAttributes redirectAttributes
    ) {
        try {
            paymentRecoveryAdminService.resolveQuarantine(orderId, reason);
        } catch (PaymentValidException | PaymentStatusException e) {
            return redirectWithError(eventId, redirectAttributes, e.getMessage());
        }

        return "redirect:/admin/payments/events/" + eventId;
    }

    /**
     * {@code events.confirmed.dlq} 유실 메시지를 원 토픽으로 재주입한다. 종결(DONE) 후 멱등
     * 보장 기간(P8D)을 초과하면 유스케이스가 {@code DLQ_REPROCESS_AGE_GATE_EXCEEDED} 로 거부하고
     * 수동 대사를 안내한다 — 이 경우도 raw JSON 400 대신 상세 화면 리다이렉트 + flash 메시지로 전달한다.
     */
    @PostMapping("/events/{eventId}/reprocess-dlq")
    public String reprocessDlq(
            @PathVariable Long eventId,
            @RequestParam String orderId,
            RedirectAttributes redirectAttributes
    ) {
        try {
            paymentRecoveryAdminService.reprocessDlq(orderId);
        } catch (PaymentValidException | PaymentStatusException e) {
            return redirectWithError(eventId, redirectAttributes, e.getMessage());
        }

        return "redirect:/admin/payments/events/" + eventId;
    }

    private String redirectWithError(Long eventId, RedirectAttributes redirectAttributes, String errorMessage) {
        redirectAttributes.addFlashAttribute("errorMessage", errorMessage);

        return "redirect:/admin/payments/events/" + eventId + "?error";
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
