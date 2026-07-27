# 현재 작업 상태

> 최종 수정: 2026-07-28

## 활성 작업

- **주제**: 관리자 화면 가시성 확충 — 재시도 이력과 재고 (ADMIN-VISIBILITY)
- **단계**: execute
- **활성 태스크**: Task 13: 라이브 검증
- **이슈/브랜치**: #126
- **파일**: docs/topics/ADMIN-VISIBILITY.md / docs/ADMIN-VISIBILITY-PLAN.md

## 재개 메모

Task 12(재고 화면) 완료 — payment-service 에 `StockViewController`(`@Controller`, `/admin/stocks`, 기존 `StockAdminController`(`@RestController`, `/admin/stock` 캐시 재동기화 POST)와 경로 비충돌) 신규. `ProductCatalogQueryPort.getPage(page, size)` 조회를 `try/catch (RuntimeException)` 로 감싸 실패 시에도 200 + 조회 불가 안내(`unavailable=true`, `products` 속성 부재)로 렌더 — Task 8 부분 렌더와 동일 패턴. `templates/admin/stock.html` 신규 — 기존 카드/테이블/페이지네이션 구성 그대로, "확정 수량은 RDB 기준이며 실시간 판매 가능 여부(캐시)는 다를 수 있다"는 안내 문구 고정 노출, 캐시 재동기화 REST 는 버튼으로 노출하지 않음(조작 기능 범위 밖). 신규 슬라이스 테스트 3건(`StockViewControllerTest`). `./gradlew :payment-service:test` 532건 전체 pass, checkstyle/spotbugs 통과. 13태스크 중 12개 완료. 다음은 Task 13 — `:*:bootJar` 선행 후 도커 스택 기동, (1) 재시도 이력 카드 렌더 (2) pg-service 다운 시 부분 렌더 (3) 재고 화면 확정 수량 반영 3항목을 실제 기동 환경에서 관찰 확인.

## 최근 완료

- **DLQ-QUARANTINE-RECOVERY** (2026-07-11) — docs/archive/dlq-quarantine-recovery/COMPLETION-BRIEFING.md
- **DOCS-CONSISTENCY-OVERHAUL** (2026-07-07) — docs/archive/docs-consistency-overhaul/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
