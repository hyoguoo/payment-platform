package com.hyoguoo.paymentplatform.core.common.infrastructure;

import com.hyoguoo.paymentplatform.core.common.service.port.UUIDProvider;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SystemUUIDProvider implements UUIDProvider {

    @Override
    public String generateUUID() {
        return UUID.randomUUID().toString();
    }

    @Override
    public String generateShortUUID() {
        return this.generateUUID().substring(0, 8);
    }
}
