package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import java.util.List;
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

    @Override
    public List<PaymentOrder> saveAll(List<PaymentOrder> paymentOrderList) {
        List<PaymentOrderEntity> paymentOrderEntityList = paymentOrderList.stream()
                .map(PaymentOrderEntity::from)
                .toList();
        return jpaPaymentOrderRepository.saveAll(paymentOrderEntityList)
                .stream()
                .map(PaymentOrderEntity::toDomain)
                .toList();
    }
}
