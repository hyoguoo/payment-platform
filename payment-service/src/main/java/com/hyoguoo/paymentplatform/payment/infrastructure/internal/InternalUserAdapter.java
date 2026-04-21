package com.hyoguoo.paymentplatform.payment.infrastructure.internal;

import com.hyoguoo.paymentplatform.payment.application.port.out.UserPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentInfrastructureMapper;
import com.hyoguoo.paymentplatform.user.presentation.UserInternalReceiver;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 결제 서비스 내 user 컨텍스트 직접 호출 어댑터.
 * user.adapter.type=internal(기본값)인 경우에만 활성화.
 * MSA Phase 3 이후 UserHttpAdapter(http 타입)로 교체 예정.
 */
@Component
@ConditionalOnProperty(name = "user.adapter.type", havingValue = "internal", matchIfMissing = true)
@RequiredArgsConstructor
public class InternalUserAdapter implements UserPort {

    private final UserInternalReceiver userInternalReceiver;

    @Override
    public UserInfo getUserInfoById(Long userId) {
        UserInfoResponse userInfoResponse = userInternalReceiver.getUserInfoById(userId);

        return PaymentInfrastructureMapper.toUserInfo(userInfoResponse);
    }
}
