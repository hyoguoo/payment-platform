package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmDlqPublisher;
import java.util.ArrayList;
import java.util.List;

/**
 * PaymentConfirmDlqPublisher Fake — in-memory 캡처 구현체 (테스트 전용).
 * dedupe remove 실패 시 DLQ 전송 경로 검증용.
 */
public class FakePaymentConfirmDlqPublisher implements PaymentConfirmDlqPublisher {

    public record DlqRecord(String eventUuid, String reason) {}

    private final List<DlqRecord> published = new ArrayList<>();

    @Override
    public void publishDlq(String eventUuid, String reason) {
        published.add(new DlqRecord(eventUuid, reason));
    }

    public List<DlqRecord> getPublished() {
        return List.copyOf(published);
    }

    public int publishedCount() {
        return published.size();
    }

    public void reset() {
        published.clear();
    }
}
