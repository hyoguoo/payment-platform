package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.util.AmountConverter;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * pg-service DLQ 전용 처리 서비스.
 * PaymentConfirmDlqConsumer로부터 위임받아 재시도 소진 건을 {@link PgFinalConfirmationGate}
 * 로 넘긴다 — 격리 직행 대신 벤더에 최종 상태를 1회 조회해 자동 승인/자동 실패/격리로 갈린다.
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>pg_inbox 비잠금 조회 — 원자성은 이 서비스가 아니라 관문 반영 단계의 조건부 전이가 맡는다.</li>
 *   <li>이미 terminal(APPROVED/FAILED/QUARANTINED)이면 no-op.</li>
 *   <li>그렇지 않으면 {@link PgFinalConfirmationGate#invokeVendor}(TX 밖, 벤더 조회 1회) →
 *       {@link PgFinalConfirmationGate#applyOutcome}(TX 안, pg_inbox 전이 + pg_outbox INSERT
 *       + 이벤트 발행) 순서로 위임한다.</li>
 * </ol>
 *
 * <p>이 서비스 자체는 TX 경계를 열지 않는다 — 벤더 HTTP 호출을 감싸는 트랜잭션을 두지 않기 위해서다.
 * TX 경계는 관문의 두 메서드 안에만 있다.
 *
 * <p>DLQ consumer 자체 실패 시 offset 미커밋 → 재기동 후 재처리.
 * 같은 orderId 로 다시 들어와도 관문의 CAS 전이가 중복 진입을 흡수한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgDlqService {

    private final PgInboxRepository pgInboxRepository;
    private final PgFinalConfirmationGate pgFinalConfirmationGate;

    /**
     * DLQ 메시지를 처리한다.
     * 비잠금 조회로 종결 여부만 사전 확인하고, 종결 전이면 관문에 위임한다.
     *
     * @param command DLQ에서 수신한 PG 확정 커맨드
     */
    public void handle(PgConfirmCommand command) {
        String orderId = command.orderId();

        PgInbox inbox = pgInboxRepository.findByOrderId(orderId).orElse(null);
        if (inbox == null) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_DLQ_NO_INBOX,
                    () -> "orderId=" + orderId);
            return;
        }

        if (inbox.getStatus().isTerminal()) {
            LogFmt.info(log, LogDomain.PG, EventType.PG_DLQ_ALREADY_TERMINAL,
                    () -> "orderId=" + orderId + " status=" + inbox.getStatus());
            return;
        }

        long amount = AmountConverter.fromBigDecimalStrict(command.amount());
        PgFinalConfirmationGate.FcgOutcome outcome = pgFinalConfirmationGate.invokeVendor(
                orderId, command.eventUuid(), amount, command.vendorType());
        pgFinalConfirmationGate.applyOutcome(outcome, orderId, amount);
    }
}
