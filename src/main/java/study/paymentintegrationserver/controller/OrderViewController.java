package study.paymentintegrationserver.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import study.paymentintegrationserver.dto.order.OrderCancelRequest;
import study.paymentintegrationserver.dto.order.OrderCancelResponse;
import study.paymentintegrationserver.service.OrderService;

@Controller
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderViewController {

    private final OrderService orderService;

    @GetMapping("/{id}")
    public String findOrder(@PathVariable("id") Long id, Model model) {
        model.addAttribute("order", orderService.getOrderDetailsByIdAndUpdatePaymentInfo(id));

        return "order/order-detail";
    }

    @GetMapping()
    public String findAllOrders(Model model) {
        model.addAttribute("orders", orderService.findOrderList());

        return "order/order-list";
    }

    @PostMapping("/cancel")
    public String cancelOrder(@ModelAttribute OrderCancelRequest orderCancelRequest) {
        OrderCancelResponse orderCancelResponse = orderService.cancelOrder(orderCancelRequest);

        return "redirect:/order/" + orderCancelResponse.getId();
    }
}
