package com.hyoguoo.paymentplatform.user.domain;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    @DisplayName("allArgs Builder를 사용하여 User 객체를 생성한다.")
    void createUser_AllArgsBuilder() {
        // given
        Long id = 1L;
        String username = "ogu";
        String email = "ogu@platypus.com";

        // when
        User user = User.allArgsBuilder()
                .id(id)
                .username(username)
                .email(email)
                .allArgsBuild();

        // then
        Assertions.assertThat(user)
                .extracting(User::getId,
                        User::getUsername,
                        User::getEmail)
                .containsExactly(id, username, email);
    }
}
