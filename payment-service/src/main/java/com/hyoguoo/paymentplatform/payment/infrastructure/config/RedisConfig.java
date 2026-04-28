package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 기본 Redis(redis-dedupe) 연결 설정.
 *
 * <p>EventDedupeStore + IdempotencyStore 가 사용하는 redis-dedupe 인스턴스 wiring.
 * {@link StockRedisConfig} 가 redis-stock 용 별도 ConnectionFactory 를 등록하면
 * {@code @ConditionalOnMissingBean(RedisConnectionFactory.class)} 인 Spring Boot
 * autoconfig 가 default factory 를 등록하지 못한다 — 따라서 default 도 명시 등록한다.
 *
 * <p>{@code @Primary} 표시: redis 의존 어댑터 중 변수명/Qualifier 명시가 없는 곳이
 * 이 default 빈을 받도록 한다 (IdempotencyStoreRedisAdapter, EventDedupeStoreRedisAdapter).
 * StockCacheRedisAdapter 는 변수명 {@code stockCacheRedisTemplate} 으로 별도 빈을 받는다.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
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
     * default StringRedisTemplate. {@code @Primary} 미부착 — RedisTemplate 타입으로 주입하는
     * 자리에서 RedisTemplate primary 와 충돌하지 않도록 한다. EventDedupeStoreRedisAdapter 가
     * 변수명 매칭(stringRedisTemplate)으로 default 빈을 직접 받으므로 충분하다.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    @Bean
    @Primary
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }
}
