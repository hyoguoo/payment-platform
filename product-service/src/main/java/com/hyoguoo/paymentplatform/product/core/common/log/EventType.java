package com.hyoguoo.paymentplatform.product.core.common.log;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventType {

    STOCK_COMMIT_RECEIVED,
    STOCK_COMMIT_DUPLICATE,
    STOCK_COMMIT_RDB_DONE,
    STOCK_COMMIT_INSUFFICIENT,
    STOCK_COMMIT_QUARANTINE_KEY_FALLBACK,

    EVENT_DEDUPE_RECORD,
    EVENT_DEDUPE_CLEANUP,

    SCHEDULER_ENABLED,

    EXCEPTION,

    METRICS_INIT,
    METRICS_GAUGE_UPDATED,
}
