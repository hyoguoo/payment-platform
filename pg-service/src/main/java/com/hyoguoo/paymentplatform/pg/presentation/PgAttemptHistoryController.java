package com.hyoguoo.paymentplatform.pg.presentation;

import com.hyoguoo.paymentplatform.pg.application.dto.PgAttemptHistory;
import com.hyoguoo.paymentplatform.pg.presentation.dto.PgAttemptHistoryResponse;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgAttemptHistoryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * pg-service 관리자 조회 전용 REST 컨트롤러 — pg-service 최초의 HTTP 진입점.
 * payment-service 의 Feign client(Task 6)가 관리자 상세 화면 렌더 시 호출한다.
 *
 * <ul>
 *   <li>GET /api/v1/confirmations/{orderId}/attempts — 확정 시도 이력 조회</li>
 * </ul>
 *
 * <p>이력이 없는 주문도 404 가 아니라 {@code found=false} 를 담은 200 응답이다 — 조회 실패(예외)와
 * 구분된다. 이 컨트롤러는 예외를 흡수하지 않으므로 조회 자체가 실패하면 그대로 5xx 로 전파된다.
 * pg-service 에는 아직 {@code @RestControllerAdvice} 가 없다 — 이 엔드포인트가 흡수해야 하는
 * 도메인 예외가 없으므로(이력 없음이 정상 응답으로 처리됨) 핸들러 없이 끝나는 것이 설계 의도다.
 */
@RestController
@RequestMapping("/api/v1/confirmations")
@RequiredArgsConstructor
public class PgAttemptHistoryController {

    private final PgAttemptHistoryQueryService pgAttemptHistoryQueryService;

    @GetMapping("/{orderId}/attempts")
    public ResponseEntity<PgAttemptHistoryResponse> getAttemptHistory(@PathVariable String orderId) {
        PgAttemptHistory history = pgAttemptHistoryQueryService.getAttemptHistory(orderId);
        return ResponseEntity.ok(PgAttemptHistoryResponse.from(history));
    }
}
