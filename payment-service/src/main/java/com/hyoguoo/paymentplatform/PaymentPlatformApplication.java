package com.hyoguoo.paymentplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

// basePackages 명시 생략 — main 클래스 하위 자동 scan, B2 에서 ProductFeignClient 등록 예정
@EnableFeignClients
@SpringBootApplication
public class PaymentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentPlatformApplication.class, args);
    }
}
