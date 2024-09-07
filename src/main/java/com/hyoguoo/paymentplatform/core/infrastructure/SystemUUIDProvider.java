package com.hyoguoo.paymentplatform.core.infrastructure;

import com.hyoguoo.paymentplatform.core.service.port.UUIDProvider;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SystemUUIDProvider implements UUIDProvider {

    @Override
    public String generateUUID() {
        return UUID.randomUUID().toString();
    }
}
