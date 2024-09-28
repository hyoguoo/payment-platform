package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.port.UserProvider;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OrderedUserUseCaseTest {

    private OrderedUserUseCase orderedUserUseCase;
    private UserProvider mockUserProvider;

    @BeforeEach
    void setUp() {
        mockUserProvider = Mockito.mock(UserProvider.class);
        orderedUserUseCase = new OrderedUserUseCase(mockUserProvider);
    }

    @Test
    @DisplayName("getUserInfoById 호출 시 UserProvider의 getUserInfoById 메서드가 한 번 호출되고 올바른 값을 반환한다.")
    void testGetUserInfoById() {
        // given
        Long userId = 1L;
        UserInfo expectedUserInfo = UserInfo.builder()
                .id(userId)
                .build();

        // when
        when(mockUserProvider.getUserInfoById(userId)).thenReturn(expectedUserInfo);
        UserInfo actualUserInfo = orderedUserUseCase.getUserInfoById(userId);

        // then
        assertThat(actualUserInfo).isEqualTo(expectedUserInfo);
        verify(mockUserProvider, times(1)).getUserInfoById(userId);
    }
}
