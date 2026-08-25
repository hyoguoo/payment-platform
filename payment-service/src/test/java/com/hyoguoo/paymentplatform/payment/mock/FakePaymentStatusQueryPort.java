package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentStatusQueryPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentStatusSnapshot;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PaymentStatusQueryPort Fake — DB 없이 application 계층 테스트용.
 * <p>
 * 주문번호별로 발행 상태와 스냅샷을 미리 심어 두고 그대로 돌려준다. Thread-safe: ConcurrentHashMap.
 */
public class FakePaymentStatusQueryPort implements PaymentStatusQueryPort {

    private final ConcurrentHashMap<String, PaymentOutboxStatus> activeOutboxStatuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PaymentStatusSnapshot> statusSnapshots = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentOutboxStatus> findActiveOutboxStatus(String orderId) {
        return Optional.ofNullable(activeOutboxStatuses.get(orderId));
    }

    @Override
    public Optional<PaymentStatusSnapshot> findStatusSnapshot(String orderId) {
        return Optional.ofNullable(statusSnapshots.get(orderId));
    }

    // --- fixture 헬퍼 ---

    public void putActiveOutboxStatus(String orderId, PaymentOutboxStatus status) {
        activeOutboxStatuses.put(orderId, status);
    }

    public void putStatusSnapshot(String orderId, PaymentStatusSnapshot snapshot) {
        statusSnapshots.put(orderId, snapshot);
    }

    public void clear() {
        activeOutboxStatuses.clear();
        statusSnapshots.clear();
    }
}
