package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentCancelResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentDetailResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class PaymentViewController {

    @GetMapping("/payment/{id}")
    public String findDetails(@PathVariable("id") Long id, Model model) {
        PaymentDetailResponse paymentDetailResponse = PaymentDetailResponse.builder().build();
        model.addAttribute("order", paymentDetailResponse);

        return "order/order-detail";
    }

    @GetMapping("/payment")
    public String findAll(Model model,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        PaymentListResponse paymentListResponse = PaymentListResponse.builder().build();
        model.addAttribute("orders", paymentListResponse);

        return "order/order-list";
    }

    @PostMapping("/payment/cancel")
    public String cancel(@ModelAttribute PaymentCancelRequest paymentCancelRequest) {
        PaymentCancelResponse paymentCancelResponse = PaymentCancelResponse.builder().build();

        return "redirect:/order/" + paymentCancelResponse.getId();
    }
}
