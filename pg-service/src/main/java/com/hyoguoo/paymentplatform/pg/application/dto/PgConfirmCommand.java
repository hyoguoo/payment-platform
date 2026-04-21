package com.hyoguoo.paymentplatform.pg.application.dto;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import java.math.BigDecimal;

/**
 * PgConfirmCommandService inbound 커맨드 DTO.
 * payment.commands.confirm Kafka 메시지에서 역직렬화된 데이터를 담는다.
 */
public record PgConfirmCommand(
        String orderId,
        String paymentKey,
        BigDecimal amount,
        PgVendorType vendorType,
        String eventUuid
) {

}
