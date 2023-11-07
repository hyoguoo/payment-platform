package study.paymentintegrationserver.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import study.paymentintegrationserver.entity.OrderInfo;

import java.util.Optional;

public interface OrderInfoRepository extends JpaRepository<OrderInfo, Long> {

    Optional<OrderInfo> findByOrderId(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderInfo o join fetch o.user u join fetch o.product p where o.orderId = :orderId")
    Optional<OrderInfo> findByOrderIdPessimisticLock(String orderId);
}
