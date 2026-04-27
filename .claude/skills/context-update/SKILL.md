---
name: context-update
description: >
  payment-platform의 docs/context/ 영구 문서를 현재 코드베이스 상태에 맞게 갱신한다.
  "컨텍스트 업데이트", "context 문서 갱신", "docs/context 최신화", "아키텍처 문서 업데이트",
  "문서 동기화", "context 맞춰줘" 등을 말할 때 반드시 이 스킬을 사용한다.
  workflow verify 단계에서도 호출된다.
---

# Context 문서 갱신 가이드

`docs/context/` 디렉토리의 영구 문서들은 미래 세션에서 코드베이스 탐색 비용을 줄이는
핵심 자산이다. 이 스킬의 목적은 문서가 실제 코드 구조와 어긋나는 부분을 찾아
정확하게 맞추는 것이다.

---

## 호출 맥락에 따른 시작점

### workflow-verify 단계에서 호출된 경우

브랜치에서 변경된 파일 목록이 이미 파악되어 있다면 그것을 출발점으로 삼는다.

```bash
git diff main...HEAD --stat
```

변경된 파일 유형을 보고 어떤 context 문서가 영향을 받을지 빠르게 추릴 수 있다.
변경 범위가 명확하므로, 연관 없는 문서는 탐색하지 않아도 된다.

### 독립 호출된 경우

변경 범위가 불명확하므로 전체 context 문서를 점검 대상으로 본다.
각 문서를 읽은 뒤 현재 코드베이스와 대조해 stale한 부분을 찾는다.

---

## 점검 대상 문서

| 파일 | 담당 내용 | 코드에서 대조할 것 |
|------|----------|-----------------|
| `ARCHITECTURE.md` | 헥사고날 레이어 구조, 포트/어댑터 목록, 모듈 경계 | 실제 패키지 구조, 포트 인터페이스, 어댑터 구현체 |
| `STACK.md` | 기술 스택, 의존성 버전 | `build.gradle`, `docker-compose.yml` |
| `INTEGRATIONS.md` | 외부 연동(Toss / NicePay), 벤더 어댑터, cross-service HTTP | 벤더 Strategy, HTTP 어댑터, contract test |
| `PAYMENT-FLOW.md` | end-to-end 결제 플로우 (브라우저 → DONE/FAILED) | 컨트롤러·use case·Kafka 토픽·status 폴링 |
| `CONFIRM-FLOW.md` | payment-service 측 비동기 confirm 사이클 deep dive (분석 + Mermaid 다이어그램 통합) | 진입 use case, AFTER_COMMIT 리스너, 폴백 워커, consumer, two-phase lease, 상태 머신 |
| `TESTING.md` | 테스트 전략, Fake/Mock 패턴, JaCoCo | 테스트 클래스 패턴, 설정 파일 |
| `CONVENTIONS.md` | Lombok 컨벤션, 예외 처리, LogFmt 로깅, AOP | 실제 코드 관례 |
| `STRUCTURE.md` | 디렉토리 트리, 모듈 의존, 패키지 컨벤션 | settings.gradle, src 트리 |
| `PITFALLS.md` | 학습된 도메인 함정 인덱스 | archive briefing 의 핵심 결정 |
| `CONCERNS.md` | 알려진 우려 / 한계 | Phase 4 후속 항목 |
| `TODOS.md` | 향후 처리 항목 | 활성 작업 외부 |

---

## 갱신 절차

### 1. 점검 범위 결정

호출 맥락에 따라 점검할 문서 목록을 먼저 정한다.
연관성이 없는 문서는 건너뛴다 — 모든 문서를 항상 업데이트할 필요는 없다.

### 2. 문서 읽기 → 코드 대조

점검 대상 문서를 읽고, 문서가 언급하는 구성요소(클래스, 인터페이스, 패키지 등)가
실제 코드베이스에 존재하는지, 내용이 맞는지 확인한다.

확인 방법:
- 클래스/인터페이스 존재 여부: Glob 또는 Grep으로 탐색
- 패키지 구조: 디렉토리 탐색
- 다이어그램: 실제 흐름과 노드/엣지 비교

### 3. 갱신 판단 기준

갱신이 필요한 경우:
- 문서에 언급된 클래스/패키지가 삭제·이동·이름 변경된 경우
- 새로운 포트, 어댑터, 전략, 도메인 엔티티가 추가된 경우
- 다이어그램 흐름이 실제 구현과 다른 경우
- 의존성 버전이 변경된 경우

갱신하지 않는 경우:
- 문서 내용이 코드와 일치하는 경우
- 세부 구현 상세(메서드 시그니처 등)는 문서 범위 밖

### 4. 갱신 원칙

- 추가: 새 구성요소를 해당 섹션에 추가한다.
- 삭제: 제거된 구성요소는 문서에서도 제거한다.
- 수정: 변경된 내용(이름, 역할, 흐름)을 반영한다.
- 다이어그램: 노드·엣지만 실제와 맞추고, 레이아웃은 최소한으로 변경한다.
- 범위 외 개선은 하지 않는다 — 현재 코드와의 불일치만 수정한다.

---

## 완료 보고

갱신이 완료되면 다음 형식으로 보고한다:

```
## Context 문서 갱신 완료

**점검한 문서**: ARCHITECTURE.md, INTEGRATIONS.md
**수정한 문서**: ARCHITECTURE.md (ImmediateEventHandler 어댑터 추가)
**변경 없는 문서**: INTEGRATIONS.md (내용 일치)
```

갱신한 파일이 없으면 "모든 문서가 현재 코드와 일치합니다"라고 알린다.