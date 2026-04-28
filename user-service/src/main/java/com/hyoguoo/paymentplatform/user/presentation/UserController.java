package com.hyoguoo.paymentplatform.user.presentation;

import com.hyoguoo.paymentplatform.user.core.common.log.EventType;
import com.hyoguoo.paymentplatform.user.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.user.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserResponse;
import com.hyoguoo.paymentplatform.user.presentation.port.UserQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 REST 컨트롤러.
 * GET /api/v1/users/{id} → UserQueryService.queryById 위임.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserQueryService userQueryService;

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable long id) {
        LogFmt.info(log, LogDomain.USER, EventType.USER_QUERY_RECEIVED,
                () -> "id=" + id);
        UserResponse response = UserResponse.from(userQueryService.queryById(id));
        return ResponseEntity.ok(response);
    }
}
