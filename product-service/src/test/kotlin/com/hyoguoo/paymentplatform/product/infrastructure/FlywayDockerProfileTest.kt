package com.hyoguoo.paymentplatform.product.infrastructure

import com.hyoguoo.paymentplatform.product.support.KMySQLContainer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import javax.sql.DataSource

/**
 * docker profile 에서 Flyway seed(V2) 가 적용되지 않음을 검증하는 통합 테스트.
 *
 * application-docker.yml 의 `spring.flyway.locations: classpath:db/schema` override 로
 * V2__seed_product_stock.sql 이 실행되지 않아야 한다.
 *
 * docker-java 기본 API 버전(1.32)이 Docker 29.4.2 최소 지원 버전(1.40)보다 낮아
 * src/test/resources/docker-java.properties 에서 api.version=1.44 로 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("integration")
@ActiveProfiles("docker")
class FlywayDockerProfileTest {
    @Autowired
    private lateinit var dataSource: DataSource

    /**
     * docker profile 에서 V2 seed 가 차단됨을 검증한다.
     *
     * 1. flyway_schema_history 에 V1 record 만 존재 (V2 없음)
     * 2. product 테이블 row count = 0 (seed 미적용)
     */
    @Test
    @DisplayName("docker profile — V2 seed 차단: flyway_schema_history V1 only + product row count 0")
    fun dockerProfile_doesNotApplySeedMigration() {
        dataSource.connection.use { conn ->
            // 케이스 1: flyway_schema_history 에 V1 record 만 존재
            val historyCount = queryCount(conn, "SELECT COUNT(*) FROM flyway_schema_history")
            assertThat(historyCount)
                .`as`("flyway_schema_history row count — V1 만 존재해야 한다")
                .isEqualTo(1)

            // 케이스 2: product 테이블 row count = 0 (seed 미적용)
            val productCount = queryCount(conn, "SELECT COUNT(*) FROM product")
            assertThat(productCount)
                .`as`("product 테이블 row count — docker profile 에서 seed 가 적용되지 않아야 한다")
                .isZero
        }
    }

    private fun queryCount(
        conn: Connection,
        sql: String,
    ): Int =
        conn.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    companion object {
        @Container
        @JvmField
        val mysql: KMySQLContainer =
            KMySQLContainer("mysql:8.0")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                )

        @JvmStatic
        @DynamicPropertySource
        fun registerProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.flyway.url") { mysql.jdbcUrl }
            registry.add("spring.flyway.user") { mysql.username }
            registry.add("spring.flyway.password") { mysql.password }
            // Kafka, Eureka 는 이 테스트에서 불필요
            registry.add("spring.kafka.bootstrap-servers") { "localhost:9092" }
            registry.add("spring.kafka.listener.auto-startup") { "false" }
            registry.add("eureka.client.enabled") { "false" }
            registry.add("eureka.client.register-with-eureka") { "false" }
            registry.add("eureka.client.fetch-registry") { "false" }
        }
    }
}
