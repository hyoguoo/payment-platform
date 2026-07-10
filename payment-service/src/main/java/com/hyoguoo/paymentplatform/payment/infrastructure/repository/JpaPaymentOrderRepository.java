package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaymentOrderRepository extends JpaRepository<PaymentOrderEntity, Long> {

    List<PaymentOrderEntity> findByPaymentEventId(Long paymentEventId);

    // 격리 복구 CAS(JpaPaymentEventRepository#resolveQuarantineToFailed) 게이트 통과 시에만
    // 같은 트랜잭션에서 호출되는 자식 order 동조 갱신. PaymentOrder.fail() 도메인 가드(NOT_STARTED/EXECUTING
    // 에서만 허용)와 동일한 조건을 SQL 레벨에서도 명시해 이미 종결(SUCCESS/FAIL/CANCEL/EXPIRED)된 order 는
    // 건드리지 않는다.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentOrderEntity o SET o.status = 'FAIL' "
            + "WHERE o.paymentEventId = :paymentEventId AND o.status IN ('NOT_STARTED', 'EXECUTING')")
    int failByPaymentEventId(@Param("paymentEventId") Long paymentEventId);
}
