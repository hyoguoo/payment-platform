package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;

/**
 * 사용자 조회 outbound port.
 * Phase 3 이후 RemoteUserAdapter(HTTP/gRPC)로 교체 예정.
 */
public interface UserLookupPort {

    UserInfo getUserInfoById(Long userId);
}
