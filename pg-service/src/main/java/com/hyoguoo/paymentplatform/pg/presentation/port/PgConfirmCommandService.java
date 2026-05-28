package com.hyoguoo.paymentplatform.pg.presentation.port;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;

/**
 * pg-service inbound 포트 — presentation → application 계층 계약.
 * payment.commands.confirm 메시지를 수신하여 PG 승인 처리를 트리거한다.
 * 구현체는 application 계층의 {@code PgConfirmService}.
 *
 * <p>storedTraceparent 는 불투명 {@code String} 토큰으로만 통과한다.
 * OTel API import 는 이 인터페이스(application/presentation 계층)에 존재하지 않는다.
 * 추출은 consumer(infrastructure) 레이어의 {@code PaymentConfirmConsumer} 가 수행한다.
 */
public interface PgConfirmCommandService {

    /**
     * attempt + storedTraceparent 를 포함한 confirm 처리 (메인 시그니처).
     * self-loop retry 시 attempt >= 2, 최초 진입 시 attempt=1.
     * storedTraceparent 는 W3C traceparent 불투명 문자열 (null 허용 — 헤더 부재·폴백).
     *
     * @param command           PG 승인 커맨드
     * @param attempt           시도 횟수 (1-based, 최초=1)
     * @param storedTraceparent W3C traceparent 불투명 문자열 (null 허용)
     */
    void handle(PgConfirmCommand command, int attempt, String storedTraceparent);

    /**
     * attempt=1, storedTraceparent=null 기본값 위임 (하위 호환).
     */
    default void handle(PgConfirmCommand command, int attempt) {
        handle(command, attempt, null);
    }

    /**
     * attempt=1, storedTraceparent=null 기본값 위임 (하위 호환).
     */
    default void handle(PgConfirmCommand command) {
        handle(command, 1, null);
    }
}
