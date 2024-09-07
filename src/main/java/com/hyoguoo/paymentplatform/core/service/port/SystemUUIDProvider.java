package com.hyoguoo.paymentplatform.core.service.port;

import java.util.UUID;
import org.springframework.stereotype.Component;
import com.hyoguoo.paymentplatform.core.infrastructure.UUIDProvider;

@Component
public class SystemUUIDProvider implements UUIDProvider {

    @Override
    public String generateUUID() {
        return UUID.randomUUID().toString();
    }
}
