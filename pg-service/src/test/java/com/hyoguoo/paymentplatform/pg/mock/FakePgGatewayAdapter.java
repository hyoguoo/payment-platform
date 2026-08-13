package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.event.DuplicateApprovalDetectedEvent;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgConfirmPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.ApplicationEventPublisher;

/**
 * PgStatusLookupPort + PgConfirmPort Fake — 실제 Toss/NicePay HTTP 없이 application 계층 테스트용.
 * 두 포트를 모두 구현하는 통합 Fake.
 *
 * <p>Thread-safe: AtomicInteger + AtomicReference + ConcurrentHashMap.
 *
 * <p>사용 예:
 * <pre>
 *   FakePgGatewayAdapter fake = new FakePgGatewayAdapter();
 *   fake.setConfirmResult("order-1", successResult);
 *   fake.throwOnConfirm(PgGatewayRetryableException.of("timeout"));
 * </pre>
 *
 * <p>상태 기반 멱등 모드({@link #enableIdempotentDuplicate()}) 활성화 시, 이미 SUCCESS 처리된
 * paymentKey(orderId 기준)로 confirm() 이 재호출되면 {@link PgGatewayDuplicateHandledException}
 * 을 던져 실 벤더(Toss ALREADY_PROCESSED_PAYMENT)의 중복 승인 응답을 모사한다. 기존
 * {@link #throwOnConfirm} 일회성 주입과 공존한다 — throwOnConfirm 이 설정돼 있으면 그 예외가
 * 우선한다.
 */
public class FakePgGatewayAdapter implements PgStatusLookupPort, PgConfirmPort {

    private final Map<String, PgConfirmResult> confirmResults = new HashMap<>();
    private final Map<String, PgStatusResult> statusResults = new HashMap<>();
    private final AtomicInteger confirmCallCount = new AtomicInteger(0);
    private final AtomicInteger statusCallCount = new AtomicInteger(0);
    private final AtomicReference<RuntimeException> nextConfirmException = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextStatusException = new AtomicReference<>();

    /** 상태 기반 멱등 모드 활성 여부. */
    private final AtomicBoolean idempotentDuplicateEnabled = new AtomicBoolean(false);

    /**
     * 멱등 모드 duplicate 흡수 시 {@link DuplicateApprovalDetectedEvent} 를 발행할 대상.
     * 이벤트 발행이 필요 없는 기존 테스트(다수)를 깨지 않기 위해 nullable 로 둔다 — null 이면
     * 이벤트 발행을 생략하고 예외만 던진다(기존 동작 유지).
     */
    private ApplicationEventPublisher applicationEventPublisher;

    /**
     * 멱등 모드에서 첫 confirm 성공 처리된 orderId → 결과 기록.
     * {@code putIfAbsent} 로 첫 호출 여부를 atomic 하게 판정한다 — self-loop 재호출에서도
     * 단 한 번만 happy-path 가 성립한다.
     */
    private final ConcurrentHashMap<String, PgConfirmResult> idempotentProcessedOrders = new ConcurrentHashMap<>();

    /** 모든 벤더 타입을 지원하는 Fake (테스트 편의). */
    @Override
    public boolean supports(PgVendorType vendorType) {
        return true;
    }

    /**
     * confirm() 호출 시 설정된 예외 또는 결과를 반환한다.
     *
     * <p>멱등 모드 활성 시, 이미 처리된 orderId 재호출은 {@link PgGatewayDuplicateHandledException}
     * 을 던진다(throwOnConfirm 일회성 주입이 우선).
     *
     * @throws PgGatewayRetryableException        throwOnConfirm으로 주입된 경우
     * @throws PgGatewayNonRetryableException     throwOnConfirm으로 주입된 경우
     * @throws PgGatewayDuplicateHandledException 멱등 모드에서 이미 처리된 orderId 재호출인 경우
     * @throws IllegalStateException              orderId에 대한 결과가 설정되지 않은 경우
     */
    @Override
    public PgConfirmResult confirm(PgConfirmRequest request) {
        confirmCallCount.incrementAndGet();
        RuntimeException exception = nextConfirmException.getAndSet(null);
        if (exception != null) {
            throw exception;
        }
        if (idempotentDuplicateEnabled.get()) {
            return confirmWithIdempotentMode(request);
        }
        return confirmResultFor(request.orderId());
    }

