package com.hyoguoo.paymentplatform.payment.scheduler;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.config.RetryPolicyProperties;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.RecoveryDecision;
import com.hyoguoo.paymentplatform.payment.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayStatusUnmappedException;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxProcessingService {

    private final PaymentOutboxUseCase paymentOutboxUseCase;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentTransactionCoordinator transactionCoordinator;
    private final RetryPolicyProperties retryPolicyProperties;
    private final LocalDateTimeProvider localDateTimeProvider;

    public void process(String orderId) {
        // Step 1: atomic claim — 선점 실패 시 다른 워커가 처리 중이므로 포기
        Optional<PaymentOutbox> outboxOpt = paymentOutboxUseCase.claimToInFlight(orderId);
        if (outboxOpt.isEmpty()) {
            return;
        }
        PaymentOutbox outbox = outboxOpt.orElseThrow();

        // Step 2: 로컬 PaymentEvent 로드 — 실패 시 incrementRetryOrFail 후 종료
        Optional<PaymentEvent> paymentEventOpt = loadPaymentEvent(orderId, outbox);
        if (paymentEventOpt.isEmpty()) {
            return;
        }
        PaymentEvent paymentEvent = paymentEventOpt.orElseThrow();

        // Step 3: 로컬 종결 재진입 차단 (REJECT_REENTRY) — PG 조회 불필요, outbox만 멱등 종결
        if (paymentEvent.getStatus().isTerminal()) {
            rejectReentry(outbox);
            return;
        }

        // Step 4: 최초 시도 vs 재시도 분기
        RetryPolicy policy = retryPolicyProperties.toRetryPolicy();
        int retryCount = outbox.getRetryCount();

        if (retryCount == 0) {
            // 최초 시도 — confirm을 보낸 적 없으므로 PG 조회 불필요, 바로 승인 요청
            handleAttemptConfirm(paymentEvent, outbox, policy, orderId);
        } else {
            // 재시도 — 이전 confirm이 PG에 도달했을 수 있으므로 선조회 후 결정
            StatusResolution resolution = resolveStatusAndDecision(orderId, paymentEvent, retryCount,
                    policy.maxAttempts());
            applyDecision(resolution, paymentEvent, outbox, policy, orderId);
        }
    }

    /**
     * PG 상태 조회 후 RecoveryDecision을 수립한다. 예외 발생 시 fromException 분기를 사용하며, snapshot은 Optional.empty()로 반환한다. try 블록 외부 변수
     * 재할당을 피하기 위해 private 메서드로 추출했다.
     */
    private StatusResolution resolveStatusAndDecision(
            String orderId,
            PaymentEvent paymentEvent,
            int retryCount,
            int maxRetries
    ) {
        try {
            PaymentStatusResult snapshot = paymentCommandUseCase.getPaymentStatusByOrderId(
                    orderId, paymentEvent.getGatewayType());
            RecoveryDecision decision = RecoveryDecision.from(paymentEvent, snapshot, retryCount, maxRetries);
            return StatusResolution.ofResult(decision, snapshot);
        } catch (PaymentGatewayRetryableException | PaymentGatewayNonRetryableException |
                 PaymentGatewayStatusUnmappedException e) {
            RecoveryDecision decision = RecoveryDecision.fromException(paymentEvent, e, retryCount, maxRetries);
            return StatusResolution.ofException(decision);
        }
    }

    /**
     * RecoveryDecision에 따라 적절한 TX 메서드로 위임한다.
     */
    private void applyDecision(
            StatusResolution resolution,
            PaymentEvent paymentEvent,
            PaymentOutbox outbox,
            RetryPolicy policy,
            String orderId
    ) {
        RecoveryDecision decision = resolution.decision();

        switch (decision.type()) {
            case COMPLETE_SUCCESS -> {
                LocalDateTime approvedAt = resolution.approvedAt()
                        .orElseThrow(() -> new IllegalStateException(
                                "COMPLETE_SUCCESS decision requires non-null approvedAt, orderId=" + orderId));
                transactionCoordinator.executePaymentSuccessCompletionWithOutbox(paymentEvent, approvedAt, outbox);
            }

            case COMPLETE_FAILURE -> {
                String reason = decision.reason() != null ? decision.reason().name() : "PG_TERMINAL_FAIL";
                LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, () -> reason);
                transactionCoordinator.executePaymentFailureCompensationWithOutbox(
                        orderId, paymentEvent.getPaymentOrderList(), reason);
            }

            case ATTEMPT_CONFIRM -> handleAttemptConfirm(paymentEvent, outbox, policy, orderId);

            case RETRY_LATER -> {
                // 이번 retry 후 소진되는가? → FCG, 아니면 retry
                if (policy.isExhausted(outbox.getRetryCount() + 1)) {
                    handleFinalConfirmationGate(orderId, outbox);
                } else {
                    transactionCoordinator.executePaymentRetryWithOutbox(
                            paymentEvent, outbox, policy, localDateTimeProvider.now());
                }
            }

            case QUARANTINE -> {
                // QUARANTINE: 이미 retryCount >= maxRetries → FCG로 최종 확인
                String reason = decision.reason() != null ? decision.reason().name() : "EXHAUSTED";
                LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, () -> reason);
                handleFinalConfirmationGate(orderId, outbox);
            }

            case GUARD_MISSING_APPROVED_AT -> {
                // PG는 DONE이지만 approvedAt 없음 — 소진이면 FCG, 아니면 다음 틱 재시도
                LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                        () -> "GUARD_MISSING_APPROVED_AT orderId=" + orderId);
                if (policy.isExhausted(outbox.getRetryCount() + 1)) {
                    handleFinalConfirmationGate(orderId, outbox);
                } else {
                    transactionCoordinator.executePaymentRetryWithOutbox(
                            paymentEvent, outbox, policy, localDateTimeProvider.now());
                }
            }

            case REJECT_REENTRY -> rejectReentry(outbox);
        }
    }

    /**
     * ATTEMPT_CONFIRM: confirmPaymentWithGateway 호출 후 PaymentConfirmResultStatus로 2차 분기. RetryableException →
     * RETRYABLE_FAILURE 경로, NonRetryableException → NON_RETRYABLE_FAILURE 경로로 처리한다.
     */
    private void handleAttemptConfirm(
            PaymentEvent paymentEvent,
            PaymentOutbox outbox,
            RetryPolicy policy,
            String orderId
    ) {
        PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                .userId(paymentEvent.getBuyerId())
                .orderId(orderId)
                .paymentKey(paymentEvent.getPaymentKey())
                .amount(paymentEvent.getTotalAmount())
                .gatewayType(paymentEvent.getGatewayType())
                .build();
        try {
            applyConfirmResult(
                    paymentCommandUseCase.confirmPaymentWithGateway(command),
                    paymentEvent, outbox, policy, orderId
            );
        } catch (PaymentGatewayRetryableException e) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            if (policy.isExhausted(outbox.getRetryCount() + 1)) {
                handleFinalConfirmationGate(orderId, outbox);
            } else {
                transactionCoordinator.executePaymentRetryWithOutbox(
                        paymentEvent, outbox, policy, localDateTimeProvider.now());
            }
        } catch (PaymentGatewayNonRetryableException e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            transactionCoordinator.executePaymentFailureCompensationWithOutbox(
                    orderId, paymentEvent.getPaymentOrderList(), e.getMessage());
        } catch (Exception e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                    () -> String.format("confirm uncaught orderId=%s exceptionType=%s message=%s",
                            orderId, e.getClass().getSimpleName(), e.getMessage()));
            throw e;
        }
    }

    private void applyConfirmResult(
            PaymentGatewayInfo gatewayInfo,
            PaymentEvent paymentEvent,
            PaymentOutbox outbox,
            RetryPolicy policy,
            String orderId
    ) {
        PaymentConfirmResultStatus resultStatus = gatewayInfo.getPaymentConfirmResultStatus();

        switch (resultStatus) {
            case SUCCESS -> transactionCoordinator.executePaymentSuccessCompletionWithOutbox(
                    paymentEvent, gatewayInfo.getPaymentDetails().getApprovedAt(), outbox);

            case NON_RETRYABLE_FAILURE -> {
                String reason = gatewayInfo.getPaymentFailure() != null
                        ? gatewayInfo.getPaymentFailure().getMessage() : "NON_RETRYABLE_FAILURE";
                LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, () -> reason);
                transactionCoordinator.executePaymentFailureCompensationWithOutbox(
                        orderId, paymentEvent.getPaymentOrderList(), reason);
            }

            case RETRYABLE_FAILURE -> {
                String reason = gatewayInfo.getPaymentFailure() != null
                        ? gatewayInfo.getPaymentFailure().getMessage() : "RETRYABLE_FAILURE";
                LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, () -> reason);
                // 이번 retry 후 소진이면 FCG
                if (policy.isExhausted(outbox.getRetryCount() + 1)) {
                    handleFinalConfirmationGate(orderId, outbox);
                } else {
                    transactionCoordinator.executePaymentRetryWithOutbox(
                            paymentEvent, outbox, policy, localDateTimeProvider.now());
                }
            }
        }
    }

    /**
     * D7 FCG(Final Confirmation Gate): retryCount 비증가 방식으로 getStatus 1회 재호출. 결과: COMPLETE_SUCCESS → success,
     * COMPLETE_FAILURE → failure, 그 외 → quarantine.
     * <p>
     * DE-1 대응: retry TX 이후 DB 상태가 변경됐을 수 있으므로, paymentEvent를 DB에서 fresh 재조회한다. process() 진입 시 로드한 stale 객체를 그대로 전달하면
     * JPA merge 시 잘못된 previousStatus가 PaymentHistory에 기록될 수 있다.
     * </p>
     */
    private void handleFinalConfirmationGate(
            String orderId,
            PaymentOutbox outbox
    ) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                () -> "FCG 발동 orderId=" + orderId + " retryCount=" + outbox.getRetryCount());

        // FCG: DB에서 fresh paymentEvent 재조회 (stale 방지)
        PaymentEvent freshPaymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        // FCG: retryCount를 0으로 고정하여 COMPLETE_*/RETRY_LATER 분기만 활성화
        StatusResolution fcgResolution = resolveFcgStatusAndDecision(orderId, freshPaymentEvent);

        switch (fcgResolution.decision().type()) {
            case COMPLETE_SUCCESS -> {
                LocalDateTime approvedAt = fcgResolution.approvedAt()
                        .orElseThrow(() -> new IllegalStateException(
                                "FCG COMPLETE_SUCCESS requires non-null approvedAt, orderId=" + orderId));
                transactionCoordinator.executePaymentSuccessCompletionWithOutbox(freshPaymentEvent, approvedAt, outbox);
            }

            case COMPLETE_FAILURE -> {
                String reason = fcgResolution.decision().reason() != null
                        ? fcgResolution.decision().reason().name() : "PG_TERMINAL_FAIL";
                transactionCoordinator.executePaymentFailureCompensationWithOutbox(
                        orderId, freshPaymentEvent.getPaymentOrderList(), reason);
            }

            default -> {
                // RETRY_LATER, QUARANTINE, GUARD_MISSING_APPROVED_AT, ATTEMPT_CONFIRM 등 — 판단 불가 → 격리
                String reason = fcgResolution.decision().reason() != null
                        ? fcgResolution.decision().reason().name() : "CONFIRM_EXHAUSTED";
                LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                        () -> "FCG 판단불가 → 격리 orderId=" + orderId + " reason=" + reason);
                transactionCoordinator.executePaymentQuarantineWithOutbox(freshPaymentEvent, outbox, reason);
            }
        }
    }

    /**
     * FCG용 getStatus 재호출 — retryCount=0, maxRetries=1로 고정하여 소진 분기를 비활성화. COMPLETE_SUCCESS/FAILURE 판별만 목적이며, 결과가
     * RETRY_LATER이면 outer switch가 quarantine으로 처리한다. try 블록 외부 변수 재할당을 피하기 위해 private 메서드로 추출했다.
     */
    private StatusResolution resolveFcgStatusAndDecision(String orderId, PaymentEvent paymentEvent) {
        try {
            PaymentStatusResult snapshot = paymentCommandUseCase.getPaymentStatusByOrderId(
                    orderId, paymentEvent.getGatewayType());
            RecoveryDecision decision = RecoveryDecision.from(paymentEvent, snapshot, 0, 1);
            return StatusResolution.ofResult(decision, snapshot);
        } catch (PaymentGatewayRetryableException | PaymentGatewayNonRetryableException |
                 PaymentGatewayStatusUnmappedException e) {
            RecoveryDecision decision = RecoveryDecision.fromException(paymentEvent, e, 0, 1);
            return StatusResolution.ofException(decision);
        }
    }

    /**
     * REJECT_REENTRY: outbox가 IN_FLIGHT 상태이므로 toDone()으로 멱등 종결. PG 조회 없이 outbox만 종결 처리.
     * <p>
     * DE-4 참고: outbox.toDone() + save()는 @Transactional 경계 밖에서 수행된다. 동시성 위험은 claimToInFlight의 원자적 선점으로 이미 배제되었으므로 의도적
     * 설계다. 별도 TX 없이 단순 save()로 처리해도 안전하다.
     * </p>
     */
    private void rejectReentry(PaymentOutbox outbox) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                () -> "REJECT_REENTRY: 로컬 종결 상태이나 outbox IN_FLIGHT — outbox 멱등 종결 orderId=" + outbox.getOrderId());
        outbox.toDone();
        paymentOutboxUseCase.save(outbox);
    }

    private Optional<PaymentEvent> loadPaymentEvent(String orderId, PaymentOutbox outbox) {
        try {
            return Optional.of(paymentLoadUseCase.getPaymentEventByOrderId(orderId));
        } catch (Exception e) {
            // intentionally broad — any load failure retries via outbox mechanism
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            paymentOutboxUseCase.incrementRetryOrFail(orderId, outbox);
            return Optional.empty();
        }
    }

    /**
     * PG 상태 조회 결과와 결정을 함께 보관하는 내부 레코드. approvedAt을 RecoveryDecision 밖에서 전달하기 위해 사용한다.
     */
    private record StatusResolution(RecoveryDecision decision, Optional<PaymentStatusResult> snapshot) {

        static StatusResolution ofResult(RecoveryDecision decision, PaymentStatusResult snapshot) {
            return new StatusResolution(decision, Optional.of(snapshot));
        }

        static StatusResolution ofException(RecoveryDecision decision) {
            return new StatusResolution(decision, Optional.empty());
        }

        Optional<LocalDateTime> approvedAt() {
            return snapshot.map(PaymentStatusResult::approvedAt);
        }
    }
}
