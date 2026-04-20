package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.core.common.dto.PageResponse;
import com.hyoguoo.paymentplatform.core.common.dto.PageSpec;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class AdminPaymentQueryRepositoryImpl implements AdminPaymentQueryRepository {

    private static final QPaymentEventEntity paymentEvent = QPaymentEventEntity.paymentEventEntity;
    private static final QPaymentOrderEntity paymentOrder = QPaymentOrderEntity.paymentOrderEntity;
    private static final QPaymentHistoryEntity paymentHistory = QPaymentHistoryEntity.paymentHistoryEntity;
    private final JPAQueryFactory queryFactory;

    private static PageRequest toPageable(PageSpec pageSpec) {
        return PageRequest.of(
                pageSpec.getZeroBasedPage(),
                pageSpec.getSize(),
                Sort.by(Direction.fromString(pageSpec.getSortDirection().toString()), pageSpec.getSortBy())
        );
    }

    private static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent())
                .currentPage(page.getNumber() + 1)
                .totalPages(page.getTotalPages())
                .totalElements(page.getTotalElements())
                .pageSize(page.getSize())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .isFirst(page.isFirst())
                .isLast(page.isLast())
                .build();
    }

    @Override
    public PageResponse<PaymentEvent> searchPaymentEvents(PaymentEventSearchQuery searchQuery, PageSpec pageSpec) {
        Pageable pageable = toPageable(pageSpec);
        BooleanExpression predicate = orderIdContains(searchQuery.getOrderId());

        List<Long> eventIds = queryFactory
                .select(paymentEvent.id)
                .from(paymentEvent)
                .where(predicate)
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(paymentEvent.createdAt.desc())
                .fetch();

        List<PaymentEventEntity> entities = eventIds.isEmpty() ? List.of() :
                queryFactory
                        .selectFrom(paymentEvent)
                        .where(paymentEvent.id.in(eventIds))
                        .orderBy(paymentEvent.createdAt.desc())
                        .fetch();

        List<PaymentOrderEntity> allOrders = eventIds.isEmpty() ? List.of() :
                queryFactory
                        .selectFrom(paymentOrder)
                        .where(paymentOrder.paymentEventId.in(eventIds))
                        .orderBy(paymentOrder.id.asc())
                        .fetch();

        Map<Long, List<PaymentOrderEntity>> ordersByEventId = allOrders.stream()
                .collect(Collectors.groupingBy(PaymentOrderEntity::getPaymentEventId));

        List<PaymentEvent> content = entities.stream()
                .map(entity -> {
                    List<PaymentOrder> orders = ordersByEventId
                            .getOrDefault(entity.getId(), List.of())
                            .stream()
                            .map(PaymentOrderEntity::toDomain)
                            .toList();
                    return entity.toDomain(orders);
                })
                .toList();

        long total = count(paymentEvent, predicate);
        Page<PaymentEvent> page = new PageImpl<>(content, pageable, total);

        return toPageResponse(page);
    }

    @Override
    public PageResponse<PaymentHistory> searchPaymentHistories(PaymentHistorySearchQuery searchQuery,
            PageSpec pageSpec) {
        Pageable pageable = toPageable(pageSpec);
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

        Page<PaymentHistory> page = new PageImpl<>(content, pageable, total);

        return toPageResponse(page);
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
