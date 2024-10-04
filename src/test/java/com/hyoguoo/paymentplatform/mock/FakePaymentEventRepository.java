package com.hyoguoo.paymentplatform.mock;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.test.util.ReflectionTestUtils;

public class FakePaymentEventRepository implements PaymentEventRepository {

    private final Map<Long, PaymentEvent> paymentEventDatabase = new HashMap<>();
    private final Map<Long, List<PaymentOrder>> paymentOrderDatabase = new HashMap<>();
    private Long autoGeneratedEventId = 1L;
    private Long autoGeneratedOrderId = 1L;

    @Override
    public Optional<PaymentEvent> findById(Long id) {
        Optional<PaymentEvent> paymentEvent = Optional.ofNullable(paymentEventDatabase.get(id));
        paymentEvent.ifPresent(event -> event.addPaymentOrderList(findPaymentOrdersByPaymentEventId(event.getId())));

        return paymentEvent;
    }

    @Override
    public Optional<PaymentEvent> findByOrderId(String orderId) {
        Optional<PaymentEvent> paymentEvent = paymentEventDatabase.values().stream()
                .filter(event -> event.getOrderId().equals(orderId))
                .findFirst();
        paymentEvent.ifPresent(event -> event.addPaymentOrderList(findPaymentOrdersByPaymentEventId(event.getId())));

        return paymentEvent;
    }

    @Override
    public PaymentEvent saveOrUpdate(PaymentEvent paymentEvent) {
        if (paymentEvent.getId() == null) {
            ReflectionTestUtils.setField(paymentEvent, "id", autoGeneratedEventId);
            autoGeneratedEventId++;
        }

        paymentEventDatabase.put(paymentEvent.getId(), paymentEvent);
        paymentOrderDatabase.put(paymentEvent.getId(), new ArrayList<>(paymentEvent.getPaymentOrderList()));

        if (!paymentEvent.getPaymentOrderList().isEmpty()) {
            savePaymentOrders(paymentEvent.getId(), paymentEvent.getPaymentOrderList());
        }

        return paymentEvent;
    }

    @Override
    public List<PaymentEvent> findDelayedInProgressOrUnknownEvents(LocalDateTime before) {
        List<PaymentEvent> paymentEventList = paymentEventDatabase.values().stream()
                .filter(event ->
                        (event.getStatus() == PaymentEventStatus.IN_PROGRESS && event.getExecutedAt().isBefore(before))
                                || event.getStatus() == PaymentEventStatus.UNKNOWN)
                .toList();
        paymentEventList.forEach(event -> event.addPaymentOrderList(findPaymentOrdersByPaymentEventId(event.getId())));

        return paymentEventList;
    }

    public void savePaymentOrders(Long paymentEventId, List<PaymentOrder> paymentOrders) {
        paymentOrderDatabase.put(paymentEventId, assignIdsToPaymentOrders(paymentOrders));
    }

    private List<PaymentOrder> assignIdsToPaymentOrders(List<PaymentOrder> paymentOrders) {
        return paymentOrders.stream()
                .peek(paymentOrder -> {
                    if (paymentOrder.getId() == null) {
                        ReflectionTestUtils.setField(paymentOrder, "id", autoGeneratedOrderId);
                        autoGeneratedOrderId++;
                    }
                })
                .toList();
    }

    public List<PaymentOrder> findPaymentOrdersByPaymentEventId(Long paymentEventId) {
        return paymentOrderDatabase.getOrDefault(paymentEventId, Collections.emptyList());
    }
}
