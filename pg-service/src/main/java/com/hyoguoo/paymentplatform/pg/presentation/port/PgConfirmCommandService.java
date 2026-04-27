package com.hyoguoo.paymentplatform.pg.presentation.port;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;

/**
 * pg-service inbound 포트 — presentation → application 계층 계약.
 * payment.commands.confirm 메시지를 수신하여 PG 승인 처리를 트리거한다.
 * 구현체는 application 계층의 {@code PgConfirmService}.
 */
public interface PgConfirmCommandService {

    /**
     * attempt 헤더를 포함한 confirm 처리 (메인 시그니처).
     * self-loop retry 시 attempt >= 2, 최초 진입 시 attempt=1.
     *
     * @param command PG 승인 커맨드
     * @param attempt 시도 횟수 (1-based, 최초=1)
     */
    void handle(PgConfirmCommand command, int attempt);

    /**
     * attempt=1 기본값 위임 (하위 호환).
     */
    default void handle(PgConfirmCommand command) {
        handle(command, 1);
    }
}
