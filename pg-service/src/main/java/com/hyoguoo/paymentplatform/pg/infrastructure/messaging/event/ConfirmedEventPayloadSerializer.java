package com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * payment.events.confirmed 토픽 payload 를 record → JSON 문자열로 변환한다.
 * 수동 문자열 concat 을 금지하고 ConfirmedEventPayload 스키마 변경 시 빌더 단일 지점에서 반영되도록 강제한다.
 *
 * <p>직렬화 실패는 well-formed record 입력에서는 발생하지 않는 불변 위반이므로
 * IllegalStateException 으로 상위에 알려 TX 롤백을 유도한다.
 */
@Component
@RequiredArgsConstructor
public class ConfirmedEventPayloadSerializer {

    private final ObjectMapper objectMapper;

    public String serialize(ConfirmedEventPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ConfirmedEventPayload 직렬화 실패", e);
        }
    }
}
