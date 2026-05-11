package com.hyoguoo.paymentplatform.payment.exception.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyoguoo.paymentplatform.payment.exception.ProductServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.UserServiceRetryableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(PaymentExceptionHandlerTest.StubController.class)
@Import({PaymentExceptionHandler.class, PaymentExceptionHandlerTest.StubController.class})
class PaymentExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("ProductServiceRetryableException 발생 시 503 + Retry-After:5 + E03031 body를 반환한다.")
    void handleProductServiceRetryable_returns503WithRetryAfter() throws Exception {
        mockMvc.perform(get("/test/product-retryable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.error.code").value("E03031"));
    }

    @Test
    @DisplayName("UserServiceRetryableException 발생 시 503 + Retry-After:5 + E03032 body를 반환한다.")
    void handleUserServiceRetryable_returns503WithRetryAfter() throws Exception {
        mockMvc.perform(get("/test/user-retryable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.error.code").value("E03032"));
    }

    @RestController
    static class StubController {

        @GetMapping("/test/product-retryable")
        public void throwProductRetryable() {
            throw ProductServiceRetryableException.of(PaymentErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }

        @GetMapping("/test/user-retryable")
        public void throwUserRetryable() {
            throw UserServiceRetryableException.of(PaymentErrorCode.USER_SERVICE_UNAVAILABLE);
        }
    }
}
