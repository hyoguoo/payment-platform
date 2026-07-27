package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgAttemptHistory;
import com.hyoguoo.paymentplatform.pg.application.dto.PgAttemptHistoryEntry;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * PgAttemptHistoryService 단위 테스트 — 시도 이력 조립의 정확성을 검증한다.
 * domain_risk=true: 화면이 말하는 사실의 정확성이 전부 여기서 결정된다.
 */
@DisplayName("PgAttemptHistoryService")
class PgAttemptHistoryServiceTest {

    private static final String ORDER_ID = "order-attempt-history-001";

    private PgInboxRepository pgInboxRepository;
    private PgOutboxRepository pgOutboxRepository;
    private PgAttemptHistoryService sut;

    @BeforeEach
    void setUp() {
        pgInboxRepository = mock(PgInboxRepository.class);
        pgOutboxRepository = mock(PgOutboxRepository.class);
        sut = new PgAttemptHistoryService(pgInboxRepository, pgOutboxRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("조립 — 최초 수신만 있으면 1회차 한 건으로 조립된다")
    void 조립_최초_수신만_있으면_1회차_한_건() {
        // given
        Instant receivedAt = Instant.parse("2026-07-01T00:00:00Z");
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.IN_PROGRESS, 10_000L,
                null, null, receivedAt, receivedAt);
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inbox));
        given(pgOutboxRepository.findConfirmAttemptRows(ORDER_ID)).willReturn(List.of());

        // when
        PgAttemptHistory result = sut.getAttemptHistory(ORDER_ID);

