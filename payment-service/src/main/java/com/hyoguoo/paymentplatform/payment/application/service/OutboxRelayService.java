package com.hyoguoo.paymentplatform.payment.application.service;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.MessagePublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.dto.event.PaymentConfirmCommandMessage;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional Outbox relay — outbox 레코드를 Kafka로 발행하고 DONE 전이를 수행한다.
 * ADR-04: KafkaTemplate 직접 호출 금지. MessagePublisherPort를 통해서만 발행.
 *
 * <p>Idempotency: claimToInFlight 원자 선점을 통해 동일 orderId에 대한 중복 발행을 방지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final MessagePublisherPort messagePublisherPort;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final LocalDateTimeProvider localDateTimeProvider;

    /**
     * orderId에 해당하는 outbox 레코드를 Kafka로 릴레이한다.
     *
     * <p>Step 1: claimToInFlight — 원자 선점. false이면 다른 워커가 처리 중이므로 즉시 리턴 (멱등성 보장).
     * <p>Step 2: paymentEvent 조회 후 Kafka payload 구성.
     * <p>Step 3: messagePublisherPort.send() — 실패 시 예외 전파 (상태 전이 방지).
     * <p>Step 4: 성공 시 outbox.toDone() + save().
     *
     * <p>@Transactional: @Modifying UPDATE(claimToInFlight) + save가 TX를 요구한다.
     * AFTER_COMMIT 리스너·@Scheduled 워커 모두 TX-less 진입점이므로 이 메서드에서 TX를 시작한다.
     * Kafka send가 TX 안에서 수행되지만 outbox 패턴에선 실패 시 rollback으로 PENDING 유지가 올바른 동작.
     */
    @Transactional
    public void relay(String orderId) {
        LocalDateTime now = localDateTimeProvider.now();

        // Step 1: 원자 선점 — false이면 다른 워커가 처리 중이므로 포기
        boolean claimed = paymentOutboxRepository.claimToInFlight(orderId, now);
        if (!claimed) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SKIPPED,
                    () -> "OutboxRelayService: claimToInFlight 실패(다른 워커 처리 중), orderId=" + orderId);
            return;
        }

        // Step 2: outbox 로드 후 paymentEvent 조회
        Optional<PaymentOutbox> outboxOpt = paymentOutboxRepository.findByOrderId(orderId);
        if (outboxOpt.isEmpty()) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                    () -> "OutboxRelayService: outbox 없음 orderId=" + orderId);
            return;
        }
        PaymentOutbox outbox = outboxOpt.get();

        PaymentEvent paymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        // Step 3: payload 구성 후 발행 — 실패 시 예외 전파
        PaymentConfirmCommandMessage message = buildMessage(paymentEvent);
        messagePublisherPort.send(PaymentTopics.COMMANDS_CONFIRM, orderId, message);

        // Step 4: 성공 시 DONE 전이 + save
        outbox.toDone();
        paymentOutboxRepository.save(outbox);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                () -> "OutboxRelayService: relay 완료 orderId=" + orderId);
    }

    private PaymentConfirmCommandMessage buildMessage(PaymentEvent paymentEvent) {
        // eventUuid는 orderId로 재사용한다. confirm 명령은 orderId당 1회만 발행되므로
        // orderId가 곧 메시지 고유 식별자로 기능하며 pg-service의 Redis dedupe 키로 유효하다.
        return new PaymentConfirmCommandMessage(
                paymentEvent.getOrderId(),
                paymentEvent.getPaymentKey(),
                paymentEvent.getTotalAmount(),
                paymentEvent.getGatewayType(),
                paymentEvent.getOrderId()
        );
    }
}
