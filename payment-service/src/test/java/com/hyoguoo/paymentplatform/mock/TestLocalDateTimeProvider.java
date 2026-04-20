package com.hyoguoo.paymentplatform.mock;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import java.time.LocalDateTime;

public class TestLocalDateTimeProvider implements LocalDateTimeProvider {

    private LocalDateTime fixedDateTime;

    public TestLocalDateTimeProvider() {
        this.fixedDateTime = LocalDateTime.now();
    }

    @Override
    public LocalDateTime now() {
        return fixedDateTime;
    }

    @SuppressWarnings("unused")
    public void setFixedDateTime(LocalDateTime fixedDateTime) {
        this.fixedDateTime = fixedDateTime;
    }
}
