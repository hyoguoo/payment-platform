package com.hyoguoo.paymentplatform.pg.presentation.port;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;

/**
 * pg-service inbound 포트 — presentation → application 계층 계약.
 * ADR-21: payment.commands.confirm 메시지를 수신하여 PG 승인 처리를 트리거한다.
 * 구현체(PgConfirmCommandServiceImpl 등)는 T2a-06에서 추가.
 */
public interface PgConfirmCommandService {

    void handle(PgConfirmCommand command);
}