        // then
        assertThat(result.found()).isTrue();
        assertThat(result.attempts()).hasSize(1);
        PgAttemptHistoryEntry first = result.attempts().get(0);
        assertThat(first.attemptNo()).contains(1);
        assertThat(first.reservedAt()).isEqualTo(receivedAt);
        assertThat(first.normalAttempt()).isTrue();
        assertThat(first.exhausted()).isFalse();
    }

    @Test
    @DisplayName("조립 — 재시도 진행 중(미발행) 행이 실행 예정 상태로 포함된다")
    void 조립_재시도_진행중_예정_상태_포함() {
        // given
        Instant receivedAt = Instant.parse("2026-07-01T00:00:00Z");
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.IN_PROGRESS, 10_000L,
                null, null, receivedAt, receivedAt);
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inbox));

        PgOutbox pendingRetry = PgOutbox.of(1L, PgTopics.COMMANDS_CONFIRM, ORDER_ID,
                "{}", "{\"attempt\":2}",
                receivedAt.plusSeconds(10), null, 0, receivedAt.plusSeconds(1));
        given(pgOutboxRepository.findConfirmAttemptRows(ORDER_ID)).willReturn(List.of(pendingRetry));

        // when
        PgAttemptHistory result = sut.getAttemptHistory(ORDER_ID);

        // then
        assertThat(result.attempts()).hasSize(2);
        PgAttemptHistoryEntry retryEntry = result.attempts().get(1);
        assertThat(retryEntry.attemptNo()).contains(2);
        assertThat(retryEntry.publishedAt()).isNull();
        assertThat(retryEntry.scheduledAt()).isEqualTo(receivedAt.plusSeconds(10));
        assertThat(retryEntry.normalAttempt()).isTrue();
    }

    @Test
    @DisplayName("조립 — 소진(DLQ) 토픽 행은 소진으로 표시된다")
    void 조립_소진_후_격리_소진_표시() {
        // given
        Instant receivedAt = Instant.parse("2026-07-01T00:00:00Z");
        Instant finalizedAt = receivedAt.plusSeconds(100);
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.QUARANTINED, 10_000L,
                null, "RETRY_EXHAUSTED", receivedAt, finalizedAt);
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inbox));

        PgOutbox dlqRow = PgOutbox.of(1L, PgTopics.COMMANDS_CONFIRM_DLQ, ORDER_ID,
                "{}", "{\"attempt\":4}",
                receivedAt.plusSeconds(50), receivedAt.plusSeconds(60), 0, receivedAt.plusSeconds(50));
        given(pgOutboxRepository.findConfirmAttemptRows(ORDER_ID)).willReturn(List.of(dlqRow));

        // when
        PgAttemptHistory result = sut.getAttemptHistory(ORDER_ID);

        // then
        PgAttemptHistoryEntry dlqEntry = result.attempts().get(1);
        assertThat(dlqEntry.exhausted()).isTrue();
        assertThat(result.finalStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
    }

    @Test
    @DisplayName("조립 — 회차 정보가 없는 행은 미지로 표시되고 조회가 실패하지 않는다")
    void 조립_회차_정보_없는_행_미지로_표시() {
        // given
        Instant receivedAt = Instant.parse("2026-07-01T00:00:00Z");
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.IN_PROGRESS, 10_000L,
                null, null, receivedAt, receivedAt);
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inbox));

        PgOutbox headerlessRow = PgOutbox.of(1L, PgTopics.COMMANDS_CONFIRM, ORDER_ID,
                "{}", null,
                receivedAt.plusSeconds(10), receivedAt.plusSeconds(11), 0, receivedAt.plusSeconds(1));
        given(pgOutboxRepository.findConfirmAttemptRows(ORDER_ID)).willReturn(List.of(headerlessRow));

        // when
        PgAttemptHistory result = sut.getAttemptHistory(ORDER_ID);

        // then
        assertThat(result.attempts()).hasSize(2);
        assertThat(result.attempts().get(1).attemptNo()).isEmpty();
    }

    @Test
    @DisplayName("조립 — 이력 없음과 조회 실패는 다른 상태로 구분된다")
    void 조립_이력_없음과_조회실패_구분() {
        // given — 이력 없음
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

        // when
        PgAttemptHistory notFound = sut.getAttemptHistory(ORDER_ID);

        // then — 이력 없음은 예외 없이 found=false
        assertThat(notFound.found()).isFalse();
        assertThat(notFound.attempts()).isEmpty();

        // given — 조회 실패(DB 장애 등)
        String otherOrderId = "order-query-failure";
        given(pgInboxRepository.findByOrderId(otherOrderId))
                .willThrow(new IllegalStateException("DB 연결 실패"));

        // when + then — 조회 실패는 예외로 전파되며 이력없음으로 흡수되지 않는다
        assertThatThrownBy(() -> sut.getAttemptHistory(otherOrderId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("조립 — 발행 시각이 종결 시각보다 늦으면 미실행으로 분류된다")
    void 조립_발행시각이_종결시각보다_늦으면_미실행으로_분류() {
        // given
        Instant receivedAt = Instant.parse("2026-07-01T00:00:00Z");
        Instant finalizedAt = receivedAt.plusSeconds(30);
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.APPROVED, 10_000L,
                null, null, receivedAt, finalizedAt);
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inbox));

        // 좀비 회수가 앞지른 경우 — 발행이 종결 이후로 밀림
        PgOutbox lateRow = PgOutbox.of(1L, PgTopics.COMMANDS_CONFIRM, ORDER_ID,
                "{}", "{\"attempt\":2}",
                receivedAt.plusSeconds(5), finalizedAt.plusSeconds(10), 0, receivedAt.plusSeconds(1));
        given(pgOutboxRepository.findConfirmAttemptRows(ORDER_ID)).willReturn(List.of(lateRow));

        // when
        PgAttemptHistory result = sut.getAttemptHistory(ORDER_ID);

        // then
        assertThat(result.attempts().get(1).normalAttempt()).isFalse();
    }

    @Test
    @DisplayName("조립 — 예정은 종결 전이었으나 발행이 종결 후면 미실행으로 분류된다")
    void 조립_예정은_종결전_발행은_종결후면_미실행으로_분류() {
        // given
        Instant receivedAt = Instant.parse("2026-07-01T00:00:00Z");
        Instant finalizedAt = receivedAt.plusSeconds(30);
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.APPROVED, 10_000L,
                null, null, receivedAt, finalizedAt);
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inbox));

        // 실행 예정 시각(available_at)은 종결 전이었으나, 실제 발행이 밀려 종결 이후에 나간 좁은 창
        PgOutbox delayedPublishRow = PgOutbox.of(1L, PgTopics.COMMANDS_CONFIRM, ORDER_ID,
                "{}", "{\"attempt\":2}",
                finalizedAt.minusSeconds(5), finalizedAt.plusSeconds(20), 0, receivedAt.plusSeconds(1));
        given(pgOutboxRepository.findConfirmAttemptRows(ORDER_ID)).willReturn(List.of(delayedPublishRow));

        // when
        PgAttemptHistory result = sut.getAttemptHistory(ORDER_ID);

        // then — 발행 시각 기준으로 판정해야 이 좁은 창이 막힌다
        assertThat(result.attempts().get(1).normalAttempt()).isFalse();
    }

    @Test
    @DisplayName("조립 — 발행 시각이 없으면 실행 예정 시각으로 판정한다")
    void 조립_발행시각_없으면_실행예정시각으로_판정() {
        // given
        Instant receivedAt = Instant.parse("2026-07-01T00:00:00Z");
        Instant finalizedAt = receivedAt.plusSeconds(30);
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.APPROVED, 10_000L,
                null, null, receivedAt, finalizedAt);
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inbox));

        // 아직 미발행 — 실행 예정 시각이 종결 이후라 폴백 경로로 미실행 판정
        PgOutbox unpublishedRow = PgOutbox.of(1L, PgTopics.COMMANDS_CONFIRM, ORDER_ID,
                "{}", "{\"attempt\":2}",
                finalizedAt.plusSeconds(10), null, 0, receivedAt.plusSeconds(1));
        given(pgOutboxRepository.findConfirmAttemptRows(ORDER_ID)).willReturn(List.of(unpublishedRow));

        // when
        PgAttemptHistory result = sut.getAttemptHistory(ORDER_ID);

        // then
        assertThat(result.attempts().get(1).normalAttempt()).isFalse();
    }

    @Test
    @DisplayName("조립 — 종결 시각 이전 행은 정상 시도로 분류된다")
    void 조립_종결시각_이전_행은_정상시도로_분류() {
        // given
        Instant receivedAt = Instant.parse("2026-07-01T00:00:00Z");
        Instant finalizedAt = receivedAt.plusSeconds(30);
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.APPROVED, 10_000L,
                null, null, receivedAt, finalizedAt);
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inbox));

        PgOutbox normalRow = PgOutbox.of(1L, PgTopics.COMMANDS_CONFIRM, ORDER_ID,
                "{}", "{\"attempt\":2}",
                receivedAt.plusSeconds(5), finalizedAt.minusSeconds(5), 0, receivedAt.plusSeconds(1));
        given(pgOutboxRepository.findConfirmAttemptRows(ORDER_ID)).willReturn(List.of(normalRow));

        // when
        PgAttemptHistory result = sut.getAttemptHistory(ORDER_ID);

        // then
        assertThat(result.attempts().get(1).normalAttempt()).isTrue();
    }

    @Test
    @DisplayName("조립 — 진행 중 결제는 종결 시각이 없어 미실행 판정을 건너뛴다")
    void 조립_진행중_결제는_종결시각_없어_미실행_판정_스킵() {
        // given
        Instant receivedAt = Instant.parse("2026-07-01T00:00:00Z");
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.IN_PROGRESS, 10_000L,
                null, null, receivedAt, receivedAt);
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inbox));

        // 기본값을 잘못 잡으면(예: finalizedAt=now) 이 정상 재시도가 미실행으로 표시된다
        PgOutbox futureRow = PgOutbox.of(1L, PgTopics.COMMANDS_CONFIRM, ORDER_ID,
                "{}", "{\"attempt\":2}",
                receivedAt.plusSeconds(999_999), null, 0, receivedAt.plusSeconds(1));
        given(pgOutboxRepository.findConfirmAttemptRows(ORDER_ID)).willReturn(List.of(futureRow));

        // when
        PgAttemptHistory result = sut.getAttemptHistory(ORDER_ID);

        // then
        assertThat(result.finalStatus()).isNull();
        assertThat(result.finalizedAt()).isNull();
        assertThat(result.attempts().get(1).normalAttempt()).isTrue();
    }

    @Test
    @DisplayName("조립 — 세 시각(예약/실행예정/발행)의 의미가 회차와 어긋나지 않는다")
    void 조립_세_시각_의미_고정() {
        // given
        Instant receivedAt = Instant.parse("2026-07-01T00:00:00Z");
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.IN_PROGRESS, 10_000L,
                null, null, receivedAt, receivedAt);
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inbox));

        Instant reservedAt = receivedAt.plusSeconds(1);
        Instant scheduledAt = receivedAt.plusSeconds(10);
        Instant publishedAt = receivedAt.plusSeconds(11);
        PgOutbox row = PgOutbox.of(1L, PgTopics.COMMANDS_CONFIRM, ORDER_ID,
                "{}", "{\"attempt\":3}",
                scheduledAt, publishedAt, 0, reservedAt);
        given(pgOutboxRepository.findConfirmAttemptRows(ORDER_ID)).willReturn(List.of(row));

        // when
        PgAttemptHistory result = sut.getAttemptHistory(ORDER_ID);

        // then
        PgAttemptHistoryEntry entry = result.attempts().get(1);
        assertThat(entry.attemptNo()).contains(3);
        assertThat(entry.reservedAt()).isEqualTo(reservedAt);
        assertThat(entry.scheduledAt()).isEqualTo(scheduledAt);
        assertThat(entry.publishedAt()).isEqualTo(publishedAt);
    }

    @Test
    @DisplayName("조립 — 응답에 결제 키와 원문 컬럼이 담기지 않는다")
    void 조립_응답에_결제키_미포함() {
        // given
        Instant receivedAt = Instant.parse("2026-07-01T00:00:00Z");
        String secretPaymentKey = "SECRET-PAYMENT-KEY-9912";
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.IN_PROGRESS, 10_000L,
                null, null, receivedAt, receivedAt, secretPaymentKey, "TOSS");
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inbox));

        String secretPayload = "{\"paymentKey\":\"" + secretPaymentKey + "\"}";
        PgOutbox row = PgOutbox.of(1L, PgTopics.COMMANDS_CONFIRM, ORDER_ID,
                secretPayload, "{\"attempt\":2}",
                receivedAt.plusSeconds(10), receivedAt.plusSeconds(11), 0, receivedAt.plusSeconds(1));
        given(pgOutboxRepository.findConfirmAttemptRows(ORDER_ID)).willReturn(List.of(row));

        // when
        PgAttemptHistory result = sut.getAttemptHistory(ORDER_ID);

        // then — record 컴포넌트 이름에 원문/결제키 필드가 존재하지 않는다
        assertRecordHasNoField(PgAttemptHistory.class, "payload", "headersJson", "paymentKey");
        assertRecordHasNoField(PgAttemptHistoryEntry.class, "payload", "headersJson", "paymentKey");

        // then — 직렬화 표현(toString)에도 원문 값이 노출되지 않는다
        assertThat(result.toString()).doesNotContain(secretPaymentKey);
        assertThat(result.toString()).doesNotContain(secretPayload);
    }

    private void assertRecordHasNoField(Class<?> recordClass, String... forbiddenNames) {
        List<String> componentNames = Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        for (String forbidden : forbiddenNames) {
            assertThat(componentNames).doesNotContain(forbidden);
        }
    }
}
