package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.usecase.DlqReprocessUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.QuarantineResolveUseCase;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentRecoveryAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 격리 결제 안전 종결 + DLQ 재주입 관리자 액션을 각 유스케이스로 위임하는 presentation 포트 구현.
 *
 * <p>조회 전용 {@link AdminPaymentServiceImpl}(class-level {@code @Transactional(readOnly = true)})과
 * 달리 이 서비스가 위임하는 두 유스케이스는 각자 자체 TX 경계(redis 보상은 TX 밖, 도메인 전이 +
 * CAS 저장은 유스케이스 내부의 단일 {@code @Transactional})를 스스로 관리하므로, 이 façade 는
 * 트랜잭션 애노테이션을 부여하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PaymentRecoveryAdminServiceImpl implements PaymentRecoveryAdminService {

    private final QuarantineResolveUseCase quarantineResolveUseCase;
    private final DlqReprocessUseCase dlqReprocessUseCase;

    @Override
    public void resolveQuarantine(String orderId, String reason) {
        quarantineResolveUseCase.resolve(orderId, reason);
    }

    @Override
    public void reprocessDlq(String orderId) {
        dlqReprocessUseCase.reprocess(orderId);
    }
}
