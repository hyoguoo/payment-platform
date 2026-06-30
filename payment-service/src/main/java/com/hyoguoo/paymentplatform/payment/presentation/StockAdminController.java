package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin.StockResyncResponse;
import com.hyoguoo.paymentplatform.payment.presentation.port.StockAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재고 캐시 운영 admin REST 컨트롤러.
 *
 * <p>redis-stock(payment 선차감 캐시)이 product RDB(SoT)와 발산했을 때 운영자가
 * 특정 productId 의 캐시를 RDB 기준으로 수동 재정렬한다. in-flight 선차감 안전성은
 * {@code StockCachePort#set} 주석 참고 — 트래픽이 조용한 시점/특정 productId 한정 호출이 전제.
 */
@RestController
@RequestMapping("/admin/stock")
@RequiredArgsConstructor
public class StockAdminController {

    private final StockAdminService stockAdminService;

    @PostMapping("/resync/{productId}")
    public ResponseEntity<StockResyncResponse> resync(@PathVariable Long productId) {
        int quantity = stockAdminService.resyncStockCache(productId);
        return ResponseEntity.ok(new StockResyncResponse(productId, quantity));
    }
}
