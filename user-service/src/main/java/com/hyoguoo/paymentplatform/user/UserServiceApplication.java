package com.hyoguoo.paymentplatform.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * user-service 메인 클래스.
 * Virtual Threads: application.yml spring.threads.virtual.enabled=true 로 활성화.
 */
@SpringBootApplication
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
