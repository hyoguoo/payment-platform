package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.usecase.OrderedProductUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.OrderedUserUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCreateUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentCheckoutServiceImplTest {

    private PaymentCheckoutServiceImpl paymentCheckoutService;
    private OrderedUserUseCase mockOrderedUserUseCase;
    private OrderedProductUseCase mockOrderedProductUseCase;
    private PaymentCreateUseCase mockPaymentCreateUseCase;

    @BeforeEach
    void setUp() {
        mockOrderedUserUseCase = Mockito.mock(OrderedUserUseCase.class);
        mockOrderedProductUseCase = Mockito.mock(OrderedProductUseCase.class);
        mockPaymentCreateUseCase = Mockito.mock(PaymentCreateUseCase.class);
        paymentCheckoutService = new PaymentCheckoutServiceImpl(
                mockOrderedUserUseCase,
                mockOrderedProductUseCase,
                mockPaymentCreateUseCase
        );
    }

    @Test
    @DisplayName("결제 요청 시 사용자의 정보와 상품 정보를 조회하여 결제 이벤트를 성공적으로 생성한다.")
    void testCheckout_Success() {
        // given
        CheckoutCommand checkoutCommand = CheckoutCommand.builder()
                .userId(1L)
                .orderedProductList(List.of())
                .build();

        UserInfo mockUserInfo = UserInfo.builder()
                .id(1L)
                .build();
        ProductInfo mockProductInfo = ProductInfo.builder()
                .id(1L)
                .name("Product 1")
                .price(new BigDecimal("10000"))
                .build();
        PaymentEvent mockPaymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .sellerId(2L)
                .orderName("Product 1")
                .orderId("order123")
                .paymentKey("paymentKey")
                .status(PaymentEventStatus.IN_PROGRESS)
                .paymentOrderList(List.of())
                .allArgsBuild();

        // when
        when(mockOrderedUserUseCase.getUserInfoById(checkoutCommand.getUserId()))
                .thenReturn(mockUserInfo);
        when(mockOrderedProductUseCase.getProductInfoList(checkoutCommand.getOrderedProductList()))
                .thenReturn(List.of(mockProductInfo));
        when(mockPaymentCreateUseCase.createNewPaymentEvent(
                any(UserInfo.class), anyList(), anyList()))
                .thenReturn(mockPaymentEvent);

        CheckoutResult result = paymentCheckoutService.checkout(checkoutCommand);

        // then
        assertThat(result.getOrderId()).isEqualTo(mockPaymentEvent.getOrderId());
        assertThat(result.getTotalAmount()).isEqualTo(mockPaymentEvent.getTotalAmount());

        verify(mockOrderedUserUseCase, times(1))
                .getUserInfoById(checkoutCommand.getUserId());
        verify(mockOrderedProductUseCase, times(1))
                .getProductInfoList(checkoutCommand.getOrderedProductList());
        verify(mockPaymentCreateUseCase, times(1))
                .createNewPaymentEvent(any(UserInfo.class), anyList(), anyList());
    }
}
