package com.hyoguoo.paymentplatform.order.presentation;

import com.hyoguoo.paymentplatform.order.application.dto.request.OrderCancelInfo;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderCancelResponse;
import com.hyoguoo.paymentplatform.order.presentation.dto.request.OrderCancelRequest;
import com.hyoguoo.paymentplatform.order.presentation.port.OrderService;
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
public class OrderViewController {

    private final OrderService orderService;

    @GetMapping("/order/{id}")
    public String findOrder(@PathVariable("id") Long id, Model model) {
        model.addAttribute("order", orderService.getOrderDetailsByIdAndUpdatePaymentInfo(id));

        return "order/order-detail";
    }

    @GetMapping("/order")
    public String findAllOrders(Model model,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        model.addAttribute("orders", orderService.findOrderList(page, size));

        return "order/order-list";
    }

    @PostMapping("/order/cancel")
    public String cancelOrder(@ModelAttribute @Valid OrderCancelRequest orderCancelRequest) {
        OrderCancelInfo orderCancelInfo = OrderPresentationMapper.toOrderCancelInfo(
                orderCancelRequest
        );

        OrderCancelResponse orderCancelResponse = orderService.cancelOrder(orderCancelInfo);

        return "redirect:/order/" + orderCancelResponse.getId();
    }
}
