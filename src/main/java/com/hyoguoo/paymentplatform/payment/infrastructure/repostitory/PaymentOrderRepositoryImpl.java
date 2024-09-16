package com.hyoguoo.paymentplatform.payment.infrastructure.repostitory;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentOrderRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    public Optional<PaymentOrder> findByOrderId(String orderId) {
        return jpaPaymentOrderRepository
                .findByOrderId(orderId)
                .map(PaymentOrderEntity::toDomain);
    }

    @Override
    public Page<PaymentOrder> findAll(Pageable pageable) {
        return jpaPaymentOrderRepository
                .findAll(pageable)
                .map(PaymentOrderEntity::toDomain);
    }

    @Override
    public PaymentOrder saveOrUpdate(PaymentOrder paymentOrder) {
        return jpaPaymentOrderRepository.save(PaymentOrderEntity.from(paymentOrder)).toDomain();
    }
}
