package com.hyoguoo.paymentplatform.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentEventRepositoryImplTest extends BaseIntegrationTest {

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Test
    @DisplayName("findReadyPaymentsOlderThan(Instant) — cutoff 이전 READY 결제만 반환한다.")
    void findReadyPaymentsOlderThan_withInstantCutoff_returnsOnlyOlderPayments() {
        // given
        Instant baseTime = Instant.parse("2026-01-01T12:00:00Z");
        Instant cutoff = Instant.parse("2026-01-01T12:30:00Z");

        // cutoff 이전에 생성된 READY 결제 (조회 대상)
        PaymentEvent olderEvent = paymentEventRepository.saveOrUpdate(
                PaymentEvent.create(
                        UserInfo.builder().id(1L).build(),
                        List.of(ProductInfo.builder()
                                .id(1L)
                                .name("상품1")
                                .price(BigDecimal.valueOf(10000))
                                .stock(10)
                                .sellerId(2L)
                                .build()),
                        "older-order-" + System.nanoTime(),
                        baseTime,  // cutoff 이전
                        PaymentGatewayType.TOSS
                )
        );

        // cutoff 이후에 생성된 READY 결제 (조회 제외)
        PaymentEvent newerEvent = paymentEventRepository.saveOrUpdate(
                PaymentEvent.create(
                        UserInfo.builder().id(1L).build(),
                        List.of(ProductInfo.builder()
                                .id(1L)
                                .name("상품2")
                                .price(BigDecimal.valueOf(5000))
                                .stock(5)
                                .sellerId(2L)
                                .build()),
                        "newer-order-" + System.nanoTime(),
                        cutoff.plusSeconds(300),  // cutoff 이후
                        PaymentGatewayType.TOSS
                )
        );

        // when
        List<PaymentEvent> result = paymentEventRepository.findReadyPaymentsOlderThan(cutoff);

        // then
        List<Long> resultIds = result.stream().map(PaymentEvent::getId).toList();
        assertThat(resultIds).contains(olderEvent.getId());
        assertThat(resultIds).doesNotContain(newerEvent.getId());
    }
}
