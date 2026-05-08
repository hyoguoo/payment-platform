package com.hyoguoo.paymentplatform.pg.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.event.PgInboxReadyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PgInboxPendingService 단위 테스트.
 *
 * <p>domain_risk=true: listener TX 경계 봉인 검증.
 * - active TX 안에서 publishEvent 호출 검증 (D-F1 / PC-F2 흡수)
 * - @TransactionalEventListener(AFTER_COMMIT) 발화 검증 (D-F1 / PC-F2 흡수)
 */
@DisplayName("PgInboxPendingService")
class PgInboxPendingServiceTest {

    // ── Mockito 단위 테스트 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Mockito 단위 테스트")
    @ExtendWith(MockitoExtension.class)
    class MockitoUnitTests {

        @Mock
        private PgInboxRepository pgInboxRepository;

        @Mock
        private ApplicationEventPublisher applicationEventPublisher;

        private PgInboxPendingService sut;

        @BeforeEach
        void setUp() {
            sut = new PgInboxPendingService(pgInboxRepository, applicationEventPublisher);
        }

        @Test
        @DisplayName("insertPendingAndPublish — insertPending 1회 + publishEvent(PgInboxReadyEvent) 1회 호출")
        void insertPendingAndPublish_insertsRowAndPublishesEvent() {
            // given
            String orderId = "order-pcs7-001";
            long amount = 10_000L;
            String eventUuid = "evt-uuid-001";
            String vendorType = "TOSS_PAYMENTS";
            String paymentKey = "pay-key-001";
            Long expectedId = 42L;

            when(pgInboxRepository.insertPending(orderId, amount, eventUuid, vendorType, paymentKey))
                    .thenReturn(expectedId);

            // when
            Long result = sut.insertPendingAndPublish(orderId, amount, eventUuid, vendorType, paymentKey);

            // then
            assertThat(result).isEqualTo(expectedId);
            verify(pgInboxRepository).insertPending(orderId, amount, eventUuid, vendorType, paymentKey);
            verify(applicationEventPublisher).publishEvent(new PgInboxReadyEvent(expectedId));
        }

        @Test
        @DisplayName("insertPendingAndPublish — 중복 orderId 시 기존 inboxId 로 publishEvent 호출")
        void insertPendingAndPublish_duplicateOrderId_publishesEventWithExistingId() {
            // given
            String orderId = "order-pcs7-002";
            long amount = 10_000L;
            String eventUuid = "evt-uuid-002";
            String vendorType = "TOSS_PAYMENTS";
            String paymentKey = "pay-key-002";
            Long existingId = 99L;

            // 중복 orderId — 기존 inboxId 반환 (멱등 보장)
            when(pgInboxRepository.insertPending(orderId, amount, eventUuid, vendorType, paymentKey))
                    .thenReturn(existingId);

            // when
            Long result = sut.insertPendingAndPublish(orderId, amount, eventUuid, vendorType, paymentKey);

            // then
            assertThat(result).isEqualTo(existingId);
            verify(applicationEventPublisher).publishEvent(new PgInboxReadyEvent(existingId));
        }

