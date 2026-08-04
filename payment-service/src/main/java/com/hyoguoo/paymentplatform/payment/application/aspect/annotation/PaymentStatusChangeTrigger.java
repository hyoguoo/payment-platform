package com.hyoguoo.paymentplatform.payment.application.aspect.annotation;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * {@link PaymentStatusChange#trigger()} 및 trigger 파라미터에 넘길 값 상수.
 * 오타로 지표 라벨이 갈라지는 것을 막기 위해 여기서만 정의한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PaymentStatusChangeTrigger {

    /** 벤더 승인 결과 수신 처리 흐름 — 완료/실패/격리 전이 모두 이 값을 쓴다. */
    public static final String CONFIRM = "confirm";
    public static final String EXPIRATION = "expiration";
    public static final String MANUAL = "manual";
    /** 확정 전 재고 차감 거절(재고 부족)로 인한 실패 전이. */
    public static final String STOCK_FAILURE = "stock_failure";
    /** 재고 캐시(Redis) 장애로 벤더 상태를 확인할 수 없어 거는 격리 전이. */
    public static final String STOCK_CACHE_DOWN = "stock_cache_down";
}
