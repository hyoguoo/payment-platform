# 현재 작업 상태

> 최종 수정: 2026-07-28

## 활성 작업

- **주제**: 관리자 화면 가시성 확충 — 재시도 이력과 재고 (ADMIN-VISIBILITY)
- **단계**: execute
- **활성 태스크**: Task 7: 시도 이력 조회 포트 + HTTP 어댑터
- **이슈/브랜치**: #126
- **파일**: docs/topics/ADMIN-VISIBILITY.md / docs/ADMIN-VISIBILITY-PLAN.md

## 재개 메모

Task 6(payment 측 pg 전용 Feign client + 짧은 타임아웃) 완료 — payment-service 가 pg-service 를 HTTP 로 부르는 최초의 경로. `PgFeignClient`(Eureka 논리 이름 `pg-service`) + `PgFeignConfig`(`@FeignClient(configuration=...)` 로만 한정 등록, ErrorDecoder 로 404/429·502·503·504/500 매핑). 타임아웃은 `application.yml` 의 `spring.cloud.openfeign.client.config.pg-service` 블록(연결 1초/읽기 2초, 환경변수로 조정 가능)으로 기존 `default`(연결 2초/읽기 5초) 와 분리 — 상품·사용자 Feign 타임아웃 불변 확인. 13태스크 중 6개 완료.

## 최근 완료

- **DLQ-QUARANTINE-RECOVERY** (2026-07-11) — docs/archive/dlq-quarantine-recovery/COMPLETION-BRIEFING.md
- **DOCS-CONSISTENCY-OVERHAUL** (2026-07-07) — docs/archive/docs-consistency-overhaul/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
