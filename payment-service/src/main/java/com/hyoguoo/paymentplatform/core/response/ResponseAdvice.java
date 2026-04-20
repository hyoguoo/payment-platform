package com.hyoguoo.paymentplatform.core.response;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class ResponseAdvice implements ResponseBodyAdvice<Object> {

    @Value("${springdoc.api-docs.path}")
    private String apiDocsPath;

    @Override
    public boolean supports(
            @Nullable MethodParameter returnType,
            @Nullable Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            @Nullable MethodParameter returnType,
            @Nullable MediaType selectedContentType,
            @Nullable Class<? extends HttpMessageConverter<?>> selectedConverterType,
            @Nullable ServerHttpRequest request,
            @Nullable ServerHttpResponse response
    ) {
        if (body instanceof ErrorResponse errorResponse) {
            return ResponseUtil.error(errorResponse);
        }

        if (request != null && request.getURI().getPath().startsWith("/actuator")) {
            return body;
        }

        if (request != null && request.getURI().getPath().contains(apiDocsPath)) {
            return body;
        }

        return ResponseUtil.success(body);
    }
}

