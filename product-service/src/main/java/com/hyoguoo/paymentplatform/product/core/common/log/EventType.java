package com.hyoguoo.paymentplatform.product.core.common.log;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventType {

    STOCK_SNAPSHOT_PUBLISH,

    STOCK_COMMIT_RECEIVED,
    STOCK_COMMIT_DUPLICATE,
    STOCK_COMMIT_RDB_DONE,
    STOCK_COMMIT_REDIS_DONE,

    STOCK_RESTORE_RECEIVED,
    STOCK_RESTORE_DUPLICATE,
    STOCK_RESTORE_RDB_DONE,
    STOCK_RESTORE_DONE,

    STOCK_REDIS_SET,

    EXCEPTION,
}
