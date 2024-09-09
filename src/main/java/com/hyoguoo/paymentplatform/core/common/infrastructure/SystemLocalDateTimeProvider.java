package com.hyoguoo.paymentplatform.core.common.infrastructure;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class SystemLocalDateTimeProvider implements LocalDateTimeProvider {

    @Override
    public LocalDateTime now() {
        return LocalDateTime.now();
    }
}
