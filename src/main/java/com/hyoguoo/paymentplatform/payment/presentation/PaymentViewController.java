package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderCancelInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderCancelResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.OrderCancelRequest;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentService;
import jakarta.validation.Valid;
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

    private final PaymentService paymentService;

    @GetMapping("/payment/{id}")
    public String findOrder(@PathVariable("id") Long id, Model model) {
        model.addAttribute("order", paymentService.getOrderDetailsByIdAndUpdatePaymentInfo(id));

        return "order/order-detail";
    }

    @GetMapping("/payment")
    public String findAllOrders(Model model,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        model.addAttribute("orders", paymentService.findOrderList(page, size));

        return "order/order-list";
    }

    @PostMapping("/payment/cancel")
    public String cancelOrder(@ModelAttribute @Valid OrderCancelRequest orderCancelRequest) {
        OrderCancelInfo orderCancelInfo = PaymentPresentationMapper.toOrderCancelInfo(
                orderCancelRequest
        );

        OrderCancelResponse orderCancelResponse = paymentService.cancelOrder(orderCancelInfo);

        return "redirect:/order/" + orderCancelResponse.getId();
    }
}
