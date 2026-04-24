package com.hyoguoo.paymentplatform.gateway.core.common.log;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventType {

    TRACE_CONTEXT_INJECTED,
    TRACE_CONTEXT_MALFORMED,

    EXCEPTION,
}
