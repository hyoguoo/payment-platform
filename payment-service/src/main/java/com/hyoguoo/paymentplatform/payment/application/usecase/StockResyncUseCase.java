package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.StockResyncOverlapStatus;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.StockResyncResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.StockAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 재고 캐시(redis-stock)를 product RDB(SoT) 기준으로 재정렬하는 운영 resync use-case.
 *
 * <p>redis-stock 은 payment 의 선차감 캐시이고 product RDB 가 SoT 다. 부팅 직후 시드 스크립트가
 * 1회 정렬한 뒤로는 동기화 경로가 없어, product RDB 가 외부(입고/관리자)에서 변경되면 발산한다.
 * 이 use-case 는 발산이 확인된 productId 의 캐시 값을 product RDB 값으로 덮어써 보정한다.
 *
 * <p>in-flight 선차감 안전성은 {@link StockCachePort#set} 주석 참고 — 단순 덮어쓰기라
 * 트래픽이 조용한 시점/특정 productId 한정 호출이 전제다. 이를 위해 그 상품에 진행 중(잡음 상태)
 * 선차감이 있으면 기본적으로 거부하고, 강제 실행({@code force=true})만 허용한다. 확인~덮어쓰기
 * 사이의 창은 막지 않고 덮어쓴 뒤 같은 조회를 한 번 더 해 겹침 여부를 알리는 방식으로 드러낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockResyncUseCase implements StockAdminService {

    private final ProductPort productPort;
    private final StockCachePort stockCachePort;
    private final StockHoldRecordRepository stockHoldRecordRepository;

    @Override
    public StockResyncResult resyncStockCache(Long productId, boolean force) {
        // 사전 조회는 강제 실행 여부와 무관하게 항상 먼저 실행한다. 강제 실행이 우회하는 것은
        // 건수 판정뿐이고 조회 자체가 아니다 — 조회 실패는 강제 실행이어도 그대로 전파해 거부한다.
        long beforeNoiseCount = countNoiseWithLogging(productId);

        if (beforeNoiseCount > 0) {
            if (!force) {
                throw PaymentStatusException.of(PaymentErrorCode.STOCK_RESYNC_NOISE_IN_PROGRESS);
            }
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.STOCK_CACHE_RESYNC_FORCED, () ->
                    String.format("Stock cache resync forced despite in-flight noise holds - "
                            + "productId=%d, noiseCount=%d", productId, beforeNoiseCount));
        }

        ProductInfo productInfo = productPort.getProductInfoById(productId);
        int rdbStock = productInfo.getStock();
        stockCachePort.set(productId, rdbStock);
        LogFmt.warn(log, LogDomain.PRODUCT, EventType.STOCK_CACHE_RESYNC, () ->
                String.format("Stock cache resynced from RDB(SoT) - productId=%d, quantity=%d",
                        productId, rdbStock));

        StockResyncOverlapStatus overlapStatus = checkOverlap(productId, beforeNoiseCount);

        return new StockResyncResult(rdbStock, overlapStatus);
    }

    /**
     * 사전 건수 조회 실패를 경고 로그로 남기고 그대로 다시 던진다 — 거부(전파) 자체는 올바른
     * 동작이라 바꾸지 않되, 로그가 없으면 운영자가 500 을 받아도 원인을 추적할 수 없다.
     */
    private long countNoiseWithLogging(Long productId) {
        try {
            return stockHoldRecordRepository.countNoiseByProductId(productId);
        } catch (RuntimeException e) {
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.STOCK_CACHE_RESYNC_PRECHECK_FAILED, () ->
                    String.format("Stock cache resync precheck (noise count) failed, request rejected - "
                            + "productId=%d: %s", productId, e.getMessage()));
            throw e;
        }
    }

    /**
     * 덮어쓴 뒤 같은 조회를 한 번 더 해 겹침 여부를 판정한다 — 사후 건수가 사전 건수보다 크면
     * 겹침이다. 재확인 자체가 실패해도 예외를 밖으로 내보내지 않는다 — 덮어쓰기는 이미 끝난
     * 뒤라 실패로 보고하면 운영자가 덮어쓰기가 안 됐다고 오인한다. 경고만 남기고 미상으로 둔다.
     */
    private StockResyncOverlapStatus checkOverlap(Long productId, long beforeNoiseCount) {
        try {
            long afterNoiseCount = stockHoldRecordRepository.countNoiseByProductId(productId);
            if (afterNoiseCount > beforeNoiseCount) {
                LogFmt.warn(log, LogDomain.PRODUCT, EventType.STOCK_CACHE_RESYNC_OVERLAPPED, () ->
                        String.format("Stock cache resync overlapped with new in-flight noise holds - "
                                        + "productId=%d, beforeNoiseCount=%d, afterNoiseCount=%d",
                                productId, beforeNoiseCount, afterNoiseCount));
                return StockResyncOverlapStatus.OVERLAPPED;
            }
            return StockResyncOverlapStatus.CLEAR;
        } catch (RuntimeException e) {
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.STOCK_CACHE_RESYNC_OVERLAP_CHECK_FAILED, () ->
                    String.format("Stock cache resync overlap recheck failed, overlap status left unknown - "
                            + "productId=%d: %s", productId, e.getMessage()));
            return StockResyncOverlapStatus.UNKNOWN;
        }
    }
}
