# vc-round 프로토콜

GitHub 이슈 · 브랜치 · PR 생성/갱신 표준 절차. PR Manager 페르소나(Haiku)가
verify 단계에서 호출한다.

## Participants
- **PR Manager** (Haiku) — 결정론적 gh CLI 호출 담당

## 이슈 → 브랜치 → PR 흐름

### Step 1 — 이슈 생성 (선택)
- 토픽 시작 시 `gh issue create`로 이슈 개설
- 제목: `<TOPIC>` (UPPER-KEBAB-CASE)
- 본문: discuss 산출물 요약 링크

### Step 2 — 브랜치
- 브랜치 명: `#<issue-number>` 또는 `<topic-slug>`
- `main`에서 분기
- 강제 push(`--force`) 금지, 필요 시 `--force-with-lease`

### Step 3 — PR 생성 (verify 단계)
- 조건: `verify-ready.md` 전 항목 yes AND 로컬 `./gradlew test` pass
- `gh pr create --base main --head <branch>` 사용
- 제목: `<type>: <한글 요약>` (70자 이내)
- 본문 템플릿:
  ```
  ## Summary
  - <1~3 bullet>

  ## Changes
  - <주요 파일/모듈>

  ## Test plan
  - [ ] ./gradlew test
  - [ ] <추가 검증>

  Closes #<issue-number>
  ```

### Step 4 — PR 갱신
- 리뷰 피드백 대응은 **새 커밋** + `git push`
- PR 본문 수정: `gh pr edit`
- 상태 코멘트: `gh pr comment`

## 금지
- `main` 브랜치로 직접 push
- PR에서 `--force-push` (lease 없는 것)
- 리뷰 없이 self-merge (오케스트레이터는 merge를 수행하지 않음 — 사용자만 merge)
- 커밋 메시지/PR 본문에 비밀 값

## 오케스트레이터 책임 분리
- PR Manager는 **생성/갱신만**. merge는 **사용자 권한**.
- `gh pr merge`는 사용자가 명시적으로 요청할 때만 실행
