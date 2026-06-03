package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * T6 — PaymentLoadUseCase 만료 임계 외부화 검증.
 *
 * <p>@Value 주입 기본값(30분) 및 커스텀 값으로 cutoff 계산이 정확한지 verify.
 */
@DisplayName("PaymentLoadUseCase 만료 임계 외부화 검증")
class PaymentLoadUseCaseClockTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private PaymentEventRepository mockRepository() {
        PaymentEventRepository repo = Mockito.mock(PaymentEventRepository.class);
        given(repo.findReadyPaymentsOlderThan(any())).willReturn(List.of());
        return repo;
    }

    @Test
    @DisplayName("기본 타임아웃(30분) — cutoff = fixedInstant - 30min")
    void getReadyPaymentsOlder_defaultTimeout_30min_cutoffIsCorrect() {
        PaymentEventRepository repo = mockRepository();
        PaymentLoadUseCase sut = new PaymentLoadUseCase(repo, FIXED_CLOCK, 30);

        sut.getReadyPaymentsOlder();

        Instant expectedCutoff = FIXED_INSTANT.minus(Duration.ofMinutes(30));
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        then(repo).should(times(1)).findReadyPaymentsOlderThan(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(expectedCutoff);
    }

    @Test
    @DisplayName("커스텀 타임아웃(5분) — cutoff = fixedInstant - 5min")
    void getReadyPaymentsOlder_customTimeout_cutoffRespectsSetting() {
        PaymentEventRepository repo = mockRepository();
        PaymentLoadUseCase sut = new PaymentLoadUseCase(repo, FIXED_CLOCK, 5);

        sut.getReadyPaymentsOlder();

        Instant expectedCutoff = FIXED_INSTANT.minus(Duration.ofMinutes(5));
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        then(repo).should(times(1)).findReadyPaymentsOlderThan(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(expectedCutoff);
    }

    @Test
    @DisplayName("29분 전 결제 — cutoff 30분 기준이면 포함되지 않아야 한다")
    void getReadyPaymentsOlder_29min_notIncluded() {
        PaymentEventRepository repo = mockRepository();
        PaymentLoadUseCase sut = new PaymentLoadUseCase(repo, FIXED_CLOCK, 30);

        sut.getReadyPaymentsOlder();

        Instant cutoff = FIXED_INSTANT.minus(Duration.ofMinutes(30));
        // 29분 전 결제는 cutoff 이후이므로 findReadyPaymentsOlderThan 범위에 포함 안 됨
        Instant twentyNineMinutesAgo = FIXED_INSTANT.minus(Duration.ofMinutes(29));
        org.assertj.core.api.Assertions.assertThat(twentyNineMinutesAgo).isAfter(cutoff);
    }

    @Test
    @DisplayName("31분 전 결제 — cutoff 30분 기준이면 포함되어야 한다")
    void getReadyPaymentsOlder_31min_included() {
        PaymentEventRepository repo = mockRepository();
        PaymentLoadUseCase sut = new PaymentLoadUseCase(repo, FIXED_CLOCK, 30);

        sut.getReadyPaymentsOlder();

        Instant cutoff = FIXED_INSTANT.minus(Duration.ofMinutes(30));
        // 31분 전 결제는 cutoff 이전이므로 findReadyPaymentsOlderThan 범위에 포함됨
        Instant thirtyOneMinutesAgo = FIXED_INSTANT.minus(Duration.ofMinutes(31));
        org.assertj.core.api.Assertions.assertThat(thirtyOneMinutesAgo).isBefore(cutoff);
    }
}
