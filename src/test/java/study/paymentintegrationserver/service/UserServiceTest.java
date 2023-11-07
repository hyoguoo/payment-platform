package study.paymentintegrationserver.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import study.paymentintegrationserver.TestDataFactory;
import study.paymentintegrationserver.entity.User;
import study.paymentintegrationserver.exception.UserErrorMessage;
import study.paymentintegrationserver.exception.UserException;
import study.paymentintegrationserver.repository.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("유저 조회 시 유저가 존재하면 유저를 반환합니다.")
    void getUserByIdSuccess() {
        // Given
        Long userId = 1L;
        User expectedUser = TestDataFactory.generateUser();

        when(userRepository.findById(userId)).thenReturn(Optional.of(expectedUser));

        // When
        User result = userService.getById(userId);

        // Then
        assertThat(result).isEqualTo(expectedUser);
    }

    @Test
    @DisplayName("유저 조회 시 유저가 존재하지 않으면 예외를 발생시킵니다.")
    void getUserNotFound() {
        // Given
        Long userId = 1L;

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When, Then
        assertThatThrownBy(() -> userService.getById(userId))
                .isInstanceOf(UserException.class)
                .hasMessage(UserErrorMessage.NOT_FOUND.getMessage());
    }
}