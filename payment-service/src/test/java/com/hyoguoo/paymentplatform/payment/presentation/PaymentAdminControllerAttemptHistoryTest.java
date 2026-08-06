package com.hyoguoo.paymentplatform.payment.presentation;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptEntryInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryLookupResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.presentation.port.AdminPaymentService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentRecoveryAdminService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PgAttemptHistoryViewService;
import com.hyoguoo.paymentplatform.payment.presentation.port.PgVendorStatusViewService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code PaymentAdminController} 의 시도 이력 카드 렌더링 테스트.
 *
 * <p>조회 실패 흡수와 조회 불가 폴백 판단은 {@link PgAttemptHistoryViewService}(입력 포트)
 * 구현으로 옮겨갔다 — 이 테스트는 그 결과({@link PgAttemptHistoryLookupResult})를 컨트롤러가
 * 모델에 올바르게 담는지만 검증한다. 예외 타입별(도메인 예외 vs 타임아웃) 흡수 로직 검증은
 * {@code PgAttemptHistoryViewServiceImplTest} 가 담당한다.
 */
@WebMvcTest(PaymentAdminController.class)
class PaymentAdminControllerAttemptHistoryTest {

    private static final Long EVENT_ID = 1L;
    private static final String ORDER_ID = "order-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminPaymentService adminPaymentService;

    @MockitoBean
    private PaymentRecoveryAdminService paymentRecoveryAdminService;

    @MockitoBean
    private PgAttemptHistoryViewService pgAttemptHistoryViewService;

    @MockitoBean
    private PgVendorStatusViewService pgVendorStatusViewService;

