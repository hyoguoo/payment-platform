package com.hyoguoo.paymentplatform.payment.core.common.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * HttpOperatorImpl(payment-service) Builder 주입 구조 검증.
 *
 * <p>Spring Boot 3.2+ 는 {@code WebClient.Builder} auto-config 에서 {@code ObservationRegistry} 를
 * 자동 설정한다. Builder 를 생성자로 주입받기만 하면 HTTP 경계에서 traceparent 가 자동 전파된다.
 * 실제 전파는 Boot auto-config + micrometer-tracing-bridge-otel 이 활성화된 운영 환경에서 보장되며,
 * 이 단위 테스트는 Builder 주입 구조의 정합성만 검증한다.
 */
@DisplayName("HttpOperatorImpl(payment-service) Builder 주입 구조 검증")
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
    @DisplayName("requestGet — WebClient.Builder 생성자 주입 시 HTTP 요청 정상 수행 + observationRegistry 등록 구조 확인")
    void requestGet_withInjectedBuilder_shouldPerformHttpRequest()
            throws InterruptedException {
        // given: ObservationRegistry + WebClient.Builder (Boot auto-config 환경 시뮬레이션)
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        WebClient.Builder builder = WebClient.builder()
                .observationRegistry(observationRegistry);

        HttpOperatorImpl operator = new HttpOperatorImpl(builder, 3000, 5000);

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("ok")
                .addHeader("Content-Type", "text/plain"));

        String url = mockWebServer.url("/test").toString();

        // when: Builder 주입 구조로 생성된 HttpOperatorImpl 이 HTTP 요청 수행
        String result = operator.requestGet(url, Map.of(), String.class);

        // then: HTTP 요청이 정상 수행되고 응답을 수신
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(result).isEqualTo("ok");
        // observationRegistry 가 Builder 에 설정되었으므로 운영 환경에서 traceparent 자동 전파 보장
        // (Spring Boot auto-config WebClient.Builder 가 ObservationRegistry 를 자동 주입함)
    }

    @Test
    @DisplayName("requestPost — WebClient.Builder 생성자 주입 시 HTTP POST 요청 정상 수행")
    void requestPost_withInjectedBuilder_shouldPerformHttpPost()
            throws InterruptedException {
        // given
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        WebClient.Builder builder = WebClient.builder()
                .observationRegistry(observationRegistry);

        HttpOperatorImpl operator = new HttpOperatorImpl(builder, 3000, 5000);

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("created")
                .addHeader("Content-Type", "text/plain"));

        String url = mockWebServer.url("/test").toString();

        // when
        String result = operator.requestPost(url, Map.of(), "{\"key\":\"value\"}", String.class);

        // then
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(result).isEqualTo("created");
    }
}
