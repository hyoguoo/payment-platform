package com.hyoguoo.paymentplatform.pg.domain;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgInboxStatus enum 단위 테스트.
 *
 * <p>PCS-2: NONE 폐기 + PENDING 도입 후 isTerminal() 계약 검증.
 */
@DisplayName("PgInboxStatus — isTerminal() 계약 + NONE 폐기 검증")
class PgInboxStatusTest {

    @Test
    @DisplayName("PENDING.isTerminal() == false")
    void isTerminal_returnsFalseForPending() {
        assertThat(PgInboxStatus.PENDING.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("IN_PROGRESS.isTerminal() == false")
    void isTerminal_returnsFalseForInProgress() {
        assertThat(PgInboxStatus.IN_PROGRESS.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PgInboxStatus.class, names = {"APPROVED", "FAILED", "QUARANTINED"})
    @DisplayName("APPROVED / FAILED / QUARANTINED.isTerminal() == true")
    void isTerminal_returnsTrueForTerminalStatuses(PgInboxStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("NONE 이 enum 상수에 존재하지 않음 — values() 배열에 NONE 없음")
    void noneIsAbsent() {
        boolean nonePresent = Arrays.stream(PgInboxStatus.values())
                .anyMatch(s -> s.name().equals("NONE"));
        assertThat(nonePresent).isFalse();
    }
}
