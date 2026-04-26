package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgConfirmPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TOSS 전용 PgStatusLookupPort + PgConfirmPort Fake.
 * selector 단위 테스트에서 TOSS 전략 구별용으로 사용한다.
 *
 * <p>FakePgGatewayAdapter 와의 차이: {@link #supports(PgVendorType)} 이 TOSS 만 true 반환.
 */
public class FakePgGatewayAdapterToss implements PgStatusLookupPort, PgConfirmPort {

    private final Map<String, PgConfirmResult> confirmResults = new HashMap<>();
    private final Map<String, PgStatusResult> statusResults = new HashMap<>();
    private final AtomicInteger confirmCallCount = new AtomicInteger(0);
    private final AtomicInteger statusCallCount = new AtomicInteger(0);
    private final AtomicReference<RuntimeException> nextConfirmException = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextStatusException = new AtomicReference<>();

    /** TOSS 벤더만 지원한다. */
    @Override
    public boolean supports(PgVendorType vendorType) {
        return vendorType == PgVendorType.TOSS;
    }

    @Override
    public PgConfirmResult confirm(PgConfirmRequest request) {
        confirmCallCount.incrementAndGet();
        RuntimeException exception = nextConfirmException.getAndSet(null);
        if (exception != null) {
            throw exception;
        }
        PgConfirmResult result = confirmResults.get(request.orderId());
        if (result == null) {
            throw new IllegalStateException(
                    "FakePgGatewayAdapterToss: confirm 결과가 설정되지 않음 — orderId=" + request.orderId());
        }
        return result;
    }

    @Override
    public PgStatusResult getStatusByOrderId(String orderId) {
        statusCallCount.incrementAndGet();
        RuntimeException exception = nextStatusException.getAndSet(null);
        if (exception != null) {
            throw exception;
        }
        PgStatusResult result = statusResults.get(orderId);
        if (result == null) {
            throw new IllegalStateException(
                    "FakePgGatewayAdapterToss: status 결과가 설정되지 않음 — orderId=" + orderId);
        }
        return result;
    }

    public void setConfirmResult(String orderId, PgConfirmResult result) {
        confirmResults.put(orderId, result);
    }

    public void setStatusResult(String orderId, PgStatusResult result) {
        statusResults.put(orderId, result);
    }

    public void throwOnConfirm(RuntimeException exception) {
        nextConfirmException.set(exception);
    }

    public void throwOnStatusQuery(RuntimeException exception) {
        nextStatusException.set(exception);
    }

    public int getConfirmCallCount() {
        return confirmCallCount.get();
    }

    public int getStatusCallCount() {
        return statusCallCount.get();
    }
}
