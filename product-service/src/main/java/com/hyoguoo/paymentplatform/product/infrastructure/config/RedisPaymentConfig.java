package com.hyoguoo.paymentplatform.product.infrastructure.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * payment-service 전용 Redis 인스턴스(redis-payment) 연결 설정.
 * <p>
 * product.cache.payment-redis.host 프로퍼티가 없으면 빈이 등록되지 않는다.
 * 테스트 환경에서는 FakePaymentStockCachePort를 주입해 Redis 없이 검증한다.
 * <p>
 * keyspace: {@code stock:{productId}} — payment-service의 재고 캐시와 동일 키 규약 사용.
 */
@Configuration
@ConditionalOnProperty(name = "product.cache.payment-redis.host")
public class RedisPaymentConfig {

    @Value("${product.cache.payment-redis.host:localhost}")
    private String host;

    @Value("${product.cache.payment-redis.port:6380}")
    private int port;

    /**
     * redis-payment 전용 LettuceConnectionFactory 빈.
     * 기본 spring.data.redis 연결과 분리된 별도 인스턴스.
     */
    @Bean(name = "paymentRedisConnectionFactory")
    public RedisConnectionFactory paymentRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .build())
                        .build())
                .commandTimeout(Duration.ofSeconds(3))
                .build();

        return new LettuceConnectionFactory(config, clientConfig);
    }

    /**
     * redis-payment 전용 RedisTemplate — key/value 모두 String 직렬화.
     */
    @Bean(name = "paymentRedisTemplate")
    public RedisTemplate<String, String> paymentRedisTemplate() {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(paymentRedisConnectionFactory());
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }
}
