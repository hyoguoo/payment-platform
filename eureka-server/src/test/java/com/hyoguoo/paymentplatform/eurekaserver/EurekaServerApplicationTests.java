package com.hyoguoo.paymentplatform.eurekaserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false"
    })
class EurekaServerApplicationTests {

    @Test
    void contextLoads() {
    }
}
