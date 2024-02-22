package study.paymentintegrationserver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static study.paymentintegrationserver.TestDataFactory.generateOrderInfoWithTotalAmountAndQuantity;
import static study.paymentintegrationserver.TestDataFactory.generateProductWithPriceAndStock;
import static study.paymentintegrationserver.TestDataFactory.generateUser;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import study.paymentintegrationserver.dto.order.OrderListResponse;
import study.paymentintegrationserver.entity.OrderInfo;
import study.paymentintegrationserver.entity.Product;
import study.paymentintegrationserver.entity.User;
import study.paymentintegrationserver.repository.OrderInfoRepository;

class OrderServiceTest {

    private final static BigDecimal DEFAULT_PRODUCT_PRICE = BigDecimal.valueOf(10000);
    private final static Integer DEFAULT_STOCK = 10;
    private final static Integer DEFAULT_QUANTITY = 1;
    private final static BigDecimal DEFAULT_TOTAL_AMOUNT =
            DEFAULT_PRODUCT_PRICE.multiply(BigDecimal.valueOf(DEFAULT_QUANTITY));
    private final static User DEFAULT_USER = generateUser();
    private final static Product DEFAULT_PRODUCT =
            generateProductWithPriceAndStock(DEFAULT_PRODUCT_PRICE, DEFAULT_STOCK);

    @InjectMocks
    private OrderService orderService;

    @Mock
    private OrderInfoRepository orderInfoRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 10, 5",
            "0, 5, 5",
            "0, 50, 5",
    })
    @DisplayName("주어진 페이지 정보로 주문 목록을 조회합니다.")
    void findOrderList(Integer page, Integer size, Long expectedSize) {
        // Given
        List<OrderInfo> orderInfoList = List.of(
                generateOrderInfoWithTotalAmountAndQuantity(1L, DEFAULT_USER, DEFAULT_PRODUCT,
                        DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY),
                generateOrderInfoWithTotalAmountAndQuantity(1L, DEFAULT_USER, DEFAULT_PRODUCT,
                        DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY),
                generateOrderInfoWithTotalAmountAndQuantity(1L, DEFAULT_USER, DEFAULT_PRODUCT,
                        DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY),
                generateOrderInfoWithTotalAmountAndQuantity(1L, DEFAULT_USER, DEFAULT_PRODUCT,
                        DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY),
                generateOrderInfoWithTotalAmountAndQuantity(1L, DEFAULT_USER, DEFAULT_PRODUCT,
                        DEFAULT_TOTAL_AMOUNT, DEFAULT_QUANTITY)
        );
        when(orderInfoRepository.findAllWithProductAndUser(
                PageRequest.of(page, size, Sort.by("id").descending())))
                .thenReturn(new PageImpl<>(orderInfoList));

        // When
        OrderListResponse result = orderService.findOrderList(page, size);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(expectedSize);
    }
}
