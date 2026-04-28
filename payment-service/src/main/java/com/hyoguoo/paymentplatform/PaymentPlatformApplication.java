package com.hyoguoo.paymentplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

// basePackages 생략 — main 클래스 하위 자동 scan 으로 ProductFeignClient / UserFeignClient 등록.
@EnableFeignClients
@SpringBootApplication
public class PaymentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentPlatformApplication.class, args);
    }
}
