package com.hyoguoo.paymentplatform.mock;

import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class TestLocalDateTimeProvider implements LocalDateTimeProvider {

    private LocalDateTime fixedDateTime;
    private Instant fixedInstant;

    public TestLocalDateTimeProvider() {
        this.fixedDateTime = LocalDateTime.now();
        this.fixedInstant = null;
    }

    @Override
    public LocalDateTime now() {
        return fixedDateTime;
    }

    @Override
    public Instant nowInstant() {
        if (fixedInstant != null) {
            return fixedInstant;
        }
        return fixedDateTime.toInstant(ZoneOffset.UTC);
    }

    @SuppressWarnings("unused")
    public void setFixedDateTime(LocalDateTime fixedDateTime) {
        this.fixedDateTime = fixedDateTime;
        this.fixedInstant = fixedDateTime.toInstant(ZoneOffset.UTC);
    }

    @SuppressWarnings("unused")
    public void setFixedInstant(Instant fixedInstant) {
        this.fixedInstant = fixedInstant;
        this.fixedDateTime = LocalDateTime.ofInstant(fixedInstant, ZoneOffset.UTC);
    }
}
