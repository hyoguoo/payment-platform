package com.hyoguoo.paymentplatform.mock;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("benchmark")
public class BenchmarkConfig {

    @Bean
    @Primary
    public HttpOperator httpOperator() {
        return new FakeTossHttpOperator();
    }
}
