package study.paymentintegrationserver.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import study.paymentintegrationserver.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

}
