---
phase: 04-kafka-adapter
plan: 01
subsystem: kafka-infrastructure
tags: [kafka, testcontainers, docker-compose, infrastructure, test-scaffolding]
dependency_graph:
  requires: []
  provides:
    - spring-kafka dependency (build.gradle)
    - testcontainers:kafka dependency (build.gradle)
    - spring.kafka.* base configuration (application.yml)
    - kafka + kafka-ui Docker services (docker-compose.yml)
    - BaseKafkaIntegrationTest abstract class
    - KafkaConfirmAdapterTest stub (RED)
    - KafkaConfirmListenerTest stub (RED)
  affects:
    - 04-02-PLAN.md (KafkaConfirmAdapter 구현)
    - 04-03-PLAN.md (KafkaConfirmListener 구현)
    - 04-04-PLAN.md (통합 테스트)
tech_stack:
  added:
    - spring-kafka (Spring Boot managed version)
    - testcontainers:kafka 1.19.8
    - bitnami/kafka:3.9 (Docker, KRaft mode)
    - ghcr.io/kafbat/kafka-ui:latest (Docker)
    - confluentinc/cp-kafka:7.4.0 (Testcontainers KRaft)
  patterns:
    - BaseKafkaIntegrationTest extends BaseIntegrationTest pattern (parallel abstract class)
    - @DynamicPropertySource for Kafka bootstrap-servers override
    - RED test stub pattern (Plan N creates stubs, Plan N+1 implements)
key_files:
  created:
    - src/test/java/com/hyoguoo/paymentplatform/core/test/BaseKafkaIntegrationTest.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/infrastructure/adapter/KafkaConfirmAdapterTest.java
    - src/test/java/com/hyoguoo/paymentplatform/payment/listener/KafkaConfirmListenerTest.java
  modified:
    - build.gradle
    - src/main/resources/application.yml
    - src/main/resources/application-docker.yml
    - docker/compose/docker-compose.yml
decisions:
  - "BaseKafkaIntegrationTest는 BaseIntegrationTest를 상속하지 않고 독립 추상 클래스로 설계: Kafka 전용 통합 테스트는 MySQL+Kafka 두 컨테이너를 모두 관리하는 독립 베이스 클래스가 필요함"
  - "KafkaContainer(confluentinc/cp-kafka:7.4.0).withKraft() 사용: testcontainers 1.19.8에서 KRaft 지원 API"
  - "KafkaConfirmAdapterTest stub은 executeStockDecreaseWithJobCreation() 호출 검증: Kafka 어댑터는 outbox 없이 직접 job 생성 후 Kafka publish 방식 사용"
metrics:
  duration: 4m
  completed: 2026-03-15
  tasks_completed: 2
  files_modified: 7
---

# Phase 4 Plan 01: Kafka 인프라 기반 세팅 Summary

**One-liner:** bitnami/kafka:3.9 KRaft Docker Compose + spring-kafka/testcontainers:kafka 의존성 + BaseKafkaIntegrationTest + Wave 0 RED 테스트 스텁 2개 생성

## What Was Built

Phase 4 Kafka 어댑터 구현을 위한 인프라 기반을 세팅했다. Docker Compose에 KRaft 모드 Kafka 브로커와 kafbat/kafka-ui를 추가하고, Spring Boot 애플리케이션에 spring-kafka 의존성과 기본 설정을 추가했다. 통합 테스트를 위한 BaseKafkaIntegrationTest 추상 클래스와 Plan 02/03에서 구현할 KafkaConfirmAdapter, KafkaConfirmListener의 테스트 스텁(RED 상태)을 생성했다.

## Tasks Completed

| Task | Description | Commit | Key Files |
|------|-------------|--------|-----------|
| 1 | Docker Compose + build.gradle + application.yml 인프라 세팅 | 1d055bb | docker-compose.yml, build.gradle, application.yml, application-docker.yml |
| 2 | BaseKafkaIntegrationTest + Wave 0 테스트 스텁 생성 | db1123b | BaseKafkaIntegrationTest.java, KafkaConfirmAdapterTest.java, KafkaConfirmListenerTest.java |

## Decisions Made

1. **BaseKafkaIntegrationTest 독립 추상 클래스 설계**: BaseIntegrationTest를 상속하지 않고 동일한 MySQL 컨테이너 설정을 복제한 독립 클래스로 설계. Kafka 통합 테스트는 `spring.payment.async-strategy=kafka` 오버라이드가 필요하여 BaseIntegrationTest와 분리.

2. **confluentinc/cp-kafka:7.4.0 with .withKraft() 사용**: testcontainers 1.19.8에서 KRaft 지원. bitnami/kafka:3.9는 Docker 운영용, confluentinc/cp-kafka:7.4.0은 Testcontainers용으로 분리 사용.

3. **KafkaConfirmAdapterTest stub의 executeStockDecreaseWithJobCreation() 검증**: Outbox 어댑터는 `executeStockDecreaseWithOutboxCreation()`을 사용하지만, Kafka 어댑터는 기존 `executeStockDecreaseWithJobCreation()`을 재사용 (PaymentProcess 기반 job 생성).

## Deviations from Plan

None - plan executed exactly as written.

## Verification Results

- `./gradlew compileJava`: BUILD SUCCESSFUL
- `docker/compose/docker-compose.yml`: kafka + kafka-ui 서비스 존재 확인
- `BaseKafkaIntegrationTest.java`: KafkaContainer 정적 필드 선언 확인
- `KafkaConfirmAdapterTest.java`: 패키지 존재, 4개 테스트 메서드 스텁 (RED 상태)
- `KafkaConfirmListenerTest.java`: 패키지 존재, 4개 테스트 메서드 스텁 (RED 상태)
- RED 상태 원인: KafkaConfirmAdapter, KafkaConfirmListener 클래스 미구현 (Plan 02/03에서 해소 예정)

## Self-Check: PASSED
