package study.paymentintegrationserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import study.paymentintegrationserver.entity.OrderInfo;

import java.util.Optional;

public interface OrderInfoRepository extends JpaRepository<OrderInfo, Long> {

    Optional<OrderInfo> findByOrderId(String orderId);
}
