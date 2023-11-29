package study.paymentintegrationserver.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import study.paymentintegrationserver.entity.OrderInfo;

import java.util.Optional;

public interface OrderInfoRepository extends JpaRepository<OrderInfo, Long> {

    @Query("select o from OrderInfo o join fetch o.product join fetch o.user where o.id = :id")
    Optional<OrderInfo> findByIdWithProductAndUser(Long id);

    @Query("select o from OrderInfo o join fetch o.product join fetch o.user where o.orderId = :orderId")
    Optional<OrderInfo> findByOrderIdWithProductAndUser(String orderId);

    @Query("select o from OrderInfo o join fetch o.product join fetch o.user")
    Page<OrderInfo> findAllWithProductAndUser(Pageable pageable);

    @Query("select o from OrderInfo o join fetch o.product join fetch o.user where o.id < :cursor")
    Slice<OrderInfo> findAllWithProductAndUserWithCursor(Pageable pageable, Long cursor);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderInfo o join fetch o.user u join fetch o.product p where o.orderId = :orderId")
    Optional<OrderInfo> findByOrderIdPessimisticLock(String orderId);
}
