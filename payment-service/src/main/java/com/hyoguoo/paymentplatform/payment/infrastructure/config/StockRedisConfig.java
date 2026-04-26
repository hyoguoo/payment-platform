package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 재고 선차감 캐시 전용 Redis(redis-stock) 연결 설정.
 *
 * <p>keyspace: {@code stock:{productId}}.
 * {@link RedisConfig} 의 default redis-dedupe 와 별개의 인스턴스를 사용한다.
 * StockCacheRedisAdapter 가 {@code stockCacheRedisTemplate} 변수명으로 이 빈을 주입받는다.
 *
 * <p>{@code @Primary} 미부착 — default 빈은 {@link RedisConfig} 가 책임진다.
 */
@Configuration
public class StockRedisConfig {

    @Value("${payment.cache.stock-redis.host:localhost}")
    private String host;

    @Value("${payment.cache.stock-redis.port:6380}")
    private int port;

    @Bean(name = "stockCacheRedisConnectionFactory")
    public RedisConnectionFactory stockCacheRedisConnectionFactory() {
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

    @Bean(name = "stockCacheRedisTemplate")
    public StringRedisTemplate stockCacheRedisTemplate(
            @Qualifier("stockCacheRedisConnectionFactory") RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
