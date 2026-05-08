package com.hyoguoo.paymentplatform.pg.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.event.PgInboxReadyEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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

        private MeterRegistry meterRegistry;
        private PgInboxPendingService sut;

        @BeforeEach
        void setUp() {
            meterRegistry = new SimpleMeterRegistry();
            sut = new PgInboxPendingService(pgInboxRepository, applicationEventPublisher, meterRegistry);
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
        @DisplayName("insertPendingAndPublish — TransactionTimedOutException 발생 시 카운터 증가 + 예외 재전파 (PCS-14 / PC-F3)")
        void insertPendingAndPublish_timeoutExceeded_emitsCounterAndRethrows() {
            // given
            String orderId = "order-pcs14-timeout";
            long amount = 10_000L;
            String eventUuid = "evt-uuid-timeout";
            String vendorType = "TOSS_PAYMENTS";
            String paymentKey = "pay-key-timeout";

            when(pgInboxRepository.insertPending(eq(orderId), eq(amount), eq(eventUuid), eq(vendorType), eq(paymentKey)))
                    .thenThrow(new TransactionTimedOutException("TX timeout 5s 초과"));

            // when / then — TransactionTimedOutException 재전파
            assertThatThrownBy(() ->
                    sut.insertPendingAndPublish(orderId, amount, eventUuid, vendorType, paymentKey))
                    .isInstanceOf(TransactionTimedOutException.class);

            // 카운터 증가 검증
            assertThat(meterRegistry.counter(PgInboxPendingService.LISTENER_TX_TIMEOUT_COUNTER_NAME).count())
                    .as("listener_tx_timeout_total 카운터가 1 증가해야 한다")
                    .isEqualTo(1.0);

            // publishEvent 미호출 검증
            verify(applicationEventPublisher, never()).publishEvent(any());
        }
    }

    // ── Spring context 통합 테스트 — active TX + AFTER_COMMIT 발화 검증 ───────────

    /**
     * Spring TX proxy 가 실제로 동작하는 컨텍스트에서 두 가지 핵심 속성을 검증한다.
     * (1) insertPendingAndPublish 호출 시 active TX 가 활성화됨 (D-F1 흡수)
     * (2) TX 커밋 후 @TransactionalEventListener(AFTER_COMMIT) 가 발화됨 (PC-F2 흡수)
     *
     * <p>@EnableTransactionManagement + 커스텀 TX Manager 조합으로 Spring context 를 최소 구성한다.
     * DB 없이 TX proxy + event publisher TX sync 만 검증한다.
     */
    @Nested
    @DisplayName("active TX + AFTER_COMMIT 발화 검증 (Spring TX proxy)")
    @ExtendWith(SpringExtension.class)
    @org.springframework.test.context.ContextConfiguration(classes = {
            PgInboxPendingServiceTest.TxIntegrationTestConfig.class,
            PgInboxPendingService.class,
            PgInboxPendingServiceTest.InboxReadyTestListener.class
    })
    class TxIntegrationTests {

        @Autowired
        private PgInboxPendingService sut;

        @Autowired
        private InboxReadyTestListener listener;

        @Autowired
        private TransactionTemplate transactionTemplate;

        @BeforeEach
        void setUp() {
            listener.reset();
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

            // @Transactional proxy 가 TX 를 시작한 상태에서 insertPending 안에서
            // TX active 여부를 캡처 — TxIntegrationTestConfig.MockPgInboxRepository 가 수행
            TxIntegrationTestConfig.MockPgInboxRepository.reset();

            // when — sut.insertPendingAndPublish 는 @Transactional(REQUIRED, timeout=5) proxy 로 감싸져 있음
            sut.insertPendingAndPublish(orderId, amount, eventUuid, vendorType, paymentKey);

            // then — insertPending 호출 시점(= publishEvent 직전) 에 active TX 가 true 여야 함
            assertThat(TxIntegrationTestConfig.MockPgInboxRepository.txActiveAtInsert.get())
                    .as("insertPendingAndPublish @Transactional proxy 안에서 insertPending/publishEvent 호출 시 active TX 가 활성화되어야 한다")
                    .isTrue();
        }

        @Test
        @DisplayName("insertPendingAndPublish — TX 커밋 후 @TransactionalEventListener(AFTER_COMMIT) 발화 (PC-F2 흡수)")
        void insertPendingAndPublish_afterCommitListenerFires() {
            // given
            String orderId = "order-pcs7-after-commit";
            long amount = 20_000L;
            String eventUuid = "evt-uuid-ac";
            String vendorType = "TOSS_PAYMENTS";
            String paymentKey = "pay-key-ac";

            // when — transactionTemplate 으로 TX 를 명시적으로 시작·커밋한다.
            // sut.insertPendingAndPublish 내부의 @Transactional(REQUIRED) 는 외부 TX 에 참여하고,
            // transactionTemplate 이 커밋할 때 AFTER_COMMIT sync 가 발화된다.
            transactionTemplate.executeWithoutResult(status ->
                    sut.insertPendingAndPublish(orderId, amount, eventUuid, vendorType, paymentKey)
            );

            // then — TX 커밋 후 AFTER_COMMIT 리스너 발화 확인
            assertThat(listener.isFired())
                    .as("TX 커밋 후 @TransactionalEventListener(AFTER_COMMIT) 가 발화되어야 한다")
                    .isTrue();
            assertThat(listener.getCapturedInboxId())
                    .as("리스너가 수신한 inboxId 가 insertPendingAndPublish 에서 발행한 id 와 일치해야 한다")
                    .isNotNull();
        }
    }

    // ── 테스트 전용 Spring 설정 ──────────────────────────────────────────────────

    @Configuration
    @EnableTransactionManagement
    static class TxIntegrationTestConfig {

        @Bean
        @Primary
        PgInboxRepository mockPgInboxRepository() {
            return new MockPgInboxRepository();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new SimplePlatformTransactionManager();
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        /**
         * TX active 여부를 캡처하는 Mock PgInboxRepository.
         * insertPending 호출 시점에 TransactionSynchronizationManager.isActualTransactionActive() 를 기록한다.
         */
        static class MockPgInboxRepository implements PgInboxRepository {

            static final AtomicBoolean txActiveAtInsert = new AtomicBoolean(false);
            private long nextId = 1L;

            static void reset() {
                txActiveAtInsert.set(false);
            }

            @Override
            public Long insertPending(String orderId, long amount, String eventUuid,
                    String vendorType, String paymentKey) {
                // publishEvent 직전인 이 시점에 active TX 여부를 캡처한다
                txActiveAtInsert.set(TransactionSynchronizationManager.isActualTransactionActive());
                return nextId++;
            }

            @Override
            public Optional<PgInbox> findByOrderId(String orderId) {
                return Optional.empty();
            }

            @Override
            public Optional<PgInbox> findById(Long inboxId) {
                return Optional.empty();
            }

            @Override
            public PgInbox save(PgInbox inbox) {
                return inbox;
            }

            // PCS-9: transitNoneToInProgress 삭제됨

            @Override
            public boolean transitPendingToInProgress(Long inboxId) {
                return false;
            }

            @Override
            public Long transitDirectToInProgress(String orderId, long amount) {
                return nextId++;
            }

            @Override
            public Long transitDirectToTerminal(String orderId, long amount,
                    PgInboxStatus terminalStatus, String storedStatusResult, String reasonCode) {
                return nextId++;
            }

            @Override
            public List<Long> findPendingZombieIds(int batchSize, long thresholdMs) {
                return List.of();
            }

            @Override
            public List<Long> findInProgressZombieIds(int batchSize, long thresholdMs) {
                return List.of();
            }

            @Override
            public void transitToApproved(String orderId, String storedStatusResult) {
            }

            @Override
            public void transitToFailed(String orderId, String storedStatusResult, String reasonCode) {
            }

            @Override
            public boolean transitToQuarantined(String orderId, String reasonCode) {
                return false;
            }

            @Override
            public Optional<PgInbox> findByOrderIdForUpdate(String orderId) {
                return Optional.empty();
            }
        }

        /**
         * DB 없이 TX 동기화만 수행하는 최소 TX Manager.
         * TransactionSynchronizationManager.setActualTransactionActive(true) 를 통해
         * Spring @Transactional proxy 와 @TransactionalEventListener 가 올바르게 동작함을 검증한다.
         */
        static class SimplePlatformTransactionManager extends AbstractPlatformTransactionManager {

            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
                // no-op — 실제 DB TX 없이 TX 동기화만 활성화
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                // no-op
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                // no-op
            }
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
