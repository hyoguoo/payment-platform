package com.hyoguoo.paymentplatform.pg.presentation;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyoguoo.paymentplatform.pg.application.dto.PgVendorStatusJudgement;
import com.hyoguoo.paymentplatform.pg.application.dto.PgVendorStatusView;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgAttemptHistoryQueryService;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgVendorStatusQueryService;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PgAttemptHistoryController.class)
class PgVendorStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PgAttemptHistoryQueryService pgAttemptHistoryQueryService;

    @MockitoBean
    private PgVendorStatusQueryService pgVendorStatusQueryService;

    @Test
    @DisplayName("벤더 상태를 조회하면 판정·원 상태·조회 시각을 담은 응답을 반환한다.")
    void 벤더_상태_조회_정상_응답() throws Exception {
        // given
        Instant queriedAt = Instant.parse("2026-08-06T00:00:00Z");
        PgVendorStatusView view = PgVendorStatusView.of(PgVendorStatusJudgement.APPROVED, "DONE", queriedAt);
        given(pgVendorStatusQueryService.getVendorStatus("order-1")).willReturn(view);

        // when / then
        mockMvc.perform(get("/api/v1/confirmations/{orderId}/vendor-status", "order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.judgement").value("APPROVED"))
                .andExpect(jsonPath("$.vendorStatus").value("DONE"))
                .andExpect(jsonPath("$.queriedAt").value("2026-08-06T00:00:00Z"));
    }

    @Test
    @DisplayName("조회 결과가 확인불가여도 5xx 가 아니라 200 으로 응답한다 — 벤더 조회 실패가 이 값으로 흡수된다.")
    void 벤더_상태_확인불가도_200으로_응답() throws Exception {
        // given
        Instant queriedAt = Instant.parse("2026-08-06T00:00:00Z");
        given(pgVendorStatusQueryService.getVendorStatus("order-unknown"))
                .willReturn(PgVendorStatusView.unknown(queriedAt));

        // when / then
        mockMvc.perform(get("/api/v1/confirmations/{orderId}/vendor-status", "order-unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.judgement").value("UNKNOWN"))
                .andExpect(jsonPath("$.vendorStatus").doesNotExist());
    }
}
