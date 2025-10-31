package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentProcessRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentProcess;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentProcessStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentProcessEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentProcessRepositoryImpl implements PaymentProcessRepository {

    private final JpaPaymentProcessRepository jpaPaymentProcessRepository;

    @Override
    public PaymentProcess save(PaymentProcess paymentProcess) {
        PaymentProcessEntity entity = PaymentProcessEntity.from(paymentProcess);
        PaymentProcessEntity savedEntity = jpaPaymentProcessRepository.save(entity);
        return savedEntity.toDomain();
    }

    @Override
    public Optional<PaymentProcess> findByOrderId(String orderId) {
        return jpaPaymentProcessRepository.findByOrderId(orderId)
                .map(PaymentProcessEntity::toDomain);
    }

    @Override
    public List<PaymentProcess> findAllByStatus(PaymentProcessStatus status) {
        return jpaPaymentProcessRepository.findAllByStatus(status)
                .stream()
                .map(PaymentProcessEntity::toDomain)
                .toList();
    }

    @Override
    public boolean existsByOrderId(String orderId) {
        return jpaPaymentProcessRepository.existsByOrderId(orderId);
    }
}
