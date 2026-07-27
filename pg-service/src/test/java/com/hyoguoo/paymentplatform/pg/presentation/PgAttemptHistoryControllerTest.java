package com.hyoguoo.paymentplatform.pg.presentation;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyoguoo.paymentplatform.pg.application.dto.PgAttemptHistory;
import com.hyoguoo.paymentplatform.pg.application.dto.PgAttemptHistoryEntry;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgAttemptHistoryQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PgAttemptHistoryController.class)
class PgAttemptHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PgAttemptHistoryQueryService pgAttemptHistoryQueryService;

    @Test
    @DisplayName("이력이 있는 주문을 조회하면 회차·시각·정상시도 여부를 포함한 고정된 응답 형태를 반환한다.")
    void 조회_정상_응답_형태_고정() throws Exception {
        // given
        Instant receivedAt = Instant.parse("2026-07-28T00:00:00Z");
        Instant reservedAt = Instant.parse("2026-07-28T00:01:00Z");
        Instant scheduledAt = Instant.parse("2026-07-28T00:01:30Z");
        Instant publishedAt = Instant.parse("2026-07-28T00:01:35Z");
        Instant finalizedAt = Instant.parse("2026-07-28T00:02:00Z");

        PgAttemptHistoryEntry initial = PgAttemptHistoryEntry.initial(receivedAt);
        PgAttemptHistoryEntry retry = new PgAttemptHistoryEntry(
                java.util.Optional.of(2), reservedAt, scheduledAt, publishedAt, false, true);

        PgAttemptHistory history = new PgAttemptHistory(
                "order-1", true, PgInboxStatus.APPROVED, finalizedAt, null, List.of(initial, retry));

        given(pgAttemptHistoryQueryService.getAttemptHistory("order-1")).willReturn(history);

        // when / then
        mockMvc.perform(get("/api/v1/confirmations/{orderId}/attempts", "order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.finalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.finalizedAt").value("2026-07-28T00:02:00Z"))
                .andExpect(jsonPath("$.reasonCode").doesNotExist())
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].attemptNo").value(1))
                .andExpect(jsonPath("$.attempts[0].reservedAt").value("2026-07-28T00:00:00Z"))
                .andExpect(jsonPath("$.attempts[0].scheduledAt").doesNotExist())
                .andExpect(jsonPath("$.attempts[0].publishedAt").doesNotExist())
                .andExpect(jsonPath("$.attempts[0].exhausted").value(false))
                .andExpect(jsonPath("$.attempts[0].normalAttempt").value(true))
                .andExpect(jsonPath("$.attempts[1].attemptNo").value(2))
                .andExpect(jsonPath("$.attempts[1].reservedAt").value("2026-07-28T00:01:00Z"))
                .andExpect(jsonPath("$.attempts[1].scheduledAt").value("2026-07-28T00:01:30Z"))
                .andExpect(jsonPath("$.attempts[1].publishedAt").value("2026-07-28T00:01:35Z"));
    }

    @Test
    @DisplayName("pg-service 에 없는 주문번호를 조회하면 404 가 아니라 이력 없음을 담은 200 응답을 반환한다.")
    void 조회_이력없는_주문_이력없음_응답() throws Exception {
        // given
        given(pgAttemptHistoryQueryService.getAttemptHistory("order-unknown"))
                .willReturn(PgAttemptHistory.notFound("order-unknown"));

        // when / then
        mockMvc.perform(get("/api/v1/confirmations/{orderId}/attempts", "order-unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order-unknown"))
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.finalStatus").doesNotExist())
                .andExpect(jsonPath("$.finalizedAt").doesNotExist())
                .andExpect(jsonPath("$.attempts.length()").value(0));
    }

    @Test
    @DisplayName("응답 본문에는 결제 키·원문 컬럼(payload/headersJson/paymentKey)이 담기지 않는다.")
    void 조회_응답에_결제키_미포함() throws Exception {
        // given
        PgAttemptHistoryEntry initial = PgAttemptHistoryEntry.initial(Instant.parse("2026-07-28T00:00:00Z"));
        PgAttemptHistory history = new PgAttemptHistory(
                "order-2", true, null, null, null, List.of(initial));

        given(pgAttemptHistoryQueryService.getAttemptHistory("order-2")).willReturn(history);

        // when / then
        mockMvc.perform(get("/api/v1/confirmations/{orderId}/attempts", "order-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentKey").doesNotExist())
                .andExpect(jsonPath("$.payload").doesNotExist())
                .andExpect(jsonPath("$.headersJson").doesNotExist())
                .andExpect(jsonPath("$.attempts[0].paymentKey").doesNotExist())
                .andExpect(jsonPath("$.attempts[0].payload").doesNotExist())
                .andExpect(jsonPath("$.attempts[0].headersJson").doesNotExist());
    }
}
