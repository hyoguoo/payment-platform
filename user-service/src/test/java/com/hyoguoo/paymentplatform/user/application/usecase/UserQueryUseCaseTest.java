package com.hyoguoo.paymentplatform.user.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.user.application.port.out.UserRepository;
import com.hyoguoo.paymentplatform.user.domain.User;
import com.hyoguoo.paymentplatform.user.exception.UserNotFoundException;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserQueryResult;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("UserQueryUseCase 테스트")
@ExtendWith(MockitoExtension.class)
class UserQueryUseCaseTest {

    @InjectMocks
    private UserQueryUseCase sut;

    @Mock
    private UserRepository userRepository;

    @Test
    @DisplayName("queryById: 사용자가 존재하면 UserQueryResult를 반환한다")
    void queryById_whenUserExists_returnsUserQueryResult() {
        // given
        long userId = 1L;
        LocalDateTime createdAt = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
        User user = User.allArgsBuilder()
                .id(userId)
                .email("test@example.com")
                .createdAt(createdAt)
                .allArgsBuild();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));

        // when
        UserQueryResult result = sut.queryById(userId);

        // then
        assertThat(result.id()).isEqualTo(userId);
        assertThat(result.email()).isEqualTo("test@example.com");
        assertThat(result.createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("queryById: 사용자가 존재하지 않으면 UserNotFoundException을 던진다")
    void queryById_whenUserNotFound_throwsUserNotFoundException() {
        // given
        long userId = 1L;
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.queryById(userId))
                .isInstanceOf(UserNotFoundException.class);
    }
}
