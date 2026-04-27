package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import com.hyoguoo.paymentplatform.payment.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.UserPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.exception.UserNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.UserServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.UserResponse;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * user-service HTTP 어댑터.
 * UserPort 구현체로, {@code user.adapter.type=http} 프로파일에서 활성화된다.
 * Resilience4j {@code @CircuitBreaker} 는 Phase 4 에서 이 클래스 내부 메서드에 설치한다 — port 인터페이스는 회복성 어노테이션으로부터 격리된 채로 유지한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "user.adapter.type", havingValue = "http")
public class UserHttpAdapter implements UserPort {

    private static final String USERS_PATH = "/api/v1/users/";

    private final HttpOperator httpOperator;
    private final String userServiceBaseUrl;

    public UserHttpAdapter(
            HttpOperator httpOperator,
            @Value("${user-service.base-url:http://localhost:8084}") String userServiceBaseUrl) {
        this.httpOperator = httpOperator;
        this.userServiceBaseUrl = userServiceBaseUrl;
    }

    @Override
    public UserInfo getUserInfoById(Long userId) {
        return fetchUserById(userId);
    }

    private UserInfo fetchUserById(Long userId) {
        UserResponse response = callGet(
                userServiceBaseUrl + USERS_PATH + userId,
                UserResponse.class
        );
        return toUserInfo(response);
    }

    private <T> T callGet(String url, Class<T> responseType) {
        try {
            return httpOperator.requestGet(url, Map.of(), responseType);
        } catch (WebClientResponseException e) {
            throw mapResponseException(e);
        } catch (WebClientRequestException e) {
            throw UserServiceRetryableException.of(PaymentErrorCode.USER_SERVICE_UNAVAILABLE);
        }
    }

    private RuntimeException mapResponseException(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == HttpStatus.NOT_FOUND.value()) {
            LogFmt.warn(log, LogDomain.USER, EventType.USER_SERVICE_NOT_FOUND,
                    () -> "status=404 body=" + e.getResponseBodyAsString());
            return UserNotFoundException.of(PaymentErrorCode.USER_NOT_FOUND);
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value()
                || status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            LogFmt.warn(log, LogDomain.USER, EventType.USER_SERVICE_RETRYABLE,
                    () -> "status=" + status);
            return UserServiceRetryableException.of(PaymentErrorCode.USER_SERVICE_UNAVAILABLE);
        }
        LogFmt.warn(log, LogDomain.USER, EventType.USER_SERVICE_UNEXPECTED,
                () -> "status=" + status + " body=" + e.getResponseBodyAsString());
        return new IllegalStateException(
                "user-service 오류 status=" + status + " body=" + e.getResponseBodyAsString()
        );
    }

    private static UserInfo toUserInfo(UserResponse response) {
        return UserInfo.builder()
                .id(response.id())
                .build();
    }

}
