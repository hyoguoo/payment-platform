package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogLookupResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogPageInfo;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductCatalogQueryPort;
import com.hyoguoo.paymentplatform.payment.exception.ProductServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@code StockCatalogViewServiceImpl} 단위 테스트.
 *
 * <p>조회 실패 흡수 + 조회 불가 폴백 판단(리뷰 finding 1로 컨트롤러에서 이 계층으로 옮김)이
 * 정확히 여기서 이뤄지는지 검증한다.
 */
class StockCatalogViewServiceImplTest {

    private ProductCatalogQueryPort productCatalogQueryPort;
    private StockCatalogViewServiceImpl sut;

    @BeforeEach
    void setUp() {
        productCatalogQueryPort = Mockito.mock(ProductCatalogQueryPort.class);
        sut = new StockCatalogViewServiceImpl(productCatalogQueryPort);
    }

    @Test
    @DisplayName("포트 조회가 성공하면 조회 가능 결과에 그 값을 그대로 담는다")
    void getPage_success_returnsAvailableResult() {
        // given
        ProductCatalogPageInfo pageInfo = ProductCatalogPageInfo.builder()
                .content(List.of())
                .page(0)
                .size(20)
                .totalElements(0)
                .totalPages(0)
                .build();
        given(productCatalogQueryPort.getPage(0, 20)).willReturn(pageInfo);

        // when
        ProductCatalogLookupResult result = sut.getPage(0, 20);

        // then
        assertThat(result.isUnavailable()).isFalse();
        assertThat(result.getPage()).isSameAs(pageInfo);
    }

    @Test
    @DisplayName("포트 조회가 재시도 가능(장애) 예외를 던지면 조회 불가 결과로 흡수된다")
    void getPage_retryableException_returnsUnavailable() {
        // given
        willThrow(ProductServiceRetryableException.of(PaymentErrorCode.PRODUCT_SERVICE_UNAVAILABLE))
                .given(productCatalogQueryPort).getPage(0, 20);

        // when
        ProductCatalogLookupResult result = sut.getPage(0, 20);

        // then
        assertThat(result.isUnavailable()).isTrue();
        assertThat(result.getPage()).isNull();
    }
}
