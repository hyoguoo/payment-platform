package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCommitEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto.ConfirmedEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment.events.confirmed 소비 후 결제 상태 분기 use-case.
 * ADR-04(2단 멱등성): eventUUID dedupe 선행, 처리 주체 결정.
 * ADR-14: stock 이벤트 발행(commit/restore) 담당.
 *
 * <p>상태 분기:
 * <ul>
 *   <li>APPROVED → PaymentEvent DONE 전이 + stock.events.commit 발행</li>
 *   <li>FAILED → PaymentEvent FAILED 전이 + stock.events.restore 발행</li>
 *   <li>QUARANTINED → QuarantineCompensationHandler.handle(FCG 진입점) 위임</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmResultUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final EventDedupeStore eventDedupeStore;
    private final StockCommitEventPublisherPort stockCommitEventPublisherPort;
    private final StockRestoreEventPublisherPort stockRestoreEventPublisherPort;
    private final QuarantineCompensationHandler quarantineCompensationHandler;

    @Transactional
    public void handle(ConfirmedEventMessage message) {
        // TODO: implement — GREEN 단계에서 구현
        throw new UnsupportedOperationException("not implemented yet");
    }
}
