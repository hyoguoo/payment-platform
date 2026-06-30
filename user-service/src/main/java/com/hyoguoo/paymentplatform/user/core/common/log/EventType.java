package com.hyoguoo.paymentplatform.user.core.common.log;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventType {

    EXCEPTION,
    USER_QUERY_RECEIVED,

    SCHEDULER_ENABLED,

    METRICS_INIT,
    METRICS_GAUGE_UPDATED,
}
