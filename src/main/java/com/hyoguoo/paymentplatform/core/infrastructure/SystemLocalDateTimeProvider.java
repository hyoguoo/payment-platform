package com.hyoguoo.paymentplatform.core.infrastructure;

import com.hyoguoo.paymentplatform.core.service.port.LocalDateTimeProvider;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class SystemLocalDateTimeProvider implements LocalDateTimeProvider {

    @Override
    public LocalDateTime now() {
        return LocalDateTime.now();
    }
}
