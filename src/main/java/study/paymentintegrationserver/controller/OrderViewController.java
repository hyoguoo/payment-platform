package study.paymentintegrationserver.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import study.paymentintegrationserver.service.OrderService;

@Controller
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderViewController {

    private final OrderService orderService;

    @GetMapping()
    public String findAllOrders(Model model) {
        model.addAttribute("orders", orderService.findOrderList());

        return "order/order-list";
    }
}
