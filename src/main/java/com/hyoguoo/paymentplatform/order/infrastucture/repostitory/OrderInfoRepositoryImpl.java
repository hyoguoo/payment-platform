package com.hyoguoo.paymentplatform.order.infrastucture.repostitory;

import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import com.hyoguoo.paymentplatform.order.infrastucture.entity.OrderInfoEntity;
import com.hyoguoo.paymentplatform.order.service.port.OrderInfoRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OrderInfoRepositoryImpl implements OrderInfoRepository {

    private final JpaOrderInfoRepository jpaOrderInfoRepository;

    @Override
    public Optional<OrderInfo> findById(Long id) {
        return jpaOrderInfoRepository
                .findById(id)
                .map(OrderInfoEntity::toDomain);
    }

    @Override
    public Optional<OrderInfo> findByOrderId(String orderId) {
        return jpaOrderInfoRepository
                .findByOrderId(orderId)
                .map(OrderInfoEntity::toDomain);
    }

    @Override
    public Page<OrderInfo> findAll(Pageable pageable) {
        return jpaOrderInfoRepository
                .findAll(pageable)
                .map(OrderInfoEntity::toDomain);
    }

    @Override
    public OrderInfo saveOrUpdate(OrderInfo orderInfo) {
        return jpaOrderInfoRepository.save(OrderInfoEntity.from(orderInfo)).toDomain();
    }
}
