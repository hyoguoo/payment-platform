package com.hyoguoo.paymentplatform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"eureka.client.enabled=false"})
class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