    @Test
    @DisplayName("상세 조회는 시도 이력 조회 결과가 조회 가능이면 모델에 시도 이력을 담는다.")
    void 상세_이력_정상_조회시_모델에_이력_담김() throws Exception {
        // given
        givenEventDetail();
        given(pgAttemptHistoryViewService.getAttemptHistory(ORDER_ID))
                .willReturn(PgAttemptHistoryLookupResult.available(foundHistory()));

        // when / then
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("attemptHistory"))
                .andExpect(model().attribute("attemptHistoryUnavailable", false));
    }

    @Test
    @DisplayName("시도 이력 조회가 조회 불가여도 상세 화면(주문·상태 변경 이력)은 그대로 렌더된다.")
    void 상세_조회불가시_상세_렌더_유지() throws Exception {
        // given
        givenEventDetail();
        given(pgAttemptHistoryViewService.getAttemptHistory(ORDER_ID))
                .willReturn(PgAttemptHistoryLookupResult.unavailable());

        // when / then
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("event"))
                .andExpect(model().attributeExists("orders"))
                .andExpect(model().attributeExists("histories"));
    }

    @Test
    @DisplayName("시도 이력 조회가 조회 불가면 이력 조회 불가 상태가 모델에 담긴다.")
    void 상세_조회불가시_이력_조회불가_표시() throws Exception {
        // given
        givenEventDetail();
        given(pgAttemptHistoryViewService.getAttemptHistory(ORDER_ID))
                .willReturn(PgAttemptHistoryLookupResult.unavailable());

        // when / then
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attribute("attemptHistoryUnavailable", true))
                .andExpect(model().attributeDoesNotExist("attemptHistory"));
    }

    @Test
    @DisplayName("이력 없음(pg 가 주문을 모름)과 조회 불가(pg 장애)는 모델에서 서로 다르게 표현된다.")
    void 상세_이력없음과_조회불가_구분() throws Exception {
        // given: 이력 없음 — 조회는 가능했으나 found=false
        givenEventDetail();
        given(pgAttemptHistoryViewService.getAttemptHistory(ORDER_ID))
                .willReturn(PgAttemptHistoryLookupResult.available(
                        PgAttemptHistoryInfo.builder()
                                .orderId(ORDER_ID)
                                .found(false)
                                .finalStatus(null)
                                .finalizedAt(null)
                                .reasonCode(null)
                                .attempts(List.of())
                                .build()));

        // when / then: 이력 없음은 조회 불가가 아니다 — attemptHistory 는 존재하고 found=false
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("attemptHistory"))
                .andExpect(model().attribute("attemptHistoryUnavailable", false));
    }

    @Test
    @DisplayName("최초 수신 회차(scheduledAt null)는 미발행이 아니라 최초 수신 표기로 렌더된다.")
    void 상세_최초수신_회차_미발행_아닌_최초수신_표기() throws Exception {
        // given
        givenEventDetail();
        given(pgAttemptHistoryViewService.getAttemptHistory(ORDER_ID))
                .willReturn(PgAttemptHistoryLookupResult.available(foundHistory()));

        // when / then
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("최초 수신(예약 없음)")))
                .andExpect(content().string(containsString("최초 수신 시각에 즉시 실행됨")));
    }

    @Test
    @DisplayName("Result 배지는 정상 시도 / 미실행 / 소진 세 상태를 모두 구분해 렌더한다.")
    void 상세_이력_회차_상태_배지_세가지_구분_렌더() throws Exception {
        // given
        givenEventDetail();
        given(pgAttemptHistoryViewService.getAttemptHistory(ORDER_ID))
                .willReturn(PgAttemptHistoryLookupResult.available(foundHistory()));

        // when / then
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("정상 시도")))
                .andExpect(content().string(containsString("미실행(예약만 됨)")))
                .andExpect(content().string(containsString("소진(재시도 한도 초과)")));
    }

    @Test
    @DisplayName("소진 행은 발행 시각이 종결 이후라 정상 시도 판정을 벗어나도 미실행이 아니라 소진으로 렌더된다.")
    void 상세_소진행_미실행_판정_앞서_소진으로_렌더() throws Exception {
        // given: foundHistory 의 4회차는 exhausted=true 이면서 normalAttempt=false(발행 시각이
        // 종결 시각 직후) 다 — exhausted 가 normalAttempt 보다 우선해야 함을 검증한다.
        givenEventDetail();
        given(pgAttemptHistoryViewService.getAttemptHistory(ORDER_ID))
                .willReturn(PgAttemptHistoryLookupResult.available(foundHistory()));

        // when / then
        mockMvc.perform(get("/admin/payments/events/{eventId}", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("소진(재시도 한도 초과)")));
    }

    private void givenEventDetail() {
        PaymentEventResult eventResult = PaymentEventResult.builder()
                .id(EVENT_ID)
                .orderId(ORDER_ID)
                .orderName("test order")
                .status(PaymentEventStatus.DONE)
                .gatewayType(PaymentGatewayType.TOSS)
                .totalAmount(BigDecimal.valueOf(10_000))
                .createdAt(Instant.parse("2026-07-27T09:00:00Z"))
                .executedAt(Instant.parse("2026-07-27T09:00:05Z"))
                .approvedAt(Instant.parse("2026-07-27T09:00:10Z"))
                .build();

        given(adminPaymentService.getPaymentEventDetail(EVENT_ID)).willReturn(eventResult);
        given(adminPaymentService.getPaymentOrdersByEventId(EVENT_ID)).willReturn(List.of());
        given(adminPaymentService.getPaymentHistoriesByEventId(EVENT_ID)).willReturn(List.of());
    }

    private PgAttemptHistoryInfo foundHistory() {
        return PgAttemptHistoryInfo.builder()
                .orderId(ORDER_ID)
                .found(true)
                .finalStatus("QUARANTINED")
                .finalizedAt(Instant.parse("2026-07-27T09:00:20Z"))
                .reasonCode("RETRY_EXHAUSTED")
                .attempts(List.of(
                        // 1회차 — pg_inbox.created_at 으로 구성된 최초 수신 회차.
                        // outbox 행이 없어 scheduledAt / publishedAt 이 둘 다 null 이다.
                        PgAttemptEntryInfo.builder()
                                .attemptNo(Optional.of(1))
                                .reservedAt(Instant.parse("2026-07-27T09:00:00Z"))
                                .scheduledAt(null)
                                .publishedAt(null)
                                .exhausted(false)
                                .normalAttempt(true)
                                .build(),
                        // 2회차 — 정상 시도.
                        PgAttemptEntryInfo.builder()
                                .attemptNo(Optional.of(2))
                                .reservedAt(Instant.parse("2026-07-27T09:00:01Z"))
                                .scheduledAt(Instant.parse("2026-07-27T09:00:01Z"))
                                .publishedAt(Instant.parse("2026-07-27T09:00:02Z"))
                                .exhausted(false)
                                .normalAttempt(true)
                                .build(),
                        // 3회차 — 종결 이후 뒤늦게 나간 유령 행(미실행).
                        PgAttemptEntryInfo.builder()
                                .attemptNo(Optional.empty())
                                .reservedAt(Instant.parse("2026-07-27T09:00:02Z"))
                                .scheduledAt(Instant.parse("2026-07-27T09:00:20Z"))
                                .publishedAt(null)
                                .exhausted(false)
                                .normalAttempt(false)
                                .build(),
                        // 4회차 — 소진 행. 발행 시각이 종결 시각 직후라 normalAttempt 는 false 지만
                        // exhausted 가 우선이라 "미실행"이 아니라 "소진"으로 표시돼야 한다.
                        PgAttemptEntryInfo.builder()
                                .attemptNo(Optional.of(4))
                                .reservedAt(Instant.parse("2026-07-27T09:00:19Z"))
                                .scheduledAt(Instant.parse("2026-07-27T09:00:19Z"))
                                .publishedAt(Instant.parse("2026-07-27T09:00:21Z"))
                                .exhausted(true)
                                .normalAttempt(false)
                                .build()
                ))
                .build();
    }
}
