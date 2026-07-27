package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogPageInfo;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductCatalogQueryPort;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 상품 확정 재고 목록(관리자 조회 전용) 화면.
 *
 * <p>기존 {@link StockAdminController}(REST, {@code /admin/stock} — 캐시 재동기화 POST)와
 * 경로가 겹치지 않도록 복수형 경로({@code /admin/stocks})를 쓴다. 결제 관리자 화면이
 * {@code /admin/payments} 복수형을 쓰는 관례와 맞췄다.
 *
 * <p>여기서 보여주는 확정 수량은 product-service RDB 기준 값(product-stock 조인)이다.
 * 결제 승인 경로가 실제로 참조하는 redis-stock 선차감 캐시와는 다를 수 있어, 화면에
 * "실시간 판매 가능 여부는 다를 수 있다"는 안내를 고정 문구로 둔다 — 캐시가 발산한
 * 구간에서는 이 화면의 수량이 정상으로 보여도 판매 게이트가 깨져 있을 수 있다.
 *
 * <p>조회 전용이다. 기존 캐시 재동기화 REST({@link StockAdminController#resync})는
 * 동시성 위험 때문에 이 화면에 버튼으로 노출하지 않는다 — 조작 기능은 이번 범위 밖.
 */
@Slf4j
@Controller
@RequestMapping("/admin/stocks")
@RequiredArgsConstructor
public class StockViewController {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;

    private final ProductCatalogQueryPort productCatalogQueryPort;

    /**
     * product-service 조회가 실패(장애·타임아웃 등 어떤 런타임 예외든)해도 이 메서드가 흡수하고
     * 조회 불가 상태만 모델에 담는다 — 결제 상세 상세 화면(Task 8)의 부분 렌더와 같은 취지로,
     * 상품 서비스가 죽어도 이 화면 자체는 500 으로 깨지지 않는다.
     */
    @GetMapping
    public String listStocks(
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size,
            Model model
    ) {
        try {
            ProductCatalogPageInfo pageInfo = productCatalogQueryPort.getPage(page, size);
            model.addAttribute("products", pageInfo);
            model.addAttribute("unavailable", false);
        } catch (RuntimeException e) {
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.PRODUCT_SERVICE_UNEXPECTED,
                    () -> "page=" + page + " size=" + size + " error=" + e.getMessage());
            model.addAttribute("unavailable", true);
        }

        return "admin/stock";
    }
}
