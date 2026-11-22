package com.hyoguoo.paymentplatform.product.application.usecase

import com.hyoguoo.paymentplatform.product.domain.Stock
import com.hyoguoo.paymentplatform.product.exception.ProductStockException
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode
import com.hyoguoo.paymentplatform.product.mock.FakeEventDedupeStore
import com.hyoguoo.paymentplatform.product.mock.FakeStockRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * StockCommitUseCase 단위 테스트.
 *
 * FakeStockRepository + FakeEventDedupeStore 사용. RDB 차감만 검증한다.
 * 새 모델: Redis 동기화 책임은 product-service 가 가지지 않음 — payment-service 가 자기 책임.
 */
class StockCommitUseCaseTest {
    private lateinit var fakeStockRepository: FakeStockRepository
    private lateinit var fakeEventDedupeStore: FakeEventDedupeStore
    private lateinit var stockCommitUseCase: StockCommitUseCase

    @BeforeEach
    fun setUp() {
        fakeStockRepository = FakeStockRepository()
        fakeEventDedupeStore = FakeEventDedupeStore()
        stockCommitUseCase =
            StockCommitUseCase(
                fakeStockRepository,
                fakeEventDedupeStore,
            )
    }

    @Test
    @DisplayName("첫 호출 시 RDB 재고가 qty 만큼 차감된다")
    fun commit_ShouldDecreaseRdbStock() {
        val productId = 1L
        val orderId = "order-100"
        val eventUUID = "event-uuid-001"
        val qty = 5
        val initialStock = 20

        fakeStockRepository.save(Stock(productId, initialStock))

        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)

        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt)

        val updated = fakeStockRepository.findByProductId(productId).orElseThrow()
        assertThat(updated.quantity).isEqualTo(initialStock - qty)
    }

    @Test
    @DisplayName("동일 eventUUID 2회 호출 → 두 번째는 no-op (dedupe)")
    fun commit_DuplicateEventUuid_ShouldNoOp() {
        val productId = 2L
        val orderId = "order-200"
        val eventUUID = "event-uuid-dup"
        val qty = 3
        val initialStock = 10

        fakeStockRepository.save(Stock(productId, initialStock))

        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)

        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt)
        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt)

        // 두 번째 호출은 dedupe 로 무시 → 재고는 첫 번째 호출 기준
        val current = fakeStockRepository.findByProductId(productId).orElseThrow()
        assertThat(current.quantity).isEqualTo(initialStock - qty)
    }

    @Test
    @DisplayName("재고 row 미존재 시 IllegalStateException 전파")
    fun commit_WhenStockNotFound_ShouldThrow() {
        val productId = 3L
        val orderId = "order-300"
        val eventUUID = "event-uuid-fail"
        val qty = 5

        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)

        assertThatThrownBy {
            stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("중복 이벤트: recordIfAbsent에 now가 전달되고 false 반환 시 commitToRdb 미호출")
    fun `commit_중복이벤트_recordIfAbsent에now전달_false반환시스킵`() {
        val productId = 4L
        val orderId = "order-400"
        val eventUUID = "event-uuid-dup-now"
        val qty = 2
        val initialStock = 15

        fakeStockRepository.save(Stock(productId, initialStock))

        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)

        // 첫 번째 호출: true 반환 → 재고 차감
        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt)

        // 두 번째 호출: false 반환 → commitToRdb 미호출
        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt)

        // Fake 에 now 가 전달됐는지는 FakeEventDedupeStore.contains 로 확인
        assertThat(fakeEventDedupeStore.contains(eventUUID)).isTrue
        // 재고는 첫 번째 호출분만 차감
        val current = fakeStockRepository.findByProductId(productId).orElseThrow()
        assertThat(current.quantity).isEqualTo(initialStock - qty)
    }

    @Test
    @DisplayName("최초 이벤트: recordIfAbsent true 반환 시 재고 차감 성공")
    fun `commit_최초이벤트_재고차감성공`() {
        val productId = 5L
        val orderId = "order-500"
        val eventUUID = "event-uuid-first"
        val qty = 7
        val initialStock = 30

        fakeStockRepository.save(Stock(productId, initialStock))

        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)

        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt)

        // FakeEventDedupeStore 에 UUID 가 기록됐고 재고 차감 완료
        assertThat(fakeEventDedupeStore.contains(eventUUID)).isTrue
        val current = fakeStockRepository.findByProductId(productId).orElseThrow()
        assertThat(current.quantity).isEqualTo(initialStock - qty)
    }

    // ── 재고 확정 음수 가드 ──────────────────────────────────────────────────

    @Test
    @DisplayName("잔량이 부족하면 ProductStockException 을 던지고 재고를 저장하지 않는다")
    fun commit_WhenInsufficientStock_ShouldThrowAndNotSave() {
        val productId = 6L
        val orderId = "order-600"
        val eventUUID = "event-uuid-insufficient"
        val initialStock = 3
        val qty = 5

        fakeStockRepository.save(Stock(productId, initialStock))

        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)

        assertThatThrownBy {
            stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt)
        }.isInstanceOfSatisfying(ProductStockException::class.java) { ex ->
            assertThat(ex.errorCode).isEqualTo(ProductErrorCode.NOT_ENOUGH_STOCK)
        }

        // 저장이 일어나지 않아 재고 값이 그대로다 — 초과분 흔적을 남기지 않는다
        val current = fakeStockRepository.findByProductId(productId).orElseThrow()
        assertThat(current.quantity).isEqualTo(initialStock)
    }

    @Test
    @DisplayName(
        "재고 부족 예외는 재고 row 미존재(IllegalStateException)와 다른 전용 타입이다 — 재시도 제외 목록에 이 타입만 등재할 수 있다",
    )
    fun commit_WhenInsufficientStock_ExceptionTypeIsDistinctFromNotFound() {
        val productId = 7L
        val orderId = "order-700"
        val eventUUID = "event-uuid-type-check"
        val initialStock = 1
        val qty = 2

        fakeStockRepository.save(Stock(productId, initialStock))

        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)

        assertThatThrownBy {
            stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt)
        }.isNotInstanceOf(IllegalStateException::class.java)
    }
}
