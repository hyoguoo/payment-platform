package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.core.common.util.IdempotencyKeyHasher;
import com.hyoguoo.paymentplatform.mock.FakeIdempotencyStore;
import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
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
    private FakeIdempotencyStore fakeIdempotencyStore;
    private IdempotencyKeyHasher mockIdempotencyKeyHasher;

    @BeforeEach
    void setUp() {
        mockOrderedUserUseCase = Mockito.mock(OrderedUserUseCase.class);
        mockOrderedProductUseCase = Mockito.mock(OrderedProductUseCase.class);
        mockPaymentCreateUseCase = Mockito.mock(PaymentCreateUseCase.class);
        fakeIdempotencyStore = new FakeIdempotencyStore();
        mockIdempotencyKeyHasher = Mockito.mock(IdempotencyKeyHasher.class);
        paymentCheckoutService = new PaymentCheckoutServiceImpl(
                mockOrderedUserUseCase,
                mockOrderedProductUseCase,
                mockPaymentCreateUseCase,
                fakeIdempotencyStore,
                mockIdempotencyKeyHasher
        );
    }

    @Test
    @DisplayName("결제 요청 시 사용자의 정보와 상품 정보를 조회하여 결제 이벤트를 성공적으로 생성한다.")
    void testCheckout_Success() {
        // given
        CheckoutCommand checkoutCommand = CheckoutCommand.builder()
                .userId(1L)
                .orderedProductList(List.of())
                .idempotencyKey("test-key")
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

    @Test
    @DisplayName("신규 요청 시 isDuplicate=false인 결과를 반환하고 IdempotencyStore에 저장한다.")
    void testCheckout_신규_요청_201_신규_결과_반환() {
        // given
        List<OrderedProduct> products = List.of(
                OrderedProduct.builder().productId(10L).quantity(1).build()
        );
        CheckoutCommand checkoutCommand = CheckoutCommand.builder()
                .userId(1L)
                .orderedProductList(products)
                .idempotencyKey("unique-key")
                .build();

        UserInfo mockUserInfo = UserInfo.builder().id(1L).build();
        ProductInfo mockProductInfo = ProductInfo.builder()
                .id(10L).name("P").price(BigDecimal.TEN).build();
        PaymentEvent mockPaymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L).buyerId(1L).sellerId(2L)
                .orderName("P").orderId("order-1").paymentKey("pk")
                .status(PaymentEventStatus.IN_PROGRESS).paymentOrderList(List.of())
                .allArgsBuild();

        when(mockOrderedUserUseCase.getUserInfoById(1L)).thenReturn(mockUserInfo);
        when(mockOrderedProductUseCase.getProductInfoList(products))
                .thenReturn(List.of(mockProductInfo));
        when(mockPaymentCreateUseCase.createNewPaymentEvent(any(), anyList(), anyList()))
                .thenReturn(mockPaymentEvent);

        // when
        CheckoutResult result = paymentCheckoutService.checkout(checkoutCommand);

        // then
        assertThat(result.isDuplicate()).isFalse();
        assertThat(fakeIdempotencyStore.getIfPresent("unique-key")).isPresent();
        verify(mockPaymentCreateUseCase, times(1))
                .createNewPaymentEvent(any(), anyList(), anyList());
    }

    @Test
    @DisplayName("동일 키로 두 번 요청 시 두 번째는 isDuplicate=true를 반환하고 paymentCreateUseCase는 1번만 호출된다.")
    void testCheckout_중복_요청_200_기존_결과_반환() {
        // given
        List<OrderedProduct> products = List.of(
                OrderedProduct.builder().productId(10L).quantity(1).build()
        );
        CheckoutCommand checkoutCommand = CheckoutCommand.builder()
                .userId(1L)
                .orderedProductList(products)
                .idempotencyKey("dup-key")
                .build();

        UserInfo mockUserInfo = UserInfo.builder().id(1L).build();
        ProductInfo mockProductInfo = ProductInfo.builder()
                .id(10L).name("P").price(BigDecimal.TEN).build();
        PaymentEvent mockPaymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L).buyerId(1L).sellerId(2L)
                .orderName("P").orderId("order-2").paymentKey("pk")
                .status(PaymentEventStatus.IN_PROGRESS).paymentOrderList(List.of())
                .allArgsBuild();

        when(mockOrderedUserUseCase.getUserInfoById(1L)).thenReturn(mockUserInfo);
        when(mockOrderedProductUseCase.getProductInfoList(products))
                .thenReturn(List.of(mockProductInfo));
        when(mockPaymentCreateUseCase.createNewPaymentEvent(any(), anyList(), anyList()))
                .thenReturn(mockPaymentEvent);

        // when
        paymentCheckoutService.checkout(checkoutCommand);
        CheckoutResult duplicateResult = paymentCheckoutService.checkout(checkoutCommand);

        // then
        assertThat(duplicateResult.isDuplicate()).isTrue();
        verify(mockPaymentCreateUseCase, times(1))
                .createNewPaymentEvent(any(), anyList(), anyList());
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없으면 IdempotencyKeyHasher를 호출하여 body hash를 파생한다.")
    void testCheckout_헤더_없으면_body_hash_파생() {
        // given
        List<OrderedProduct> products = List.of(
                OrderedProduct.builder().productId(10L).quantity(1).build()
        );
        CheckoutCommand checkoutCommand = CheckoutCommand.builder()
                .userId(1L)
                .orderedProductList(products)
                .idempotencyKey(null)
                .build();

        UserInfo mockUserInfo = UserInfo.builder().id(1L).build();
        ProductInfo mockProductInfo = ProductInfo.builder()
                .id(10L).name("P").price(BigDecimal.TEN).build();
        PaymentEvent mockPaymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L).buyerId(1L).sellerId(2L)
                .orderName("P").orderId("order-3").paymentKey("pk")
                .status(PaymentEventStatus.IN_PROGRESS).paymentOrderList(List.of())
                .allArgsBuild();

        when(mockOrderedUserUseCase.getUserInfoById(1L)).thenReturn(mockUserInfo);
        when(mockOrderedProductUseCase.getProductInfoList(products))
                .thenReturn(List.of(mockProductInfo));
        when(mockPaymentCreateUseCase.createNewPaymentEvent(any(), anyList(), anyList()))
                .thenReturn(mockPaymentEvent);
        when(mockIdempotencyKeyHasher.hash(anyLong(), anyList())).thenReturn("derived-hash");

        // when
        paymentCheckoutService.checkout(checkoutCommand);

        // then
        verify(mockIdempotencyKeyHasher, times(1)).hash(1L, products);
    }
}
