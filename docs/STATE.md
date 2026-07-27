# 현재 작업 상태

> 최종 수정: 2026-07-27 (ADMIN-VISIBILITY discuss 착수 — 설계 문서 작성, 결정 대기)

## 활성 작업

**ADMIN-VISIBILITY** — 관리자 화면에 재시도 경과와 재고를 노출한다. 이슈/브랜치 #126.
설계 문서: `docs/topics/ADMIN-VISIBILITY.md`

## 재개 메모

### 지금 단계

discuss 중. 설계 문서에 배경·현재 동작·확정 사항·열린 질문을 정리했고, **결정이 아직 안 났다.**
결정 없이는 태스크로 쪼갤 수 없으므로 PLAN 은 아직 만들지 않았다.

### 먼저 정할 것

1. **서비스 경계를 넘는 조회 방식** — 시도 횟수는 pg-service, 재고는 product-service 에 있고 화면은 payment-service 가 그린다. 화면 렌더 시 직접 조회 / 결과 이벤트에 동봉 / 별도 전파 중 선택하며 결합도·실시간성·복잡도 trade-off 가 갈린다.
2. **재고 조작 허용 범위** — 캐시 재동기화 / 실수량 조정 / 잡힌 재고 수동 해제 중 무엇을 열지.
3. **화면 형태** — 재시도 경과는 기존 결제 상세에 얹는 게 자연스럽고, 재고는 별도 페이지인지 상세 안인지 갈린다.

### 이미 확정된 것

- 재고는 **확정 수량만** 노출한다. 캐시의 선차감 상태는 올리지 않는다 — 승인 진행 중 두 값이 어긋나며 그 순간을 화면에서 설명하기 어렵다.
- 재고 조작 기능은 포함한다(범위만 미정).
- 재시도 추적이 원 요청과 갈리는 구조는 그대로 둔다. 흔적을 좇을 수단만 만든다.

### 배경

이 토픽은 라이브 실측(`drill/payment-e2e-live` 브랜치)에서 드러난 필요다. 실측 리포트가
재시도 경과와 재고 변화를 로그로만 보여줄 수 있었는데, 이는 개발자가 뒤져 찾은 근거라
운영자 관점의 화면으로 대체하려는 것이다. 작업이 끝나면 재실측해 리포트를 갱신한다 —
자세한 계획은 실측 브랜치의 `docs/STATE.md` 에 있다.

## 최근 완료

- **DLQ-QUARANTINE-RECOVERY** (격리 결제·유실 메시지 관리자 수동 복구 — 이슈/브랜치 #122) — 두 종류의 "멈춘 결제"에 운영 수단 부여. **범위 축소**: 격리 결제는 DONE 되살리기 배제·**FAILED 안전 종결만**(QUARANTINED 진입은 벤더 승인 전이라 돈 미캡처 → DONE 승격 시 유령 매출, discuss R1 critical). 종결 시 재고는 **`decrement:done` 토큰 존재 시에만 보상**(토큰=실차감 SoT라 사유 무관 통일 보상 유지하며 유령 재고 방지, discuss R2 critical 반증분), event `WHERE status='QUARANTINED'` CAS UPDATE 로 order 동조 전이(단일 TX)+AOP audit. ship critical: 보상이 상태 가드 앞에 있어 비격리(DONE) orderId 호출 시 유령 재고 재개방 → 조기 가드(보상 앞)+never-compensate 테스트. DLQ 는 원 토픽 `events.confirmed` 재발행(기존 EOS 파이프라인 재사용)+나이 게이트(DONE+종결+P8D 초과 차단), 어댑터는 `send().get(timeout)` 동기 확인+타임아웃 vs 없음 구분, retention 10d>게이트 8d 부등식 동조. 관리자 POST 2종+Thymeleaf 버튼. 8태스크, 단위 504·통합 48 PASS+린트 통과, discuss R2·plan·ship 리뷰 R1 pass(critical 1 해소/major 2/minor 4). 영구 문서 6개 갱신(CONCERNS L-15·16·17/TODOS/CONFIRM-FLOW/ARCHITECTURE/PAYMENT-FLOW-GUIDE/PITFALLS). 후속: TQ-2(격리 DONE 복구)·TQ-1(조건부 자동 재시도)·Task7 브로커 retention 실측(로컬 Kafka 미기동). 2026-07-11 — `docs/archive/dlq-quarantine-recovery/COMPLETION-BRIEFING.md`
- **DOCS-CONSISTENCY-OVERHAUL** (문서 전수 정합 개선 — 에이전트 문서 22파일 + README/GUIDE + 위키 25페이지를 사실 목록 28건(전건 소스 파일:라인 재확인) 기반 진단→정정. **소스-온리 근거 룰**(문서 상호 인용 불인정)은 discuss 게이트 critical 로 실증돼 채택. outbox 발행 실패 복구 stale 클러스터 전 문서 정정 + FAILED dead-terminal·attempt SoT·stock-committed key(productId) 동기화, TODOS/CONCERNS 3분류 정리(완료 32건 삭제·수용 한계 보존), 위키 본문 현행화+실이력 서사 9곳. doc-review 4관점 3라운드 전 관점 PASS. 코드 결함 후보 4건 TODOS 등재만. 19태스크, 단위 861·통합 59 PASS, ship 리뷰 pass(minor 6 전건 수정). **위키 20파일은 미커밋 — 사용자 검토 후 커밋·push 필요.** 2026-07-07, 이슈/브랜치 #120) — `docs/archive/docs-consistency-overhaul/COMPLETION-BRIEFING.md`

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
