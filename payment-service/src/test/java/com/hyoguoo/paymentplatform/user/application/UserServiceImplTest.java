package com.hyoguoo.paymentplatform.user.application;

import com.hyoguoo.paymentplatform.mock.FakeUserRepository;
import com.hyoguoo.paymentplatform.user.domain.User;
import com.hyoguoo.paymentplatform.user.exception.UserFoundException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserServiceImplTest {

    private UserServiceImpl userService;
    private FakeUserRepository fakeUserRepository;

    @BeforeEach
    void setUp() {
        fakeUserRepository = new FakeUserRepository();
        userService = new UserServiceImpl(fakeUserRepository);
    }

    @Test
    @DisplayName("유저 조회 시 유저가 존재하면 유저를 반환한다.")
    void getById_UserFound() {
        // given
        Long savedId = 1L;
        User user = User.allArgsBuilder()
                .id(savedId)
                .username("ogu")
                .email("ogu@platypus.com")
                .allArgsBuild();
        fakeUserRepository.saveOrUpdate(user);

        // when
        User foundUser = userService.getById(savedId);

        // then
        Assertions.assertThat(foundUser.getId()).isEqualTo(savedId);
    }

    @Test
    @DisplayName("유저 조회 시 유저가 존재하지 않으면 예외를 던진다.")
    void getById_UserNotFound() {
        // given
        Long savedId = 1L;
        User user = User.allArgsBuilder()
                .id(savedId)
                .username("ogu")
                .email("ogu@platypus.com")
                .allArgsBuild();
        fakeUserRepository.saveOrUpdate(user);

        Long notFoundId = 2L;

        // when & then
        Assertions.assertThatThrownBy(() -> userService.getById(notFoundId))
                .isInstanceOf(UserFoundException.class);
    }
}
