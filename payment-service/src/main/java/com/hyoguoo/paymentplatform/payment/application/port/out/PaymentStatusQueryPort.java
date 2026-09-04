package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.util.Optional;

/**
 * 폴링 상태 조회 전용 포트 — 돈 경로 판정(발행 워커, confirm 결과 반영 등)이 쓰는 조회와 분리한다.
 *
 * <p>이 포트로 가는 조회만 읽기 복제본에 묶는다. 다른 조회가 이 포트를 함께 쓰면 복제 지연 중에
 * 판정이 낡은 상태를 읽을 수 있다.
 */
public interface PaymentStatusQueryPort {

    /**
     * 발행 대기(PENDING) 또는 발행 중(IN_FLIGHT)일 때만 값을 돌려준다.
     * 발행 기록이 없거나 이미 종결됐으면 빈 값 — 이 경우 호출자는 {@link #findStatusSnapshot}으로 넘어간다.
     */
    Optional<PaymentOutboxStatus> findActiveOutboxStatus(String orderId);

    Optional<PaymentStatusSnapshot> findStatusSnapshot(String orderId);
}
