package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventSearchQuery;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistorySearchQuery;
import com.hyoguoo.paymentplatform.payment.application.port.AdminPaymentQueryRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentHistoryEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.QPaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.QPaymentHistoryEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.QPaymentOrderEntity;
import com.querydsl.core.types.EntityPath;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.Wildcard;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class AdminPaymentQueryRepositoryImpl implements AdminPaymentQueryRepository {

    private static final QPaymentEventEntity paymentEvent = QPaymentEventEntity.paymentEventEntity;
    private static final QPaymentOrderEntity paymentOrder = QPaymentOrderEntity.paymentOrderEntity;
    private static final QPaymentHistoryEntity paymentHistory = QPaymentHistoryEntity.paymentHistoryEntity;
    private final JPAQueryFactory queryFactory;

    @Override
    public Page<PaymentEvent> searchPaymentEvents(PaymentEventSearchQuery searchQuery, Pageable pageable) {
        BooleanExpression predicate = orderIdContains(searchQuery.getOrderId());
        JPAQuery<PaymentEventEntity> query = queryFactory
                .selectFrom(paymentEvent)
                .where(predicate);
        long total = count(paymentEvent, predicate);

        List<PaymentEventEntity> entities = query
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(paymentEvent.createdAt.desc())
                .fetch();

        List<PaymentEvent> content = entities.stream()
                .map(entity -> entity.toDomain(List.of()))
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    @Override
    public Page<PaymentHistory> searchPaymentHistories(PaymentHistorySearchQuery searchQuery, Pageable pageable) {
        BooleanExpression predicate = historyOrderIdContains(searchQuery.getOrderId());
        JPAQuery<PaymentHistoryEntity> query = queryFactory
                .selectFrom(paymentHistory)
                .where(predicate);
        long total = count(paymentHistory, predicate);

        List<PaymentHistoryEntity> entities = query
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(paymentHistory.changeStatusAt.desc())
                .fetch();

        List<PaymentHistory> content = entities.stream()
                .map(PaymentHistoryEntity::toDomain)
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    @Override
    public Optional<PaymentEvent> findPaymentEventWithOrdersById(Long eventId) {
        PaymentEventEntity eventEntity = queryFactory
                .selectFrom(paymentEvent)
                .where(paymentEvent.id.eq(eventId))
                .fetchOne();

        if (eventEntity == null) {
            return Optional.empty();
        }

        List<PaymentOrderEntity> orderEntities = queryFactory
                .selectFrom(paymentOrder)
                .where(paymentOrder.paymentEventId.eq(eventId))
                .orderBy(paymentOrder.id.asc())
                .fetch();

        List<PaymentOrder> domainOrders = orderEntities.stream()
                .map(PaymentOrderEntity::toDomain)
                .toList();

        PaymentEvent domainEvent = eventEntity.toDomain(domainOrders);

        return Optional.of(domainEvent);
    }

    @Override
    public List<PaymentOrder> findPaymentOrdersByEventId(Long eventId) {
        List<PaymentOrderEntity> entities = queryFactory
                .selectFrom(paymentOrder)
                .where(paymentOrder.paymentEventId.eq(eventId))
                .orderBy(paymentOrder.id.asc())
                .fetch();

        return entities.stream()
                .map(PaymentOrderEntity::toDomain)
                .toList();
    }

    @Override
    public List<PaymentHistory> findPaymentHistoriesByEventId(Long eventId) {
        List<PaymentHistoryEntity> entities = queryFactory
                .selectFrom(paymentHistory)
                .where(paymentHistory.paymentEventId.eq(eventId))
                .orderBy(paymentHistory.changeStatusAt.desc())
                .fetch();

        return entities.stream()
                .map(PaymentHistoryEntity::toDomain)
                .toList();
    }

    private BooleanExpression orderIdContains(String orderId) {
        return StringUtils.hasText(orderId)
                ? paymentEvent.orderId.contains(orderId)
                : Expressions.TRUE.isTrue();
    }

    private BooleanExpression historyOrderIdContains(String orderId) {
        if (!StringUtils.hasText(orderId)) {
            return Expressions.TRUE.isTrue();
        }
        return paymentHistory.paymentEventId.in(
                queryFactory.select(paymentEvent.id)
                        .from(paymentEvent)
                        .where(paymentEvent.orderId.contains(orderId))
        );
    }

    private long count(EntityPath<?> root, BooleanExpression predicate) {
        return queryFactory
                .select(Wildcard.count)
                .from(root)
                .where(predicate)
                .fetch()
                .stream()
                .findFirst()
                .orElse(0L);
    }
}
