package com.hyoguoo.paymentplatform.product.core.common.log;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventType {

    STOCK_COMMIT_RECEIVED,
    STOCK_COMMIT_DUPLICATE,
    STOCK_COMMIT_RDB_DONE,

    EVENT_DEDUPE_RECORD,

    EXCEPTION,
}
