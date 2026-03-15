package com.hyoguoo.paymentplatform.payment.infrastructure.adapter;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult.ResponseType;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "spring.payment.async-strategy",
        havingValue = "kafka"
)
public class KafkaConfirmAdapter implements PaymentConfirmService {

    private static final String TOPIC = "payment-confirm-requests";

    private final PaymentTransactionCoordinator transactionCoordinator;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public PaymentConfirmAsyncResult confirm(PaymentConfirmCommand command)
            throws PaymentOrderedProductStockException {
        PaymentEvent paymentEvent =
                paymentLoadUseCase.getPaymentEventByOrderId(command.getOrderId());

        // PaymentEvent READY → IN_PROGRESS 전환 + paymentKey 기록
        // 컨슈머가 나중에 paymentEvent.getPaymentKey()를 조회하기 위해 반드시 선행 호출
        paymentCommandUseCase.executePayment(paymentEvent, command.getPaymentKey());

        // 재고 감소 (트랜잭션 내, Outbox 레코드 생성 없음)
        transactionCoordinator.executeStockDecreaseOnly(
                command.getOrderId(), paymentEvent.getPaymentOrderList()
        );

        // Kafka 발행 — 트랜잭션 커밋 이후 호출로 소비자 타이밍 레이스 방지
        // 실패 시 KafkaException 전파 → 상위 컨트롤러의 예외 핸들러 처리
        kafkaTemplate.send(TOPIC, command.getOrderId(), command.getOrderId());

        return PaymentConfirmAsyncResult.builder()
                .responseType(ResponseType.ASYNC_202)
                .orderId(command.getOrderId())
                .amount(command.getAmount())
                .build();
    }
}
