package com.hyoguoo.paymentplatform.mock;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("benchmark")
public class BenchmarkConfig {

    @Bean
    @Primary
    public HttpOperator httpOperator(
            @Value("${spring.myapp.toss-payments.fake.min-delay-millis}") int minDelayMillis,
            @Value("${spring.myapp.toss-payments.fake.max-delay-millis}") int maxDelayMillis
    ) {
        FakeTossHttpOperator op = new FakeTossHttpOperator();
        op.setDelayRange(minDelayMillis, maxDelayMillis);
        return op;
    }
}
