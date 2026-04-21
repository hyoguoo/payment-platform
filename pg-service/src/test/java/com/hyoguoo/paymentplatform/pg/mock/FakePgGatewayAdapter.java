package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgGatewayPort;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PgGatewayPort Fake — 실제 Toss/NicePay HTTP 없이 application 계층 테스트용.
 *
 * <p>Thread-safe: AtomicInteger + AtomicReference.
 *
 * <p>사용 예:
 * <pre>
 *   FakePgGatewayAdapter fake = new FakePgGatewayAdapter();
 *   fake.setConfirmResult("order-1", successResult);
 *   fake.throwOnConfirm(PgGatewayRetryableException.of("timeout"));
 * </pre>
 */
public class FakePgGatewayAdapter implements PgGatewayPort {

    private final Map<String, PgConfirmResult> confirmResults = new HashMap<>();
    private final Map<String, PgStatusResult> statusResults = new HashMap<>();
    private final AtomicInteger confirmCallCount = new AtomicInteger(0);
    private final AtomicInteger statusCallCount = new AtomicInteger(0);
    private final AtomicReference<RuntimeException> nextConfirmException = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextStatusException = new AtomicReference<>();

    /** 모든 벤더 타입을 지원하는 Fake (테스트 편의). */
    @Override
    public boolean supports(PgVendorType vendorType) {
        return true;
    }

    /**
     * confirm() 호출 시 설정된 예외 또는 결과를 반환한다.
     *
     * @throws PgGatewayRetryableException    throwOnConfirm으로 주입된 경우
     * @throws PgGatewayNonRetryableException throwOnConfirm으로 주입된 경우
     * @throws IllegalStateException          orderId에 대한 결과가 설정되지 않은 경우
     */
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
                    "FakePgGatewayAdapter: confirm 결과가 설정되지 않음 — orderId=" + request.orderId());
        }
        return result;
    }

    /**
     * getStatusByOrderId() 호출 시 설정된 예외 또는 결과를 반환한다.
     *
     * @throws PgGatewayRetryableException    throwOnStatusQuery로 주입된 경우
     * @throws PgGatewayNonRetryableException throwOnStatusQuery로 주입된 경우
     * @throws IllegalStateException          orderId에 대한 결과가 설정되지 않은 경우
     */
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
                    "FakePgGatewayAdapter: status 결과가 설정되지 않음 — orderId=" + orderId);
        }
        return result;
    }

    // --- 설정 ---

    /** orderId별 confirm 결과를 설정한다. */
    public void setConfirmResult(String orderId, PgConfirmResult result) {
        confirmResults.put(orderId, result);
    }

    /** orderId별 상태 조회 결과를 설정한다. */
    public void setStatusResult(String orderId, PgStatusResult result) {
        statusResults.put(orderId, result);
    }

    /**
     * 다음 confirm() 한 번에 예외를 던진다.
     * retryable/nonretryable 예외 모두 주입 가능.
     */
    public void throwOnConfirm(RuntimeException exception) {
        nextConfirmException.set(exception);
    }

    /**
     * 다음 getStatusByOrderId() 한 번에 예외를 던진다.
     */
    public void throwOnStatusQuery(RuntimeException exception) {
        nextStatusException.set(exception);
    }

    // --- 검증 ---

    public int getConfirmCallCount() {
        return confirmCallCount.get();
    }

    public int getStatusCallCount() {
        return statusCallCount.get();
    }

    // --- 초기화 ---

    public void reset() {
        confirmResults.clear();
        statusResults.clear();
        confirmCallCount.set(0);
        statusCallCount.set(0);
        nextConfirmException.set(null);
        nextStatusException.set(null);
    }
}
