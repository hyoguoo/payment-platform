package com.hyoguoo.paymentplatform.order.infrastructure.repostitory;

import com.hyoguoo.paymentplatform.order.infrastructure.entity.OrderInfoEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaOrderInfoRepository extends JpaRepository<OrderInfoEntity, Long> {

    Optional<OrderInfoEntity> findByOrderId(String orderId);

    Page<OrderInfoEntity> findAll(Pageable pageable);
}

