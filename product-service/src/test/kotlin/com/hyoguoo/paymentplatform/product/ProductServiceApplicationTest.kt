package com.hyoguoo.paymentplatform.product

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore
import com.hyoguoo.paymentplatform.product.application.port.out.ProductRepository
import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository
import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import javax.sql.DataSource

/**
 * product-service 컨텍스트 로드 스모크 테스트.
 * - spring.kafka.bootstrap-servers 비워서 Kafka autoconfig 회피
 *   (@ConditionalOnProperty 적용 bean — KafkaTopicConfig 미기동).
 * - DataSource/JPA/Flyway autoconfig 제외 — DB 미연결 환경.
 * - StockRepository/EventDedupeStore: 포트 인터페이스 구현체 미등록이므로 목 빈으로 제공.
 *   Mockito 목처럼 호출에 기본값을 돌려주도록 relaxed 로 둔다.
 */
@SpringBootTest(
    classes = [ProductServiceApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "spring.kafka.listener.auto-startup=false",
    ],
)
class ProductServiceApplicationTest {
    @MockkBean(relaxed = true)
    lateinit var dataSource: DataSource

    @MockkBean(relaxed = true)
    lateinit var stockRepository: StockRepository

    @MockkBean(relaxed = true)
    lateinit var productRepository: ProductRepository

    @MockkBean(relaxed = true)
    lateinit var eventDedupeStore: EventDedupeStore

    @Test
    fun contextLoads() {
        // 컨텍스트가 정상 로드되면 PASS
    }
}
