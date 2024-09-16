package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.presentation.dto.response.OrderCancelResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.OrderCancelRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.OrderDetailResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.OrderListResponse;
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
    public String findOrder(@PathVariable("id") Long id, Model model) {
        OrderDetailResponse orderDetailResponse = OrderDetailResponse.builder().build();
        model.addAttribute("order", orderDetailResponse);

        return "order/order-detail";
    }

    @GetMapping("/payment")
    public String findAllOrders(Model model,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        OrderListResponse orderListResponse = OrderListResponse.builder().build();
        model.addAttribute("orders", orderListResponse);

        return "order/order-list";
    }

    @PostMapping("/payment/cancel")
    public String cancelOrder(@ModelAttribute OrderCancelRequest orderCancelRequest) {
        OrderCancelResponse orderCancelResponse = OrderCancelResponse.builder().build();

        return "redirect:/order/" + orderCancelResponse.getId();
    }
}
