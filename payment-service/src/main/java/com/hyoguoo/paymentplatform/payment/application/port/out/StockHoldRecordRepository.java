package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;
import java.util.Optional;

/**
 * 재고 선차감 기록(상품 단위) outbound port.
 *
 * <p>캐시 되돌리기가 실패해도 게이트에 남는 차감을 청소하기 위한 결제 DB 측 근거다.
 * 기록은 차감보다 먼저 적혀, 차감 직전에 죽어도 회수 대상으로 남는다. 운영 구현체는
 * JPA 어댑터({@code StockHoldRecordRepositoryImpl}).
 */
public interface StockHoldRecordRepository {

    /**
     * 이번 사이클 진입을 기록한다 — 주문번호·상품번호 조합의 행이 없으면 잡음 상태로 새로 만들고,
     * 되돌림 상태면 잡음으로 재오픈한다. 확정 상태는 건드리지 않는다.
     *
     * <p>재오픈이 필요한 이유는 거절 후 되돌리기가 재시도를 정상 경로로 만들기 때문이다 — 닫힌
     * 채로 두면 재시도가 만든 새 차감이 회수 대상에서 영영 빠진다. 확정을 건드리지 않는 이유는
     * 반대다 — 완료된 결제에 재요청이 오는 것은 더블클릭만으로도 생기는데, 그때 확정을 잡음으로
     * 되돌리면 이미 팔린 건이 회수 대상이 되어 게이트에서만 재고가 되살아난다.
     *
     * @param orderId      결제 주문 ID
     * @param paymentOrder 대상 상품 ({@link PaymentOrder#getProductId()} / {@link PaymentOrder#getQuantity()})
     * @return 이번 호출로 그 행이 속하게 된 사이클을 식별하는 값. 확정 상태라 건드리지 않은
     *         경우에도 그 행의 현재 사이클 식별 값을 그대로 반환한다.
     */
    String openHold(String orderId, PaymentOrder paymentOrder);

    /**
     * 주문번호·상품번호 조합의 현재 상태와 사이클 식별 값을 조회한다.
     *
     * @param orderId      결제 주문 ID
     * @param paymentOrder 대상 상품
     * @return 행이 있으면 그 스냅샷, 없으면 empty
     */
    Optional<StockHoldRecordSnapshot> findSnapshot(String orderId, PaymentOrder paymentOrder);

    /**
     * 조회 시점 사이클 식별 값이 지금도 일치할 때만 되돌림으로 닫는다.
     *
     * <p>캐시 되돌리기와 기록 닫기 사이에 새 차감이 들어와 행이 잡음으로 다시 열렸다면 사이클
     * 식별 값이 달라져 있고, 그 상태에서 뒤늦은 닫기가 반영되면 새 차감이 회수 대상에서 사라진다.
     * 값이 일치하지 않으면 아무 것도 바꾸지 않고 실패를 반환해 그 상황을 막는다.
     *
     * @param orderId      결제 주문 ID
     * @param paymentOrder 대상 상품
     * @param cycleToken   되돌리기를 시작하기 직전 {@link #findSnapshot} 또는 {@link #openHold}로 확인한 사이클 식별 값
     * @return 반영됐으면 true, 그 사이 값이 달라져 반영되지 않았으면 false
     */
    boolean closeAsReverted(String orderId, PaymentOrder paymentOrder, String cycleToken);

    /**
     * 그 주문의 잡음 상태 기록을 모두 확정으로 바꾼다.
     *
     * <p>벤더 승인 결과를 반영해 결제를 완료로 전이시키는 트랜잭션 안에서 호출해야 한다 — 별도
     * 트랜잭션으로 갈라지면 종결과 확정 표시 사이에 회수 작업이 끼어들어 실제로 팔린 재고를
     * 되돌릴 수 있다.
     *
     * @param orderId 결제 주문 ID
     */
    void commitAllByOrderId(String orderId);

    /**
     * 잡음(NOISE) 상태로 남은 기록을 최대 {@code limit} 건 조회한다 — 회수 판정의 입력이다.
     *
     * <p>확정(COMMITTED)·되돌림(REVERTED) 기록은 대상에서 빠진다. 결제가 종결인지 여부는 이 조회가
     * 판단하지 않는다 — 그 판단은 호출자가 {@link PaymentEventRepository}로 원본 결제 상태를 별도
     * 조회해서 한다.
     *
     * @param limit 조회 상한
     * @return 잡음 상태 기록 스냅샷 목록 (상한 이하)
     */
    List<StockHoldRecordCandidate> findNoiseCandidates(int limit);

    /**
     * 잡음(NOISE) 상태로 남은 기록 전체 건수 — 회수 주기 작업이 미회수 잔량 지표로 노출한다.
     *
     * @return 잡음 상태 기록 수
     */
    long countNoise();
}