    /**
     * getStatusByOrderId() 호출 시 설정된 예외 또는 결과를 반환한다.
     *
     * <p>멱등 모드 활성 시, 처리된 orderId 에 대해서는 명시적으로 setStatusResult 가 없어도
     * 최초 confirm 과 동일 amount 의 DONE 응답을 합성해 반환한다.
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
        PgStatusResult explicit = statusResults.get(orderId);
        if (explicit != null) {
            return explicit;
        }
        if (idempotentDuplicateEnabled.get()) {
            PgConfirmResult processed = idempotentProcessedOrders.get(orderId);
            if (processed != null) {
                return toStatusResult(processed);
            }
        }
        throw new IllegalStateException(
                "FakePgGatewayAdapter: status 결과가 설정되지 않음 — orderId=" + orderId);
    }

    // --- 멱등 모드 내부 로직 ---

    private PgConfirmResult confirmWithIdempotentMode(PgConfirmRequest request) {
        PgConfirmResult existing = idempotentProcessedOrders.get(request.orderId());
        if (existing != null) {
            throw duplicateHandledExceptionWithEvent(request);
        }

        PgConfirmResult result = confirmResultFor(request.orderId());
        PgConfirmResult winner = idempotentProcessedOrders.putIfAbsent(request.orderId(), result);
        if (winner != null) {
            throw duplicateHandledExceptionWithEvent(request);
        }
        return result;
    }

    /**
     * 실 벤더(TossPaymentGatewayStrategy) 및 main {@code FakePgGatewayStrategy} 와 동일하게
     * {@link DuplicateApprovalDetectedEvent} 발행 후 {@link PgGatewayDuplicateHandledException}
     * 을 throw 하는 이중 신호 순서를 따른다. publisher 가 주입되지 않은 경우(다수 기존 테스트)는
     * 이벤트 발행을 생략하고 예외만 던진다.
     */
    private PgGatewayDuplicateHandledException duplicateHandledExceptionWithEvent(PgConfirmRequest request) {
        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(new DuplicateApprovalDetectedEvent(
                    request.orderId(), request.amount(), request.paymentKey(),
                    "ALREADY_PROCESSED_PAYMENT", request.vendorType()));
        }
        return duplicateHandledException(request.orderId());
    }

    private PgConfirmResult confirmResultFor(String orderId) {
        PgConfirmResult result = confirmResults.get(orderId);
        if (result == null) {
            throw new IllegalStateException(
                    "FakePgGatewayAdapter: confirm 결과가 설정되지 않음 — orderId=" + orderId);
        }
        return result;
    }

    private PgGatewayDuplicateHandledException duplicateHandledException(String orderId) {
        return PgGatewayDuplicateHandledException.of(
                "ALREADY_PROCESSED_PAYMENT handled for orderId=" + orderId);
    }

    private PgStatusResult toStatusResult(PgConfirmResult confirmed) {
        return new PgStatusResult(
                confirmed.paymentKey(),
                confirmed.orderId(),
                PgPaymentStatus.DONE,
                confirmed.amount(),
                confirmed.approvedAt(),
                null,
                confirmed.approvedAtRaw()
        );
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

    /**
     * 상태 기반 멱등 모드를 활성화한다 — 이미 SUCCESS 처리된 orderId(paymentKey) 재호출 시
     * {@link PgGatewayDuplicateHandledException} 을 던져 실 벤더의 중복 승인 응답을 모사한다.
     * 기존 {@link #throwOnConfirm} 일회성 주입과 공존 가능 — throwOnConfirm 이 설정된 호출 1회는
     * 그 예외가 우선하고, 이후 호출부터 멱등 모드가 적용된다.
     */
    public void enableIdempotentDuplicate() {
        idempotentDuplicateEnabled.set(true);
    }

    /**
     * 멱등 모드 duplicate 흡수 시 {@link DuplicateApprovalDetectedEvent} 를 발행할 publisher 를
     * 설정한다. 설정하지 않으면(기본 null) 이벤트 발행 없이 예외만 던진다 — 이벤트 경로가
     * 필요 없는 기존 테스트는 호출하지 않아도 동작이 그대로 유지된다.
     */
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
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
        idempotentDuplicateEnabled.set(false);
        idempotentProcessedOrders.clear();
    }
}
