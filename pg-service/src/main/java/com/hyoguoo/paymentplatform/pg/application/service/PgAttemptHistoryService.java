package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgAttemptHistory;
import com.hyoguoo.paymentplatform.pg.application.dto.PgAttemptHistoryEntry;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgAttemptHistoryQueryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * pg-service 관리자 화면용 시도 이력 조립 서비스.
 *
 * <p>pg_inbox 의 최초 수신 시각·최종 상태·종결 시각과, {@link PgOutboxRepository#findConfirmAttemptRows}
 * 로 얻은 확정 시도 이력 행(재시도/소진 토픽만, 결과 발행 토픽 제외)을 시각 순서로 조립해
 * 회차별 타임라인을 만든다.
 *
 * <p><b>미실행 판정</b> — 결제가 종결된 경우에만 판정하며, 종결되지 않았으면 비교 대상 시각이
 * 없으므로 판정을 건너뛰고 항상 정상 시도로 취급한다(비종결 결제에 대해 기본값을 종결로 잘못
 * 잡으면 진행 중인 정상 재시도가 미실행으로 표시된다). 종결된 경우에는 발행 시각이 있으면
 * 그것을, 없으면 실행 예정 시각(백오프 반영 예정 시점)을 종결 시각과 비교한다 — 예정 시각만
 * 비교하면 "예정은 종결 전이었으나 발행이 밀려 종결 이후에 나간 행"을 놓친다.
 *
 * <p>회차는 outbox row 의 headers_json 에서 읽는다. 헤더가 없거나 파싱에 실패하면 회차 미지로
 * 처리하고 예외를 던지지 않는다 — 옛 행이 섞여도 조회 자체는 깨지지 않아야 한다.
 *
 * <p>응답 DTO 어디에도 payload / headers_json / payment_key 를 담지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PgAttemptHistoryService implements PgAttemptHistoryQueryService {

    private final PgInboxRepository pgInboxRepository;
    private final PgOutboxRepository pgOutboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * 주문번호 기준 시도 이력을 조립한다.
     * pg-service 에 해당 주문이 없으면 이력 없음({@link PgAttemptHistory#notFound})을 반환한다.
     * 조회 자체의 실패(DB 장애 등)는 여기서 흡수하지 않고 그대로 전파한다 — 이력 없음과 구분된다.
     *
     * @param orderId 주문 ID
     * @return 조립된 시도 이력
     */
    @Override
    public PgAttemptHistory getAttemptHistory(String orderId) {
        return pgInboxRepository.findByOrderId(orderId)
                .map(inbox -> assemble(orderId, inbox))
                .orElseGet(() -> PgAttemptHistory.notFound(orderId));
    }

    private PgAttemptHistory assemble(String orderId, PgInbox inbox) {
        boolean terminal = inbox.getStatus().isTerminal();
        PgInboxStatus finalStatus = terminal ? inbox.getStatus() : null;
        Instant finalizedAt = terminal ? inbox.getUpdatedAt() : null;

        List<PgAttemptHistoryEntry> attempts = new ArrayList<>();
        attempts.add(PgAttemptHistoryEntry.initial(inbox.getCreatedAt()));
        for (PgOutbox row : pgOutboxRepository.findConfirmAttemptRows(orderId)) {
            attempts.add(toEntry(row, finalizedAt));
        }

        return new PgAttemptHistory(orderId, true, finalStatus, finalizedAt, inbox.getReasonCode(), attempts);
    }

    private PgAttemptHistoryEntry toEntry(PgOutbox row, Instant finalizedAt) {
        boolean exhausted = PgTopics.COMMANDS_CONFIRM_DLQ.equals(row.getTopic());
        boolean normalAttempt = isNormalAttempt(row, finalizedAt);
        return new PgAttemptHistoryEntry(
                parseAttemptNo(row.getHeadersJson()),
                row.getCreatedAt(),
                row.getAvailableAt(),
                row.getProcessedAt(),
                exhausted,
                normalAttempt);
    }

    /**
     * 발행 시각이 있으면 그것을, 없으면 실행 예정 시각을 종결 시각과 비교한다.
     * 결제가 비종결이면(finalizedAt=null) 비교 대상이 없어 판정을 건너뛰고 정상 시도로 취급한다.
     */
    private boolean isNormalAttempt(PgOutbox row, Instant finalizedAt) {
        if (finalizedAt == null) {
            return true;
        }
        Instant effectiveTime = row.getProcessedAt() != null ? row.getProcessedAt() : row.getAvailableAt();
        return !effectiveTime.isAfter(finalizedAt);
    }

    private Optional<Integer> parseAttemptNo(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Optional.empty();
        }
        return readAttemptField(headersJson);
    }

    private Optional<Integer> readAttemptField(String headersJson) {
        try {
            JsonNode root = objectMapper.readTree(headersJson);
            JsonNode attemptNode = root.get("attempt");
            if (attemptNode == null || !attemptNode.isInt()) {
                return Optional.empty();
            }
            return Optional.of(attemptNode.asInt());
        } catch (JsonProcessingException e) {
            // 헤더 파싱 실패 — 회차 미지로 처리하고 조회 자체는 계속 진행한다.
            return Optional.empty();
        }
    }
}
