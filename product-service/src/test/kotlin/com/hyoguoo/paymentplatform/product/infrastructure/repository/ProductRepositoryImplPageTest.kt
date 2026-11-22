package com.hyoguoo.paymentplatform.product.infrastructure.repository

import com.hyoguoo.paymentplatform.product.infrastructure.entity.ProductEntity
import com.hyoguoo.paymentplatform.product.infrastructure.entity.StockEntity
import com.hyoguoo.paymentplatform.product.support.KMySQLContainer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal

/**
 * ProductRepositoryImpl.findPage 관리자 상품 목록 페이징 조회 통합 테스트.
 *
 * `pg-service` 의 `PgOutboxRepositoryImplTest` 의 `@DataJpaTest` + 정적
 * Testcontainers MySQL 패턴을 그대로 따른다 — product + stock 조인이 실제로 확정 수량을 담는지,
 * count 쿼리가 페이지 크기와 무관하게 전체 건수를 반환하는지는 실제 DB 로만 검증할 수 있다.
 *
 * flyway.locations 를 `classpath:db/schema` 로만 한정해 V2 seed(db/seed)를 배제한다 —
 * 테스트가 seed 데이터에 의존하지 않고 각 케이스가 삽입한 행만으로 단정하기 위함이다
 * (`application-docker.yml` 과 동일한 방식).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProductRepositoryImpl::class)
@DisplayName("ProductRepositoryImpl.findPage — 상품 목록 페이징 조회")
@Tag("integration")
class ProductRepositoryImplPageTest {
    @Autowired
    private lateinit var sut: ProductRepositoryImpl

    @Autowired
    private lateinit var jpaProductRepository: JpaProductRepository

    @Autowired
    private lateinit var jpaStockRepository: JpaStockRepository

    @BeforeEach
    fun setUp() {
        // stock 이 product 를 FK 로 참조하므로 stock 먼저 지운다.
        jpaStockRepository.deleteAll()
        jpaProductRepository.deleteAll()
    }

    @Test
    @DisplayName("요청한 페이지 크기만큼 반환한다")
    fun `목록조회_요청한_페이지_크기만큼_반환`() {
        // given
        for (i in 0 until 5) {
            saveProductWithStock("상품-$i", 10)
        }

        // when
        val result = sut.findPage(0, 2)

        // then
        assertThat(result.content).hasSize(2)
    }

    @Test
    @DisplayName("상품과 확정 재고를 조인해 수량을 포함한다")
    fun `목록조회_확정_수량_포함`() {
        // given
        val productId = saveProductWithStock("확정재고상품", 42)

        // when
        val result = sut.findPage(0, 10)

        // then
        assertThat(result.content.filter { it.id == productId }.map { it.stock })
            .containsExactly(42)
    }

    @Test
    @DisplayName("범위를 넘는 페이지를 요청하면 빈 목록을 반환한다")
    fun `목록조회_범위를_넘는_페이지_빈_목록`() {
        // given
        saveProductWithStock("상품", 1)

        // when
        val result = sut.findPage(10, 10)

        // then
        assertThat(result.content).isEmpty()
    }

    @Test
    @DisplayName("페이지 크기와 무관하게 전체 건수를 반환한다")
    fun `목록조회_전체_건수_반환`() {
        // given
        for (i in 0 until 3) {
            saveProductWithStock("상품-$i", 1)
        }

        // when
        val result = sut.findPage(0, 2)

        // then
        assertThat(result.totalElements).isEqualTo(3L)
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private fun saveProductWithStock(
        name: String,
        quantity: Int,
    ): Long {
        val savedProduct =
            jpaProductRepository.save(
                ProductEntity(name, BigDecimal.valueOf(1000), "설명", 1L),
            )
        val productId = savedProduct.id!!
        jpaStockRepository.save(StockEntity(productId, quantity))
        return productId
    }

    companion object {
        private val MYSQL_CONTAINER: KMySQLContainer =
            KMySQLContainer("mysql:8.0")
                .withDatabaseName("product-test")
                .withUsername("test")
                .withPassword("test")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")

        init {
            MYSQL_CONTAINER.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { MYSQL_CONTAINER.jdbcUrl }
            registry.add("spring.datasource.username") { MYSQL_CONTAINER.username }
            registry.add("spring.datasource.password") { MYSQL_CONTAINER.password }
            registry.add("spring.datasource.driver-class-name") { "com.mysql.cj.jdbc.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.locations") { "classpath:db/schema" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
