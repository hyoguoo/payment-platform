package com.hyoguoo.paymentplatform.pg.application.event;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import java.math.BigDecimal;

/**
 * Toss / NicePay 전략이 중복 승인 응답을 감지했을 때 발행하는 애플리케이션 이벤트.
 *
 * <p>전략이 DuplicateApprovalHandler 를 직접 호출하면 PgStatusLookupPort 구현체(Toss/NicePay)
 * → DuplicateApprovalHandler → PgStatusLookupPort 의 cycle 이 만들어진다. 전략은 이 이벤트만
 * 발행하고 핸들러는 {@code @EventListener} 로 수신해 처리해 cycle 을 끊는다.
 *
 * <p>vendorType 은 DuplicateApprovalHandler 가 PgStatusLookupStrategySelector 로
 * 올바른 벤더 전략을 선택하기 위해 포함한다.
 *
 * @param orderId    주문 ID
 * @param amount     결제 금액
 * @param paymentKey 결제 키 (Toss paymentKey / NicePay tid)
 * @param reasonCode 중복 승인 에러 코드 (예: ALREADY_PROCESSED_PAYMENT, 2201)
 * @param vendorType PG 벤더 구분 — selector 분기에 사용
 */
public record DuplicateApprovalDetectedEvent(
        String orderId,
        BigDecimal amount,
        String paymentKey,
        String reasonCode,
        PgVendorType vendorType
) {}
