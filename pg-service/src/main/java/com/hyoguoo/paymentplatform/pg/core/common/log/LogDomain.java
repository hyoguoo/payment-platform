package com.hyoguoo.paymentplatform.pg.core.common.log;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LogDomain {
    GLOBAL,
    PG,
    PG_VENDOR,
    PG_OUTBOX
}
