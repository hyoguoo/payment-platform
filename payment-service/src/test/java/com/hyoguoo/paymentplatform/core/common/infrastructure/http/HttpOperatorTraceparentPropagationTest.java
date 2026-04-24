package com.hyoguoo.paymentplatform.core.common.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * T-E2 RED — HttpOperatorImpl(payment-service) Boot auto-config Builder 주입 검증.
 *
 * <p>ObservationRegistry 가 설정된 WebClient.Builder 를 생성자로 주입받은
 * HttpOperatorImpl 이 요청 헤더에 traceparent 를 포함하는지 검증.
 *
 * <p>RED 상태: 현재 HttpOperatorImpl 은 {@code @PostConstruct init()} 에서
 * {@code WebClient.builder().build()} 로 수동 빌드 — WebClient.Builder 생성자가 없으므로
 * 이 테스트는 컴파일 에러(RED) 상태.
 *
 * <p>GREEN 상태: WebClient.Builder 생성자 주입 + connectTimeoutMillis/readTimeoutMillis
 * 생성자 파라미터 추가 후 통과.
 */
@DisplayName("T-E2 HttpOperatorImpl(payment-service) traceparent 전파 RED")
class HttpOperatorTraceparentPropagationTest {

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("requestGet — WebClient.Builder(observation 설정) 주입 시 traceparent 헤더 포함")
    void requestGet_withObservationRegistry_shouldIncludeTraceparentHeader()
            throws InterruptedException {
        // given: ObservationRegistry + WebClient.Builder (observation 가능)
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        WebClient.Builder builder = WebClient.builder()
                .observationRegistry(observationRegistry);

        // GREEN 에서 이 생성자가 추가됨 — 현재(RED)는 이 생성자가 없어 컴파일 에러
        HttpOperatorImpl operator = new HttpOperatorImpl(builder, 3000, 5000);

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{}")
                .addHeader("Content-Type", "application/json"));

        String url = mockWebServer.url("/test").toString();

        // when
        operator.requestGet(url, java.util.Map.of(), String.class);

        // then: traceparent 헤더가 수신 요청에 존재해야 한다
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("traceparent"))
                .as("Boot auto-config Builder 주입 시 traceparent 헤더가 포함되어야 한다")
                .isNotNull();
    }

    @Test
    @DisplayName("requestPost — WebClient.Builder(observation 설정) 주입 시 traceparent 헤더 포함")
    void requestPost_withObservationRegistry_shouldIncludeTraceparentHeader()
            throws InterruptedException {
        // given
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        WebClient.Builder builder = WebClient.builder()
                .observationRegistry(observationRegistry);

        HttpOperatorImpl operator = new HttpOperatorImpl(builder, 3000, 5000);

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{}")
                .addHeader("Content-Type", "application/json"));

        String url = mockWebServer.url("/test").toString();

        // when
        operator.requestPost(url, java.util.Map.of(), "{}", String.class);

        // then
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("traceparent"))
                .as("POST 요청에도 traceparent 헤더가 포함되어야 한다")
                .isNotNull();
    }
}