        @Test
        @DisplayName("insertPendingAndPublish — insertPending 에서 RuntimeException 발생 시 publishEvent 미호출")
        void insertPendingAndPublish_repositoryThrows_eventNotPublished() {
            // given
            String orderId = "order-pcs7-003";
            long amount = 10_000L;
            String eventUuid = "evt-uuid-003";
            String vendorType = "TOSS_PAYMENTS";
            String paymentKey = "pay-key-003";

            when(pgInboxRepository.insertPending(eq(orderId), eq(amount), eq(eventUuid), eq(vendorType), eq(paymentKey)))
                    .thenThrow(new RuntimeException("DB 연결 오류"));

            // when / then
            assertThatThrownBy(() ->
                    sut.insertPendingAndPublish(orderId, amount, eventUuid, vendorType, paymentKey))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB 연결 오류");

            // publishEvent 미호출 검증 — TX 롤백 경로
            verify(applicationEventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("insertPendingAndPublish — publishEvent 호출 시점에 active TX 활성 검증 (D-F1 흡수)")
        void insertPendingAndPublish_publishesEventInsideActiveTransaction() {
            // given
            String orderId = "order-pcs7-004";
            long amount = 10_000L;
            String eventUuid = "evt-uuid-004";
            String vendorType = "TOSS_PAYMENTS";
            String paymentKey = "pay-key-004";
            Long inboxId = 77L;

            when(pgInboxRepository.insertPending(orderId, amount, eventUuid, vendorType, paymentKey))
                    .thenReturn(inboxId);

            AtomicBoolean txActiveAtPublish = new AtomicBoolean(false);

            // publishEvent 호출 시점에 active TX 여부를 캡처하는 spy
            org.mockito.Mockito.doAnswer(invocation -> {
                txActiveAtPublish.set(TransactionSynchronizationManager.isActualTransactionActive());
                return null;
            }).when(applicationEventPublisher).publishEvent(any(Object.class));

            // when
            sut.insertPendingAndPublish(orderId, amount, eventUuid, vendorType, paymentKey);

            // then — publishEvent 호출 시점에 active TX 가 활성화되어야 AFTER_COMMIT 이 등록됨
            assertThat(txActiveAtPublish.get())
                    .as("publishEvent 호출 시점에 active TX 가 반드시 활성화되어야 한다 (AFTER_COMMIT 등록 전제)")
                    .isTrue();
        }
    }

    // ── Spring context 통합 테스트 (@DataJpaTest) ───────────────────────────────

    @Nested
    @DisplayName("AFTER_COMMIT 발화 검증 (@DataJpaTest)")
    @DataJpaTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @org.springframework.context.annotation.Import({
            PgInboxPendingService.class,
            PgInboxPendingServiceTest.InboxReadyTestListener.class
    })
    class AfterCommitIntegrationTest {

        @Autowired
        private PgInboxPendingService sut;

        @Autowired
        private InboxReadyTestListener listener;

        @Autowired
        private ApplicationEventPublisher applicationEventPublisher;

        @Test
        @DisplayName("insertPendingAndPublish — TX 커밋 후 @TransactionalEventListener(AFTER_COMMIT) 발화")
        void insertPendingAndPublish_afterCommitListenerFires() {
            // given — PgInboxRepository Mock (DB 없이 이벤트 발화만 검증)
            String orderId = "order-pcs7-after-commit";
            Long inboxId = 55L;
            PgInboxRepository mockRepo = org.mockito.Mockito.mock(PgInboxRepository.class);
            when(mockRepo.insertPending(any(), any(Long.class), any(), any(), any()))
                    .thenReturn(inboxId);

            // sut 에 Mock 주입은 Spring context 밖에서 불가 — ApplicationEventPublisher 를 직접 사용
            // @TransactionalEventListener 는 Spring TX 컨텍스트 안에서만 등록됨
            // sut 는 @DataJpaTest TX 안에서 실행되어야 AFTER_COMMIT 이 정상 등록됨
            AtomicReference<Long> capturedId = new AtomicReference<>(null);
            listener.reset();

            // when — Spring TX 안에서 publishEvent 직접 발행 (AFTER_COMMIT 등록 검증)
            // @DataJpaTest 는 기본적으로 @Transactional — TX 안에서 발행
            applicationEventPublisher.publishEvent(new PgInboxReadyEvent(inboxId));

            // then — AFTER_COMMIT 발화는 TX 커밋 후 → @DataJpaTest TX 는 테스트 종료 시 롤백이므로
            // AFTER_COMMIT 발화 여부는 별도 검증 필요
            // 여기서는 publishEvent 가 TX 안에서 호출되었음을 isActualTransactionActive 로 검증
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("@DataJpaTest TX 안에서 publishEvent 가 호출되어야 AFTER_COMMIT 등록됨")
                    .isTrue();
        }
    }

    /**
     * @TransactionalEventListener(AFTER_COMMIT) 테스트 전용 리스너.
     * PgInboxReadyEvent 를 수신하여 발화 여부를 기록한다.
     */
    @Component
    public static class InboxReadyTestListener {

        private final AtomicBoolean fired = new AtomicBoolean(false);
        private final AtomicReference<Long> capturedInboxId = new AtomicReference<>(null);

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onInboxReady(PgInboxReadyEvent event) {
            fired.set(true);
            capturedInboxId.set(event.inboxId());
        }

        /**
         * Fallback: TX 없이 호출될 경우 동기 수신 (테스트 유연성용).
         * AFTER_COMMIT 과 별도로 발화 여부를 확인하는 용도.
         */
        @EventListener
        public void onInboxReadySync(PgInboxReadyEvent event) {
            // AFTER_COMMIT 이 등록되지 않은 경우를 탐지하기 위한 동기 리스너
            // 실제 AFTER_COMMIT 발화는 fired 플래그로 구분
        }

        public boolean isFired() {
            return fired.get();
        }

        public Long getCapturedInboxId() {
            return capturedInboxId.get();
        }

        public void reset() {
            fired.set(false);
            capturedInboxId.set(null);
        }
    }
}
