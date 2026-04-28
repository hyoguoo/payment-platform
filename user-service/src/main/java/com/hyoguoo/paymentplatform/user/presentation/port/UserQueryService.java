package com.hyoguoo.paymentplatform.user.presentation.port;

import com.hyoguoo.paymentplatform.user.presentation.dto.UserQueryResult;

/**
 * 사용자 조회 인바운드 포트 (presentation 계층 진입점).
 */
public interface UserQueryService {

    /**
     * id로 사용자를 조회한다.
     *
     * @param id 사용자 식별자
     * @return UserQueryResult
     * @throws com.hyoguoo.paymentplatform.user.exception.UserNotFoundException 사용자 미존재 시
     */
    UserQueryResult queryById(long id);
}
