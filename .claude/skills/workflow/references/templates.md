# Workflow 템플릿 모음

## STATE.md 형식

단계 전환 시 항상 이 형식으로 갱신한다.

```markdown
# 현재 작업 상태

> 최종 수정: YYYY-MM-DD

## 활성 작업
- **주제**: <주제>
- **단계**: discuss | plan | execute | verify | done
- **활성 태스크**: Task N: <이름>  ← execute 단계에서만 기재
- **이슈**: #<번호>  ← discuss 완료 후 기재
- **브랜치**: #<번호>  ← discuss 완료 후 기재

## 파일 링크
- 설계: docs/topics/<TOPIC>.md
- 플랜: docs/<TOPIC>-PLAN.md

## 단계 진행
- [x] discuss
- [ ] plan
- [ ] execute
- [ ] verify
```

---

## STATE.md — verify 완료 후 형식

```markdown
# 현재 작업 상태

> 최종 수정: YYYY-MM-DD

## 활성 작업
- **주제**: 없음
- **단계**: idle

## 최근 완료
- **주제**: <방금 완료한 주제>
- **완료일**: YYYY-MM-DD
- **아카이브**: docs/archive/<topic-kebab>/

## 단계 진행
- [x] discuss
- [x] plan
- [x] execute
- [x] verify
```

---

## .continue-here.md — 세션 중단 핸드오프 형식

세션을 중단할 때 아래 형식으로 `docs/.continue-here.md`를 작성하고,
해당 단계의 산출물 커밋에 함께 포함한다 (독립 커밋 불필요).

```markdown
# Continue Here

**Date:** YYYY-MM-DD
**Topic:** <작업 주제>
**Stage:** discuss | plan | execute | verify
**Active Task:** Task N: <이름>  ← execute 단계에서만

## 완료된 작업
- Task 1: IdempotencyStore 포트 정의 ✓
- Task 2: ...

## 남은 작업
- Task 3: InMemoryIdempotencyStore 구현 (현재 진행 중)
- Task 4: ...

## 결정 사항 / 주의사항
- ConcurrentHashMap.computeIfAbsent 사용 이유: 원자적 처리
- ...

## 재개 방법
workflow-execute 스킬로 Task 3부터 이어서 진행.
```