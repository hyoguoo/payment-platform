package com.hyoguoo.paymentplatform.payment.integration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.KafkaException;
import org.springframework.kafka.core.ProducerPostProcessor;

/**
 * 테스트 전용 시드 — {@code stockCommittedProducerFactory} 가 생성하는 {@link Producer} 를
 * 동적 프록시로 감싸 {@code commitTransaction()} 호출의 최초 N 회만 결정적으로 throw 시킨다(이후 위임).
 *
 * <p>목적: JPA(inner) 커밋이 Kafka(outer) EOS 커밋보다 먼저 일어나는 순서를 이용해,
 * "RDB DONE 커밋됨 + 재고 확정 발행/오프셋 커밋 실패" 윈도우를 결정적으로 재현한다
 * (Task 3 — 종결 가드 재발행 복구 실증).
 *
 * <p>{@link org.springframework.kafka.core.ProducerFactory#addPostProcessor} 는 운영 코드가 제공하는
 * 공식 확장 포인트다 — 운영 코드 변경 없이 테스트 스코프에서만 시드를 주입한다.
 * 반드시 사용 후 {@code removePostProcessor} 로 제거해 다른 시나리오에 영향이 없도록 격리한다.
 */
final class CommitFailureInjectingProducerPostProcessor implements ProducerPostProcessor<String, String> {

    private final AtomicInteger remainingFailures;

    /**
     * @param failureCount commitTransaction() 호출 중 최초 실패시킬 횟수 (1 = 단발 실패, N = N 회 연속 실패)
     */
    CommitFailureInjectingProducerPostProcessor(int failureCount) {
        this.remainingFailures = new AtomicInteger(failureCount);
    }

    @Override
    public Producer<String, String> apply(Producer<String, String> producer) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("commitTransaction".equals(method.getName()) && remainingFailures.getAndDecrement() > 0) {
                throw new KafkaException("commitTransaction 실패 주입 — Task 3 결정적 EOS 커밋 실패 실증");
            }
            try {
                return method.invoke(producer, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Producer<String, String>) Proxy.newProxyInstance(
                Producer.class.getClassLoader(),
                new Class<?>[]{Producer.class},
                handler
        );
    }
}
