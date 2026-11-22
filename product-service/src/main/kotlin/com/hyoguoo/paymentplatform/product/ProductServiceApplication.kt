package com.hyoguoo.paymentplatform.product

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * product-service 메인 클래스.
 * Virtual Threads: application.yml spring.threads.virtual.enabled=true 로 활성화.
 */
@SpringBootApplication
class ProductServiceApplication

@Suppress("SpreadOperator") // 기동 시 한 번만 인자 배열을 넘긴다
fun main(args: Array<String>) {
    runApplication<ProductServiceApplication>(*args)
}
