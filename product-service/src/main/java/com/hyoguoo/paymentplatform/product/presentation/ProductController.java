package com.hyoguoo.paymentplatform.product.presentation;

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductPageResponse;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductResponse;
import com.hyoguoo.paymentplatform.product.presentation.dto.StockCommandItem;
import com.hyoguoo.paymentplatform.product.presentation.port.ProductQueryService;
import com.hyoguoo.paymentplatform.product.presentation.port.StockCommandService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 REST 컨트롤러.
 * payment-service ProductHttpAdapter(@ConditionalOnProperty product.adapter.type=http)와 페어.
 *
 * <ul>
 *   <li>GET /api/v1/products — 상품 목록 페이징 조회 (관리자 화면용)</li>
 *   <li>GET /api/v1/products/{id} — 상품+재고 조회</li>
 *   <li>POST /api/v1/products/stock/decrease — 동기 재고 차감</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final ProductQueryService productQueryService;
    private final StockCommandService stockCommandService;

    @GetMapping
    public ResponseEntity<ProductPageResponse> getProducts(
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        int boundedPage = Math.max(page, DEFAULT_PAGE);
        int boundedSize = Math.clamp(size, 1, MAX_SIZE);
        ProductPageResponse response =
                ProductPageResponse.from(productQueryService.getPage(boundedPage, boundedSize));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long id) {
        ProductResponse response = ProductResponse.from(productQueryService.getById(id));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/stock/decrease")
    public ResponseEntity<Void> decreaseStock(@RequestBody List<StockCommandItem> items) {
        stockCommandService.decreaseForOrders(toCommands(items));
        return ResponseEntity.ok().build();
    }

    private List<ProductStockCommand> toCommands(List<StockCommandItem> items) {
        return items.stream().map(StockCommandItem::toCommand).toList();
    }
}
