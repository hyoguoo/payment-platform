package study.paymentintegrationserver.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import study.paymentintegrationserver.entity.OrderInfo;

public interface OrderInfoRepository extends JpaRepository<OrderInfo, Long> {

    @Query("select o from OrderInfo o join fetch o.product join fetch o.user where o.id = :id")
    Optional<OrderInfo> findByIdWithProductAndUser(Long id);

    @Query("select o from OrderInfo o join fetch o.product join fetch o.user where o.orderId = :orderId")
    Optional<OrderInfo> findByOrderIdWithProductAndUser(String orderId);

    @Query("select o from OrderInfo o join fetch o.product join fetch o.user")
    Page<OrderInfo> findAllWithProductAndUser(Pageable pageable);

    @Query("select o from OrderInfo o join fetch o.product join fetch o.user where o.id < :cursor")
    Slice<OrderInfo> findAllWithProductAndUserWithCursor(Pageable pageable, Long cursor);
}
