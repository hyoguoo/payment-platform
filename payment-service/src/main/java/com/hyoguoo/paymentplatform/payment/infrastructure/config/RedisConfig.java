package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

/**
 * 기본 Redis(redis-dedupe) 연결 설정.
 *
 * <p>IdempotencyStore 가 사용하는 redis-dedupe 인스턴스 wiring.
 * {@link StockRedisConfig} 가 redis-stock 용 별도 ConnectionFactory 를 등록하면
 * {@code @ConditionalOnMissingBean(RedisConnectionFactory.class)} 인 Spring Boot
 * autoconfig 가 default factory 를 등록하지 못한다 — 따라서 default 도 명시 등록한다.
 *
 * <p>{@code @Primary} 표시: redis 의존 어댑터 중 변수명/Qualifier 명시가 없는 곳이
 * 이 default 빈을 받도록 한다 (IdempotencyStoreRedisAdapter).
 * StockCacheRedisAdapter 는 변수명 {@code stockCacheRedisTemplate} 으로 별도 빈을 받는다.
 *
 * <p>{@code spring.data.redis.cluster.nodes} 가 비어 있으면 지금과 같은 단독 노드 연결로
 * 뜨고, 채워지면 클러스터 연결로 붙는다. 클러스터 연결은 토폴로지 자동 갱신을 켜서
 * 마스터 대수가 바뀐 뒤 첫 명령이 리다이렉트로 새 배치를 따라가게 한다.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.cluster.nodes:}")
    private String clusterNodes;

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        if (StringUtils.hasText(clusterNodes)) {
            return new LettuceConnectionFactory(
                    new RedisClusterConfiguration(splitClusterNodes(clusterNodes)),
                    buildClusterClientConfiguration());
        }

        return new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(host, port),
                buildStandaloneClientConfiguration());
    }

    private LettuceClientConfiguration buildStandaloneClientConfiguration() {
        return LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .build())
                        .build())
                .commandTimeout(Duration.ofSeconds(3))
                .build();
    }

    private LettuceClientConfiguration buildClusterClientConfiguration() {
        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
                .enableAllAdaptiveRefreshTriggers()
                .enablePeriodicRefresh(Duration.ofSeconds(30))
                .build();

        return LettuceClientConfiguration.builder()
                .clientOptions(ClusterClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .build())
                        .topologyRefreshOptions(topologyRefreshOptions)
                        .build())
                .commandTimeout(Duration.ofSeconds(3))
                .build();
    }

    private static List<String> splitClusterNodes(String nodes) {
        return Arrays.stream(nodes.split(","))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    /**
     * default StringRedisTemplate. {@code @Primary} 미부착 — RedisTemplate 타입으로 주입하는
     * 자리에서 RedisTemplate primary 와 충돌하지 않도록 한다.
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
