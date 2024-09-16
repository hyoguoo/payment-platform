package com.hyoguoo.paymentplatform.payment.infrastructure.repostitory;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentOrderRepositoryImpl implements PaymentOrderRepository {

    private final JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @Override
    public Optional<PaymentOrder> findById(Long id) {
        return jpaPaymentOrderRepository
                .findById(id)
                .map(PaymentOrderEntity::toDomain);
    }

    @Override
    public PaymentOrder saveOrUpdate(PaymentOrder paymentOrder) {
        return jpaPaymentOrderRepository.save(PaymentOrderEntity.from(paymentOrder)).toDomain();
    }
}
