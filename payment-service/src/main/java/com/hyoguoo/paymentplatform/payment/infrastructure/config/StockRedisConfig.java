package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * 재고 선차감 캐시 전용 Redis(redis-stock) 연결 설정.
 *
 * <p>keyspace: {@code stock:{productId}}.
 * {@link RedisConfig} 의 default redis-dedupe 와 별개의 인스턴스를 사용한다.
 * StockCacheRedisAdapter 가 {@code stockCacheRedisTemplate} 변수명으로 이 빈을 주입받는다.
 *
 * <p>{@code @Primary} 미부착 — default 빈은 {@link RedisConfig} 가 책임진다.
 *
 * <p>{@code payment.cache.stock-redis.cluster-nodes} 가 비어 있으면 지금과 같은 단독
 * 노드 연결로 뜨고, 채워지면 클러스터 연결로 붙는다. 클러스터 연결은 토폴로지 자동
 * 갱신을 켜서 마스터 대수가 바뀐 뒤 첫 명령이 리다이렉트로 새 배치를 따라가게 한다.
 */
@Configuration
public class StockRedisConfig {

    @Value("${payment.cache.stock-redis.host:localhost}")
    private String host;

    @Value("${payment.cache.stock-redis.port:6380}")
    private int port;

    @Value("${payment.cache.stock-redis.cluster-nodes:}")
    private String clusterNodes;

    @Bean(name = "stockCacheRedisConnectionFactory")
    public RedisConnectionFactory stockCacheRedisConnectionFactory() {
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

    @Bean(name = "stockCacheRedisTemplate")
    public StringRedisTemplate stockCacheRedisTemplate(
            @Qualifier("stockCacheRedisConnectionFactory") RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
