package com.hyoguoo.paymentplatform.product.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.testcontainers.containers.MySQLContainer;

/**
 * 상품 HTTP 엔드포인트 3개의 요청·응답 계약을 실제 Jackson 설정, 서비스, 트랜잭션, MySQL 로 고정하는 특성 테스트.
 *
 * <p>응답 본문은 문자열 그대로 비교해 필드 이름·순서·null 표기·숫자 표기까지 고정한다. payment-service 의 Feign
 * 어댑터와 gateway 경유 호출자가 이 형식에 기대므로, 직렬화 방식이 바뀌면 이 테스트가 먼저 깨진다.
 * 재고 차감은 응답과 함께 DB 재고를 확인해 실패 시 앞 항목까지 롤백되는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("상품 HTTP 계약 특성 통합 검증")
class ProductHttpContractIntegrationTest {

    private static final String INTERNAL_ERROR_BODY =
            "{\"code\":\"PRODUCT_INTERNAL_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}";
    private static final String NOT_FOUND_BODY =
            "{\"code\":\"E01003\",\"message\":\"존재하지 않는 상품입니다.\"}";

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("product-http-contract-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_unicode_ci"
                    )
                    .withReuse(true);

    static {
        MYSQL_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/schema");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("scheduler.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM stock");
        jdbcTemplate.update("DELETE FROM product");
    }

    @Test
    @DisplayName("단건 조회 — 응답 본문이 필드 순서·숫자 표기까지 같다")
    void getProduct_bodyIsExact() throws Exception {
        seedProduct(1L, "상품-1", "1000", 42);

        MockHttpServletResponse response = perform(get("/api/v1/products/1"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response))
                .isEqualTo("{\"id\":1,\"name\":\"상품-1\",\"price\":1000.00,\"stock\":42,\"sellerId\":7}");
    }

    @Test
    @DisplayName("단건 조회 — 재고 행이 없으면 stock 은 0")
    void getProduct_withoutStockRow_stockIsZero() throws Exception {
        seedProductOnly(2L, "상품-2", "1500.50");

        MockHttpServletResponse response = perform(get("/api/v1/products/2"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response))
                .isEqualTo("{\"id\":2,\"name\":\"상품-2\",\"price\":1500.50,\"stock\":0,\"sellerId\":7}");
    }

    @Test
    @DisplayName("단건 조회 — 없는 상품은 404 와 E01003")
    void getProduct_notFound() throws Exception {
        MockHttpServletResponse response = perform(get("/api/v1/products/999"));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body(response)).isEqualTo(NOT_FOUND_BODY);
    }

    @Test
    @DisplayName("단건 조회 — 숫자가 아닌 id 는 500 내부 오류")
    void getProduct_nonNumericId() throws Exception {
        MockHttpServletResponse response = perform(get("/api/v1/products/abc"));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY);
    }

    @Test
    @DisplayName("목록 조회 — 기본 페이지 응답 본문이 같다")
    void getProducts_defaultPage_bodyIsExact() throws Exception {
        seedProduct(1L, "상품-1", "1000", 42);
        seedProductOnly(2L, "상품-2", "1500.50");

        MockHttpServletResponse response = perform(get("/api/v1/products"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).isEqualTo("{\"content\":["
                + "{\"id\":1,\"name\":\"상품-1\",\"price\":1000.00,\"stock\":42,\"sellerId\":7},"
                + "{\"id\":2,\"name\":\"상품-2\",\"price\":1500.50,\"stock\":0,\"sellerId\":7}"
                + "],\"page\":0,\"size\":20,\"totalElements\":2,\"totalPages\":1}");
    }

    @Test
    @DisplayName("목록 조회 — 음수 page 와 0 size 는 0 과 1 로 보정된다")
    void getProducts_lowerBounds() throws Exception {
        seedProduct(1L, "상품-1", "1000", 42);
        seedProduct(3L, "상품-3", "2000", 1);

        MockHttpServletResponse response = perform(get("/api/v1/products?page=-1&size=0"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).isEqualTo("{\"content\":["
                + "{\"id\":1,\"name\":\"상품-1\",\"price\":1000.00,\"stock\":42,\"sellerId\":7}"
                + "],\"page\":0,\"size\":1,\"totalElements\":2,\"totalPages\":2}");
    }

    @Test
    @DisplayName("목록 조회 — 101 size 는 100 으로 보정되고, 범위 밖 page 는 빈 content")
    void getProducts_upperBoundsAndEmptyPage() throws Exception {
        seedProduct(1L, "상품-1", "1000", 42);

        MockHttpServletResponse response = perform(get("/api/v1/products?page=5&size=101"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response))
                .isEqualTo("{\"content\":[],\"page\":5,\"size\":100,\"totalElements\":1,\"totalPages\":1}");
    }

    @Test
    @DisplayName("목록 조회 — 숫자가 아닌 size 는 500 내부 오류")
    void getProducts_nonNumericSize() throws Exception {
        MockHttpServletResponse response = perform(get("/api/v1/products?size=abc"));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY);
    }

    @Test
    @DisplayName("재고 차감 — 200 과 빈 본문, 항목마다 재고가 준다")
    void decrease_success() throws Exception {
        seedProduct(1L, "상품-1", "1000", 10);
        seedProduct(3L, "상품-3", "2000", 5);

        MockHttpServletResponse response =
                postDecrease("[{\"productId\":1,\"stock\":3},{\"productId\":3,\"stock\":5}]");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).isEmpty();
        assertThat(stockOf(1L)).isEqualTo(7);
        assertThat(stockOf(3L)).isZero();
    }

    @Test
    @DisplayName("재고 차감 — 빈 목록은 200")
    void decrease_emptyList() throws Exception {
        MockHttpServletResponse response = postDecrease("[]");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).isEmpty();
    }

    @Test
    @DisplayName("재고 차감 — 뒤 항목 재고 부족이면 500 이고 앞 항목도 롤백된다")
    void decrease_insufficientStock_rollsBack() throws Exception {
        seedProduct(1L, "상품-1", "1000", 10);
        seedProduct(3L, "상품-3", "2000", 1);

        MockHttpServletResponse response =
                postDecrease("[{\"productId\":1,\"stock\":3},{\"productId\":3,\"stock\":2}]");

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY);
        assertThat(stockOf(1L)).isEqualTo(10);
        assertThat(stockOf(3L)).isEqualTo(1);
    }

    @Test
    @DisplayName("재고 차감 — 뒤 항목이 없는 상품이면 404 이고 앞 항목도 롤백된다")
    void decrease_notFound_rollsBack() throws Exception {
        seedProduct(1L, "상품-1", "1000", 10);

        MockHttpServletResponse response =
                postDecrease("[{\"productId\":1,\"stock\":3},{\"productId\":999,\"stock\":1}]");

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body(response)).isEqualTo(NOT_FOUND_BODY);
        assertThat(stockOf(1L)).isEqualTo(10);
    }

    @Test
    @DisplayName("재고 차감 — 뒤 항목 productId 가 null 이면 500 이고 앞 항목도 롤백된다")
    void decrease_nullProductId_rollsBack() throws Exception {
        seedProduct(1L, "상품-1", "1000", 10);

        MockHttpServletResponse response =
                postDecrease("[{\"productId\":1,\"stock\":3},{\"productId\":null,\"stock\":1}]");

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY);
        assertThat(stockOf(1L)).isEqualTo(10);
    }

    @Test
    @DisplayName("재고 차감 — 뒤 항목에 stock 이 빠지면 500 이고 앞 항목도 롤백된다")
    void decrease_absentStock_rollsBack() throws Exception {
        seedProduct(1L, "상품-1", "1000", 10);
        seedProduct(3L, "상품-3", "2000", 5);

        MockHttpServletResponse response =
                postDecrease("[{\"productId\":1,\"stock\":3},{\"productId\":3}]");

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY);
        assertThat(stockOf(1L)).isEqualTo(10);
        assertThat(stockOf(3L)).isEqualTo(5);
    }

    @Test
    @DisplayName("재고 차감 — 음수 stock 은 재고를 늘린다(검증 없음)")
    void decrease_negativeStock_increasesQuantity() throws Exception {
        seedProduct(1L, "상품-1", "1000", 10);

        MockHttpServletResponse response = postDecrease("[{\"productId\":1,\"stock\":-2}]");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(stockOf(1L)).isEqualTo(12);
    }

    @Test
    @DisplayName("재고 차감 — 잘못된 JSON 은 500 내부 오류")
    void decrease_malformedJson() throws Exception {
        MockHttpServletResponse response = postDecrease("[{\"productId\":1,");

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY);
    }

    @Test
    @DisplayName("재고 차감 — 배열이 아닌 본문은 500 내부 오류")
    void decrease_objectBody() throws Exception {
        MockHttpServletResponse response = postDecrease("{\"productId\":1,\"stock\":1}");

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY);
    }

    private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse postDecrease(String json) throws Exception {
        return perform(post("/api/v1/products/stock/decrease")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    private void seedProduct(long id, String name, String price, int quantity) {
        seedProductOnly(id, name, price);
        jdbcTemplate.update("INSERT INTO stock (product_id, quantity) VALUES (?, ?)", id, quantity);
    }

    private void seedProductOnly(long id, String name, String price) {
        jdbcTemplate.update(
                "INSERT INTO product (id, name, price, description, seller_id) VALUES (?, ?, ?, ?, ?)",
                id, name, new java.math.BigDecimal(price), "설명", 7L);
    }

    private Integer stockOf(long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM stock WHERE product_id = ?", Integer.class, productId);
    }
}
