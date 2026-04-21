package com.hyoguoo.paymentplatform.user;

import com.hyoguoo.paymentplatform.user.application.port.out.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * user-service 컨텍스트 로드 스모크 테스트.
 * - DataSource/JPA/Flyway autoconfig 제외 — DB 미연결 환경.
 * - UserRepository: 포트 인터페이스 구현체 미등록이므로 @MockitoBean 제공.
 */
@SpringBootTest(
        classes = UserServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
})
class UserServiceApplicationTest {

    @MockitoBean
    UserRepository userRepository;

    @Test
    void contextLoads() {
        // 컨텍스트가 정상 로드되면 PASS
    }
}
