package study.paymentintegrationserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import study.paymentintegrationserver.entity.OrderInfo;

public interface OrderInfoRepository extends JpaRepository<OrderInfo, Long> {
}
