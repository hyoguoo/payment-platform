package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * pg inbox·outbox 즉시 처리 워커 수 기본값 검증.
 *
 * <p>워커가 작업을 직접 처리하므로(#166) 워커 수가 동시 처리 상한이다. capacity 160/s 측정에서 inbox 32개는 약 147/s,
 * outbox 1개는 약 145/s에서 막혔고 inbox 64 + outbox 8은 160/s를 따라갔다(EXPERIMENTS 2026-11-16).
 * 환경 변수 없이 띄웠을 때의 값을 application.yml 에서 읽어 확인한다.
 */
@DisplayName("pg 워커 수 기본값")
class PgWorkerCountDefaultTest {

    private AbstractEnvironment environment;

    @BeforeEach
    void setUp() throws IOException {
        // 시스템 환경 변수·프로퍼티 소스가 없는 환경이라 ${KEY:default} 의 기본값 분기만 읽힌다.
        environment = new AbstractEnvironment() {
        };
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        documents.stream()
                .filter(document -> document.getProperty("spring.config.activate.on-profile") == null)
                .forEach(environment.getPropertySources()::addLast);
    }

    @Test
    @DisplayName("환경 변수를 주지 않으면 application.yml 의 inbox 워커 수는 64다")
    void inboxWorkerCount_defaultsToSixtyFour() {
        assertThat(environment.getProperty("pg.inbox.channel.worker-count", Integer.class)).isEqualTo(64);
    }

    @Test
    @DisplayName("환경 변수를 주지 않으면 application.yml 의 outbox 워커 수는 8이다")
    void outboxWorkerCount_defaultsToEight() {
        assertThat(environment.getProperty("pg.outbox.channel.worker-count", Integer.class)).isEqualTo(8);
    }
}
