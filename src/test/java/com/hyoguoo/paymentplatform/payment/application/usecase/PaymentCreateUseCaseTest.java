package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.core.common.service.port.UUIDProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentCreateUseCaseTest {

    private PaymentCreateUseCase paymentCreateUseCase;
    private PaymentEventRepository mockPaymentEventRepository;
    private PaymentOrderRepository mockPaymentOrderRepository;
    private UUIDProvider mockUUIDProvider;
    private LocalDateTimeProvider mockLocalDateTimeProvider;

    @BeforeEach
    void setUp() {
        mockPaymentEventRepository = Mockito.mock(PaymentEventRepository.class);
        mockPaymentOrderRepository = Mockito.mock(PaymentOrderRepository.class);
        mockUUIDProvider = Mockito.mock(UUIDProvider.class);
        mockLocalDateTimeProvider = Mockito.mock(LocalDateTimeProvider.class);

        paymentCreateUseCase = new PaymentCreateUseCase(
                mockPaymentEventRepository,
                mockPaymentOrderRepository,
                mockUUIDProvider,
                mockLocalDateTimeProvider
        );
    }

    @Test
    @DisplayName("User와 Product를 받아 새로운 PaymentEvent와 관련된 PaymentOrder 리스트를 성공적으로 생성하고, 저장 메서드가 호출된다.")
    void testCreateNewPaymentEvent_Success() {
        // given
        UserInfo userInfo = UserInfo.builder()
                .id(1L)
                .build();

        ProductInfo productInfo1 = ProductInfo.builder()
                .id(1L)
                .name("Product 1")
                .price(new BigDecimal("10000"))
                .stock(10)
                .sellerId(2L)
                .build();

        ProductInfo productInfo2 = ProductInfo.builder()
                .id(2L)
                .name("Product 2")
                .price(new BigDecimal("20000"))
                .stock(5)
                .sellerId(2L)
                .build();

        OrderedProduct orderedProduct1 = OrderedProduct.builder()
                .productId(1L)
                .quantity(1)
                .build();

        OrderedProduct orderedProduct2 = OrderedProduct.builder()
                .productId(2L)
                .quantity(2)
                .build();

        List<OrderedProduct> orderedProductList = List.of(orderedProduct1, orderedProduct2);
        List<ProductInfo> productInfoList = List.of(productInfo1, productInfo2);

        String generatedUUID = "1234-1234-1234-1234";
        LocalDateTime now = LocalDateTime.of(2024, 1, 1, 0, 0);

        PaymentEvent mockSavedPaymentEvent = PaymentEvent.allArgsBuilder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("Order")
                .orderId(generatedUUID)
                .paymentKey("paymentKey")
                .status(PaymentEventStatus.READY)
                .paymentOrderList(new ArrayList<>(0))
                .lastStatusChangedAt(now)
                .allArgsBuild();

        // when
        when(mockUUIDProvider.generateUUID()).thenReturn(generatedUUID);
        when(mockLocalDateTimeProvider.now()).thenReturn(now);
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(mockSavedPaymentEvent);
        when(mockPaymentOrderRepository.saveAll(Mockito.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentEvent result = paymentCreateUseCase.createNewPaymentEvent(
                userInfo,
                orderedProductList,
                productInfoList
        );

        // then
        assertThat(result).isEqualTo(mockSavedPaymentEvent);
        assertThat(result.getPaymentOrderList()).hasSize(2);

        verify(mockUUIDProvider, times(1)).generateUUID();
        verify(mockPaymentEventRepository, times(1)).saveOrUpdate(any(PaymentEvent.class));
        verify(mockPaymentOrderRepository, times(1)).saveAll(Mockito.any());
    }
}
