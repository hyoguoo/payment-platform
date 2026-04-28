package com.hyoguoo.paymentplatform.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * product-service 메인 클래스.
 * Virtual Threads: application.yml spring.threads.virtual.enabled=true 로 활성화.
 */
@SpringBootApplication
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
