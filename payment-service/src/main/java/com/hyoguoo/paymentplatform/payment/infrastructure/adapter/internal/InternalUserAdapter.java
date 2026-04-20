package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.internal;

import com.hyoguoo.paymentplatform.payment.application.port.out.UserLookupPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentInfrastructureMapper;
import com.hyoguoo.paymentplatform.user.presentation.UserInternalReceiver;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 사용자 컨텍스트 내부 호출 어댑터 (Phase 1 모놀리스 경계).
 * Phase 3 이후 RemoteUserAdapter(HTTP/gRPC)로 교체 예정.
 */
@Component("userLookupAdapter")
@RequiredArgsConstructor
public class InternalUserAdapter implements UserLookupPort {

    private final UserInternalReceiver userInternalReceiver;

    @Override
    public UserInfo getUserInfoById(Long userId) {
        UserInfoResponse userInfoResponse = userInternalReceiver.getUserInfoById(userId);
        return PaymentInfrastructureMapper.toUserInfo(userInfoResponse);
    }
}
