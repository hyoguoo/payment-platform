package com.hyoguoo.paymentplatform.payment.infrastructure.repostitory;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentHistoryRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentHistoryEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentHistoryRepositoryImpl implements PaymentHistoryRepository {

    private final JpaPaymentHistoryRepository jpaPaymentHistoryRepository;

    @Override
    public PaymentHistory save(PaymentHistory paymentHistory) {
        PaymentHistoryEntity entity = PaymentHistoryEntity.from(paymentHistory);
        PaymentHistoryEntity savedEntity = jpaPaymentHistoryRepository.save(entity);
        return savedEntity.toDomain();
    }
}
