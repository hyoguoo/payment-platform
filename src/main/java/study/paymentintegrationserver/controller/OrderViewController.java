package study.paymentintegrationserver.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import study.paymentintegrationserver.dto.order.OrderCancelRequest;
import study.paymentintegrationserver.dto.order.OrderCancelResponse;
import study.paymentintegrationserver.service.OrderService;

import static org.springframework.data.domain.Sort.Direction.DESC;

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
    public String findAllOrders(Model model,
                                @PageableDefault(size = 20, sort = "id", direction = DESC) Pageable pageable) {
        model.addAttribute("orders", orderService.findOrderList(pageable));

        return "order/order-list";
    }

    @PostMapping("/cancel")
    public String cancelOrder(@ModelAttribute @Valid OrderCancelRequest orderCancelRequest) {
        OrderCancelResponse orderCancelResponse = orderService.cancelOrder(orderCancelRequest);

        return "redirect:/order/" + orderCancelResponse.getId();
    }
}
