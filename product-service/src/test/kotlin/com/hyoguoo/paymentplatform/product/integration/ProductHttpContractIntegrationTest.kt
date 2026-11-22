package com.hyoguoo.paymentplatform.product.integration

import com.hyoguoo.paymentplatform.product.support.KMySQLContainer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.nio.charset.StandardCharsets

/**
 * 상품 HTTP 엔드포인트 3개의 요청·응답 계약을 실제 Jackson 설정, 서비스, 트랜잭션, MySQL 로 고정하는 특성 테스트.
 *
 * 응답 본문은 문자열 그대로 비교해 필드 이름·순서·null 표기·숫자 표기까지 고정한다. payment-service 의 Feign
 * 어댑터와 gateway 경유 호출자가 이 형식에 기대므로, 직렬화 방식이 바뀌면 이 테스트가 먼저 깨진다.
 * 재고 차감은 응답과 함께 DB 재고를 확인해 실패 시 앞 항목까지 롤백되는지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("상품 HTTP 계약 특성 통합 검증")
class ProductHttpContractIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("DELETE FROM stock")
        jdbcTemplate.update("DELETE FROM product")
    }

    @Test
    @DisplayName("단건 조회 — 응답 본문이 필드 순서·숫자 표기까지 같다")
    fun getProduct_bodyIsExact() {
        seedProduct(1L, "상품-1", "1000", 42)

        val response = perform(get("/api/v1/products/1"))

        assertThat(response.status).isEqualTo(200)
        assertThat(body(response))
            .isEqualTo("{\"id\":1,\"name\":\"상품-1\",\"price\":1000.00,\"stock\":42,\"sellerId\":7}")
    }

    @Test
    @DisplayName("단건 조회 — 재고 행이 없으면 stock 은 0")
    fun getProduct_withoutStockRow_stockIsZero() {
        seedProductOnly(2L, "상품-2", "1500.50")

        val response = perform(get("/api/v1/products/2"))

        assertThat(response.status).isEqualTo(200)
        assertThat(body(response))
            .isEqualTo("{\"id\":2,\"name\":\"상품-2\",\"price\":1500.50,\"stock\":0,\"sellerId\":7}")
    }

    @Test
    @DisplayName("단건 조회 — 없는 상품은 404 와 E01003")
    fun getProduct_notFound() {
        val response = perform(get("/api/v1/products/999"))

        assertThat(response.status).isEqualTo(404)
        assertThat(body(response)).isEqualTo(NOT_FOUND_BODY)
    }

    @Test
    @DisplayName("단건 조회 — 숫자가 아닌 id 는 500 내부 오류")
    fun getProduct_nonNumericId() {
        val response = perform(get("/api/v1/products/abc"))

        assertThat(response.status).isEqualTo(500)
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY)
    }

    @Test
    @DisplayName("목록 조회 — 기본 페이지 응답 본문이 같다")
    fun getProducts_defaultPage_bodyIsExact() {
        seedProduct(1L, "상품-1", "1000", 42)
        seedProductOnly(2L, "상품-2", "1500.50")

        val response = perform(get("/api/v1/products"))

        assertThat(response.status).isEqualTo(200)
        assertThat(body(response)).isEqualTo(
            "{\"content\":[" +
                "{\"id\":1,\"name\":\"상품-1\",\"price\":1000.00,\"stock\":42,\"sellerId\":7}," +
                "{\"id\":2,\"name\":\"상품-2\",\"price\":1500.50,\"stock\":0,\"sellerId\":7}" +
                "],\"page\":0,\"size\":20,\"totalElements\":2,\"totalPages\":1}",
        )
    }

    @Test
    @DisplayName("목록 조회 — 음수 page 와 0 size 는 0 과 1 로 보정된다")
    fun getProducts_lowerBounds() {
        seedProduct(1L, "상품-1", "1000", 42)
        seedProduct(3L, "상품-3", "2000", 1)

        val response = perform(get("/api/v1/products?page=-1&size=0"))

        assertThat(response.status).isEqualTo(200)
        assertThat(body(response)).isEqualTo(
            "{\"content\":[" +
                "{\"id\":1,\"name\":\"상품-1\",\"price\":1000.00,\"stock\":42,\"sellerId\":7}" +
                "],\"page\":0,\"size\":1,\"totalElements\":2,\"totalPages\":2}",
        )
    }

    @Test
    @DisplayName("목록 조회 — 101 size 는 100 으로 보정되고, 범위 밖 page 는 빈 content")
    fun getProducts_upperBoundsAndEmptyPage() {
        seedProduct(1L, "상품-1", "1000", 42)

        val response = perform(get("/api/v1/products?page=5&size=101"))

        assertThat(response.status).isEqualTo(200)
        assertThat(body(response))
            .isEqualTo("{\"content\":[],\"page\":5,\"size\":100,\"totalElements\":1,\"totalPages\":1}")
    }

    @Test
    @DisplayName("목록 조회 — 숫자가 아닌 size 는 500 내부 오류")
    fun getProducts_nonNumericSize() {
        val response = perform(get("/api/v1/products?size=abc"))

        assertThat(response.status).isEqualTo(500)
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY)
    }

    @Test
    @DisplayName("재고 차감 — 200 과 빈 본문, 항목마다 재고가 준다")
    fun decrease_success() {
        seedProduct(1L, "상품-1", "1000", 10)
        seedProduct(3L, "상품-3", "2000", 5)

        val response =
            postDecrease("[{\"productId\":1,\"stock\":3},{\"productId\":3,\"stock\":5}]")

        assertThat(response.status).isEqualTo(200)
        assertThat(body(response)).isEmpty()
        assertThat(stockOf(1L)).isEqualTo(7)
        assertThat(stockOf(3L)).isZero
    }

    @Test
    @DisplayName("재고 차감 — 빈 목록은 200")
    fun decrease_emptyList() {
        val response = postDecrease("[]")

        assertThat(response.status).isEqualTo(200)
        assertThat(body(response)).isEmpty()
    }

    @Test
    @DisplayName("재고 차감 — 뒤 항목 재고 부족이면 500 이고 앞 항목도 롤백된다")
    fun decrease_insufficientStock_rollsBack() {
        seedProduct(1L, "상품-1", "1000", 10)
        seedProduct(3L, "상품-3", "2000", 1)

        val response =
            postDecrease("[{\"productId\":1,\"stock\":3},{\"productId\":3,\"stock\":2}]")

        assertThat(response.status).isEqualTo(500)
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY)
        assertThat(stockOf(1L)).isEqualTo(10)
        assertThat(stockOf(3L)).isEqualTo(1)
    }

    @Test
    @DisplayName("재고 차감 — 뒤 항목이 없는 상품이면 404 이고 앞 항목도 롤백된다")
    fun decrease_notFound_rollsBack() {
        seedProduct(1L, "상품-1", "1000", 10)

        val response =
            postDecrease("[{\"productId\":1,\"stock\":3},{\"productId\":999,\"stock\":1}]")

        assertThat(response.status).isEqualTo(404)
        assertThat(body(response)).isEqualTo(NOT_FOUND_BODY)
        assertThat(stockOf(1L)).isEqualTo(10)
    }

    @Test
    @DisplayName("재고 차감 — 뒤 항목 productId 가 null 이면 500 이고 앞 항목도 롤백된다")
    fun decrease_nullProductId_rollsBack() {
        seedProduct(1L, "상품-1", "1000", 10)

        val response =
            postDecrease("[{\"productId\":1,\"stock\":3},{\"productId\":null,\"stock\":1}]")

        assertThat(response.status).isEqualTo(500)
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY)
        assertThat(stockOf(1L)).isEqualTo(10)
    }

    @Test
    @DisplayName("재고 차감 — 뒤 항목에 stock 이 빠지면 500 이고 앞 항목도 롤백된다")
    fun decrease_absentStock_rollsBack() {
        seedProduct(1L, "상품-1", "1000", 10)
        seedProduct(3L, "상품-3", "2000", 5)

        val response =
            postDecrease("[{\"productId\":1,\"stock\":3},{\"productId\":3}]")

        assertThat(response.status).isEqualTo(500)
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY)
        assertThat(stockOf(1L)).isEqualTo(10)
        assertThat(stockOf(3L)).isEqualTo(5)
    }

    @Test
    @DisplayName("재고 차감 — 음수 stock 은 재고를 늘린다(검증 없음)")
    fun decrease_negativeStock_increasesQuantity() {
        seedProduct(1L, "상품-1", "1000", 10)

        val response = postDecrease("[{\"productId\":1,\"stock\":-2}]")

        assertThat(response.status).isEqualTo(200)
        assertThat(stockOf(1L)).isEqualTo(12)
    }

    @Test
    @DisplayName("재고 차감 — 잘못된 JSON 은 500 내부 오류")
    fun decrease_malformedJson() {
        val response = postDecrease("[{\"productId\":1,")

        assertThat(response.status).isEqualTo(500)
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY)
    }

    @Test
    @DisplayName("재고 차감 — 배열이 아닌 본문은 500 내부 오류")
    fun decrease_objectBody() {
        val response = postDecrease("{\"productId\":1,\"stock\":1}")

        assertThat(response.status).isEqualTo(500)
        assertThat(body(response)).isEqualTo(INTERNAL_ERROR_BODY)
    }

    private fun perform(request: RequestBuilder): MockHttpServletResponse =
        mockMvc.perform(request).andReturn().response

    private fun postDecrease(json: String): MockHttpServletResponse =
        perform(
            post("/api/v1/products/stock/decrease")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json),
        )

    private fun body(response: MockHttpServletResponse): String = response.getContentAsString(StandardCharsets.UTF_8)

    private fun seedProduct(
        id: Long,
        name: String,
        price: String,
        quantity: Int,
    ) {
        seedProductOnly(id, name, price)
        jdbcTemplate.update("INSERT INTO stock (product_id, quantity) VALUES (?, ?)", id, quantity)
    }

    private fun seedProductOnly(
        id: Long,
        name: String,
        price: String,
    ) {
        jdbcTemplate.update(
            "INSERT INTO product (id, name, price, description, seller_id) VALUES (?, ?, ?, ?, ?)",
            id,
            name,
            java.math.BigDecimal(price),
            "설명",
            7L,
        )
    }

    private fun stockOf(productId: Long): Int? =
        jdbcTemplate.queryForObject(
            "SELECT quantity FROM stock WHERE product_id = ?",
            Int::class.javaObjectType,
            productId,
        )

    companion object {
        private const val INTERNAL_ERROR_BODY =
            "{\"code\":\"PRODUCT_INTERNAL_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}"
        private const val NOT_FOUND_BODY =
            "{\"code\":\"E01003\",\"message\":\"존재하지 않는 상품입니다.\"}"

        private val MYSQL_CONTAINER: KMySQLContainer =
            KMySQLContainer("mysql:8.0")
                .withDatabaseName("product-http-contract-test")
                .withUsername("test")
                .withPassword("test")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                ).withReuse(true)

        init {
            MYSQL_CONTAINER.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { MYSQL_CONTAINER.jdbcUrl }
            registry.add("spring.datasource.username") { MYSQL_CONTAINER.username }
            registry.add("spring.datasource.password") { MYSQL_CONTAINER.password }
            registry.add("spring.datasource.driver-class-name") { "com.mysql.cj.jdbc.Driver" }
            registry.add("spring.flyway.locations") { "classpath:db/schema" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("scheduler.enabled") { "false" }
            registry.add("spring.kafka.listener.auto-startup") { "false" }
        }
    }
}
