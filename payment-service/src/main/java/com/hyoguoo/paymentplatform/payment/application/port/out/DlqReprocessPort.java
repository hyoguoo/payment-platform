package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * {@code events.confirmed.dlq} 유실 메시지 재주입 outbound port.
 * <p>
 * {@code events.confirmed.dlq} 읽기 + 원 토픽({@code events.confirmed}) 재발행을 단일 경계로
 * 캡슐화한다 — on-demand 조회이며 offset commit 은 하지 않는다(반복 노출은 원 토픽 EOS 컨슈머의
 * 멱등 체인 + 이 포트를 감싸는 유스케이스의 재주입 이력이 흡수한다).
 * <p>
 * 운영 구현체(Kafka 어댑터)는 별도 태스크(DLQ 읽기·재발행 어댑터)에서 붙는다 — 이 포트는
 * 경계 선언만 담당한다.
 */
public interface DlqReprocessPort {

    /**
     * 대상 주문의 DLQ 레코드를 원 토픽({@code events.confirmed})으로 재발행한다.
     *
     * @param orderId 재주입 대상 주문 ID
     * @throws RuntimeException 인프라 장애 또는 대상 DLQ 레코드 부재 시 전파
     */
    void reprocess(String orderId);
}
