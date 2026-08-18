package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordCandidate;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.util.StockHoldReverter;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미회수 선차감 기록 회수 판정.
 *
 * <p>대상은 결제가 종결이고 잡음(NOISE)으로 남은 기록뿐이다. 진행 중 결제의 기록을 되돌리면
 * 게이트가 아직 실행 중인 확정 흐름의 재고를 되살려 초과 판매로 이어질 수 있다 — 그래서 결제
 * 상태가 종결이 아니면 흔적을 건드리지 않고 다음 주기로 넘긴다. 흔적이 없는 후보는 캐시를
 * 건드리지 않고 기록만 닫고, 캐시가 이미 처리됨을 돌려줘도(다른 경로가 먼저 되돌린 경우) 기록은
 * 닫는다 — 닫지 않으면 다음 주기가 같은 기록을 집었다가 또 못 닫는 유령 미회수 건수를 만든다.
 *
 * <p><b>결제 상태 조회는 쓰기 경로와 동일한 {@link PaymentEventRepository} 빈을 그대로 쓴다.</b>
 * {@code PaymentTransactionCoordinator}/{@code PaymentConfirmResultUseCase}가 결제 상태를 갱신할 때
 * 쓰는 포트와 같다 — 회수 전용 조회 포트를 새로 만들지 않는다. 새 포트를 만들면 그 자리가 나중에
 * 읽기 복제본으로 갈아탈 수 있는 지점이 되고, 회수 판정이 낡은 상태를 보고 진행 중 결제의 재고를
 * 되돌리는 사고로 이어진다. {@link #recover}를 쓰기 경로와 같은 비-읽기전용 {@code @Transactional}로
 * 감싸는 것도 같은 이유다 — 이 메서드 안의 모든 조회·갱신이 하나의 비-읽기전용 트랜잭션에 묶여,
 * 상태 조회가 (JPA 어댑터 메서드 자체에 붙은 {@code readOnly = true}에 이끌려) 별도의 읽기전용
 * 트랜잭션으로 갈라져 나가지 않는다. 지금은 읽기 복제본이 없어 이 경계가 결과에 영향을 주지
 * 않지만, 후행 토픽이 읽기전용 여부로 라우팅하는 데이터소스를 끼우는 순간 이 경계가 실제로
 * 갈림길이 된다.
 */
@Service
@RequiredArgsConstructor
public class StockHoldRecoveryUseCase {

    private final StockHoldRecordRepository stockHoldRecordRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final StockCachePort stockCachePort;
    private final StockHoldReverter stockHoldReverter;

    /**
     * 잡음 상태 선차감 기록을 최대 {@code batchSize}건 훑어, 결제가 종결인 것만 되돌린다.
     *
     * @param batchSize 한 번에 조회할 후보 상한
     * @return 이번 호출에서 실제로 판정해 되돌린(닫은) 기록 수 — 진행 중 결제라 건드리지 않은
     *         후보는 포함하지 않는다
     */
    @Transactional
    public int recover(int batchSize) {
        List<StockHoldRecordCandidate> candidates = stockHoldRecordRepository.findNoiseCandidates(batchSize);
        int recoveredCount = 0;
        for (StockHoldRecordCandidate candidate : candidates) {
            if (isTerminalPayment(candidate.orderId())) {
                revert(candidate);
                recoveredCount++;
            }
        }
        return recoveredCount;
    }

    /**
     * 결제 상태를 찾지 못하면(고아 기록) 되돌리지 않는다 — 판정을 오판해 진행 중일 수도 있는
     * 결제의 재고를 되살리는 쪽보다, 다음 주기로 미루는 쪽이 안전하다.
     */
    private boolean isTerminalPayment(String orderId) {
        return paymentEventRepository.findByOrderId(orderId)
                .map(paymentEvent -> paymentEvent.getStatus().isTerminal())
                .orElse(false);
    }

    private void revert(StockHoldRecordCandidate candidate) {
        PaymentOrder order = PaymentOrder.allArgsBuilder()
                .orderId(candidate.orderId())
                .productId(candidate.productId())
                .quantity(candidate.quantity())
                .allArgsBuild();
        stockHoldReverter.revertProductHold(
                candidate.orderId(), order, candidate.cycleToken(), stockCachePort, stockHoldRecordRepository);
    }
}
