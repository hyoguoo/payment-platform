package study.paymentintegrationserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import study.paymentintegrationserver.entity.User;
import study.paymentintegrationserver.exception.UserErrorMessage;
import study.paymentintegrationserver.exception.UserException;
import study.paymentintegrationserver.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public User getById(Long id) {
        return userRepository
                .findById(id)
                .orElseThrow(() -> UserException.of(UserErrorMessage.NOT_FOUND));
    }
}
