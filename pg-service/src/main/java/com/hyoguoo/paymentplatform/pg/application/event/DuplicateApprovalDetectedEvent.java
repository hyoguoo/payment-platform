package com.hyoguoo.paymentplatform.pg.application.event;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import java.math.BigDecimal;

/**
 * K13: Toss/NicePay 전략이 중복 승인 응답을 감지했을 때 발행하는 애플리케이션 이벤트.
 *
 * <p>목적: TossPaymentGatewayStrategy/NicepayPaymentGatewayStrategy → DuplicateApprovalHandler
 * 직접 의존을 제거하여 순환 의존 단절.
 *
 * <p>cycle 구조:
 * TossPaymentGatewayStrategy (implements PgStatusLookupPort)
 *   → DuplicateApprovalHandler
 *   → PgStatusLookupPort (← Spring이 Toss 빈 주입)
 *   → cycle
 *
 * <p>해결: 전략이 DuplicateApprovalHandler를 직접 호출하는 대신
 * {@code ApplicationEventPublisher.publishEvent(DuplicateApprovalDetectedEvent)}를 발행하고,
 * DuplicateApprovalHandler가 {@code @EventListener}로 수신하여 처리.
 *
 * <p>K14: vendorType 추가 — DuplicateApprovalHandler 가 PgStatusLookupStrategySelector 로
 * 올바른 벤더 전략을 선택할 수 있도록 이벤트에 포함.
 *
 * @param orderId    주문 ID
 * @param amount     결제 금액
 * @param paymentKey 결제 키 (Toss paymentKey / NicePay tid)
 * @param reasonCode 중복 승인 에러 코드 (예: ALREADY_PROCESSED_PAYMENT, 2201)
 * @param vendorType PG 벤더 구분 (K14: selector 분기에 사용)
 */
public record DuplicateApprovalDetectedEvent(
        String orderId,
        BigDecimal amount,
        String paymentKey,
        String reasonCode,
        PgVendorType vendorType
) {}
