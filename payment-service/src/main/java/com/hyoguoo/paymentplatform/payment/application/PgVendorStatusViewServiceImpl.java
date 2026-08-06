package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusInfo;
import com.hyoguoo.paymentplatform.payment.application.port.out.PgVendorStatusPort;
import com.hyoguoo.paymentplatform.payment.presentation.port.PgVendorStatusViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 관리자 화면의 벤더 상태 조회 진입점 — presentation 입력 포트({@link PgVendorStatusViewService})
 * 구현.
 *
 * <p>{@link PgVendorStatusPort} 는 조회 실패를 예외로 던지지 않고 {@code UNKNOWN} 판정으로 이미
 * 흡수하므로(Task 2), 이 계층은 추가 흡수 로직 없이 결과를 그대로 넘긴다. 이 서비스가 존재하는
 * 이유는 예외 흡수가 아니라, presentation 이 출력 포트({@link PgVendorStatusPort})를 직접
 * 호출하지 않는다는 layer 규칙을 지키기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class PgVendorStatusViewServiceImpl implements PgVendorStatusViewService {

    private final PgVendorStatusPort pgVendorStatusPort;

    @Override
    public PgVendorStatusInfo getVendorStatus(String orderId) {
        return pgVendorStatusPort.lookup(orderId);
    }
}
