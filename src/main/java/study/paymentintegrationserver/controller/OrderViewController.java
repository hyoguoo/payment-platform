package study.paymentintegrationserver.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import study.paymentintegrationserver.dto.order.OrderCancelRequest;
import study.paymentintegrationserver.dto.order.OrderCancelResponse;
import study.paymentintegrationserver.service.OrderFacadeService;
import study.paymentintegrationserver.service.OrderService;

@Controller
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderViewController {

    private final OrderService orderService;
    private final OrderFacadeService orderFacadeService;

    @GetMapping("/{id}")
    public String findOrder(@PathVariable("id") Long id, Model model) {
        model.addAttribute("order", orderFacadeService.getOrderDetailsByIdAndUpdatePaymentInfo(id));

        return "order/order-detail";
    }

    @GetMapping()
    public String findAllOrders(Model model,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        model.addAttribute("orders", orderService.findOrderList(page, size));

        return "order/order-list";
    }

    @PostMapping("/cancel")
    public String cancelOrder(@ModelAttribute @Valid OrderCancelRequest orderCancelRequest) {
        OrderCancelResponse orderCancelResponse = orderFacadeService.cancelOrder(
                orderCancelRequest
        );

        return "redirect:/order/" + orderCancelResponse.getId();
    }
}
