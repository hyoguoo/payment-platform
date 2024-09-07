package com.hyoguoo.paymentplatform.order.service.port;

import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderInfoRepository {

    Optional<OrderInfo> findById(Long id);

    Optional<OrderInfo> findByOrderId(String orderId);

    Page<OrderInfo> findAll(Pageable pageable);

    OrderInfo saveOrUpdate(OrderInfo orderInfo);
}
