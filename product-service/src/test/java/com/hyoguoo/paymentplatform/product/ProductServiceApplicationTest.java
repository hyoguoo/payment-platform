package com.hyoguoo.paymentplatform.product;

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.product.application.port.out.PaymentStockCachePort;
import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * product-service 컨텍스트 로드 스모크 테스트.
 * - spring.kafka.bootstrap-servers 비워서 Kafka autoconfig 회피
 *   (@ConditionalOnProperty 적용 bean — KafkaTopicConfig, StockSnapshotPublisher 미기동).
 * - DataSource/JPA/Flyway/Redis autoconfig 제외 — DB 미연결 환경.
 * - StockRepository/EventDedupeStore: 포트 인터페이스 구현체 미등록이므로 @MockBean 제공.
 */
@SpringBootTest(
        classes = ProductServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "spring.kafka.listener.auto-startup=false",
})
class ProductServiceApplicationTest {

    @MockitoBean
    StockRepository stockRepository;

    @MockitoBean
    EventDedupeStore eventDedupeStore;

    @MockitoBean
    PaymentStockCachePort paymentStockCachePort;

    @Test
    void contextLoads() {
        // 컨텍스트가 정상 로드되면 PASS
    }
}
