package com.hyoguoo.paymentplatform.payment.application.service;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.MessagePublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.dto.event.PaymentConfirmCommandMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional Outbox relay — outbox 레코드를 Kafka 로 발행하고 DONE 전이를 수행한다.
 * KafkaTemplate 직접 호출 금지 — MessagePublisherPort 를 통해서만 발행한다.
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
    private final Clock clock;

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
        Instant now = clock.instant();

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

        // Step 2.5: 결제가 이미 확정 결과를 적용할 수 없는 상태(종결·격리)면 발행하지 않는다.
        // 브로커 장애로 발행이 만료 시한을 넘기면 결제는 만료·취소로 먼저 종결될 수 있는데,
        // 그 상태에서 확정 명령이 뒤늦게 나가면 벤더가 승인해 되돌릴 수 없는 과금이 생긴다.
        // outbox를 FAILED로 종결해 재시도 대상에서 완전히 뺀다 — IN_FLIGHT로 남기면 타임아웃
        // 복구가 PENDING으로 되돌려 같은 판정이 무한 반복된다.
        if (!paymentEvent.getStatus().canApplyConfirmResult()) {
            outbox.toFailed();
            paymentOutboxRepository.save(outbox);
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_OUTBOX_RELAY_SKIPPED_TERMINAL_PAYMENT,
                    () -> "OutboxRelayService: 결제가 확정 결과 적용 불가 상태라 발행을 건너뛰고 outbox를 종결한다, orderId="
                            + orderId + " paymentStatus=" + paymentEvent.getStatus());
            return;
        }

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
