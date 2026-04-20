package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentHistoryRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentHistoryEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.QPaymentHistoryEntity;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class PaymentHistoryRepositoryImpl implements PaymentHistoryRepository {

    private final JpaPaymentHistoryRepository jpaPaymentHistoryRepository;
    private final JPAQueryFactory queryFactory;

    @Override
    public PaymentHistory save(PaymentHistory paymentHistory) {
        PaymentHistoryEntity entity = PaymentHistoryEntity.from(paymentHistory);
        PaymentHistoryEntity savedEntity = jpaPaymentHistoryRepository.save(entity);
        return savedEntity.toDomain();
    }

    @Override
    public Map<PaymentEventStatus, Long> countTransitionsByStatusWithinWindow(LocalDateTime startTime) {
        QPaymentHistoryEntity qHistory = QPaymentHistoryEntity.paymentHistoryEntity;

        List<Tuple> results = queryFactory
                .select(
                        qHistory.currentStatus,
                        qHistory.count()
                )
                .from(qHistory)
                .where(qHistory.changeStatusAt.goe(startTime))
                .groupBy(qHistory.currentStatus)
                .fetch();

        return results.stream()
                .collect(Collectors.toMap(
                        tuple -> tuple.get(qHistory.currentStatus),
                        tuple -> tuple.get(qHistory.count()),
                        Long::sum
                ));
    }
}
