package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryLookupResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusInfo;
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
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin.PgAttemptHistoryViewResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin.PgVendorStatusViewResponse;
import com.hyoguoo.paymentplatform.payment.presentation.port.AdminPaymentService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentRecoveryAdminService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PgAttemptHistoryViewService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PgVendorStatusViewService;
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
    private final PgAttemptHistoryViewService pgAttemptHistoryViewService;
    private final PgVendorStatusViewService pgVendorStatusViewService;

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
        populateEventDetail(eventId, model);

        return "admin/payment-event-detail";
    }

    /**
     * 격리 상세 화면에서 벤더 상태 확인 버튼을 눌렀을 때만 호출된다 — {@link #getPaymentEventDetail}
     * 진입 시에는 자동 조회하지 않는다(외부 호출이라 매번 느려지고 보지도 않을 조회가 벤더에 나간다).
     * 상세 화면을 그대로 재조립하고 벤더 상태만 덧붙여 같은 템플릿으로 돌아간다.
     */
    @GetMapping("/events/{eventId}/vendor-status")
    public String getVendorStatus(
            @PathVariable Long eventId,
            Model model
    ) {
        String orderId = populateEventDetail(eventId, model);
        addVendorStatus(model, orderId);

        return "admin/payment-event-detail";
    }

    private String populateEventDetail(Long eventId, Model model) {
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

        addAttemptHistory(model, eventResult.getOrderId());

        return eventResult.getOrderId();
    }

    /**
     * 벤더 상태를 조회해 모델에 담는다. {@link PgVendorStatusViewService} 는 조회 실패를 예외로
     * 던지지 않고 확인 불가 판정으로 흡수하므로(Task 2), pg 가 닿지 않아도 이 호출은 항상 값을
     * 반환하고 나머지 상세 화면은 정상 렌더된다 — 기존 시도 이력 카드와 같은 방식이다.
     */
    private void addVendorStatus(Model model, String orderId) {
        PgVendorStatusInfo vendorStatusInfo = pgVendorStatusViewService.getVendorStatus(orderId);
        model.addAttribute("vendorStatus", PgVendorStatusViewResponse.from(vendorStatusInfo));
    }

    /**
     * pg-service 확정 시도 이력을 조회해 모델에 담는다. 조회 실패 흡수와 조회 불가 폴백 판단은
     * {@link PgAttemptHistoryViewService}(입력 포트) 구현이 전담한다 — 이 메서드는 그 결과를
     * 모델에 담는 일만 한다.
     * <p>
     * 이력 없음({@code found=false})은 조회 실패가 아니라 정상 응답이라
     * {@link PgAttemptHistoryLookupResult#isUnavailable()} 이 false 로 남는다 — 이력 없음과
     * 조회 불가가 화면에서 구분된다.
     */
    private void addAttemptHistory(Model model, String orderId) {
        PgAttemptHistoryLookupResult result = pgAttemptHistoryViewService.getAttemptHistory(orderId);
        if (result.isUnavailable()) {
            model.addAttribute("attemptHistoryUnavailable", true);
            return;
        }
        model.addAttribute("attemptHistory", PgAttemptHistoryViewResponse.from(result.getHistory()));
        model.addAttribute("attemptHistoryUnavailable", false);
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
