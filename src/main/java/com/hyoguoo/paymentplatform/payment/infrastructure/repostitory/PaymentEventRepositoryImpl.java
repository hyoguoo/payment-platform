package com.hyoguoo.paymentplatform.payment.infrastructure.repostitory;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class PaymentEventRepositoryImpl implements PaymentEventRepository {

    private final JpaPaymentEventRepository jpaPaymentEventRepository;
    private final JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentEvent> findById(Long id) {
        return jpaPaymentEventRepository
                .findById(id)
                .map(paymentEventEntity -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    paymentEventEntity.getId()
                            ).stream()
                            .map(PaymentOrderEntity::toDomain)
                            .toList();
                    return paymentEventEntity.toDomain(paymentOrderList);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentEvent> findByOrderId(String orderId) {
        return jpaPaymentEventRepository
                .findByOrderId(orderId)
                .map(paymentEventEntity -> {
                    List<PaymentOrder> paymentOrderList = jpaPaymentOrderRepository.findByPaymentEventId(
                                    paymentEventEntity.getId()
                            )
                            .stream()
                            .map(PaymentOrderEntity::toDomain)
                            .toList();
                    return paymentEventEntity.toDomain(paymentOrderList);
                });
    }

    @Override
    public PaymentEvent saveOrUpdate(PaymentEvent paymentEvent) {
        return jpaPaymentEventRepository.save(PaymentEventEntity.from(paymentEvent))
                .toDomain(paymentEvent.getPaymentOrderList());
    }
}
