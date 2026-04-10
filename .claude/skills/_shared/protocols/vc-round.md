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
- 제목: `<type>: <TOPIC> — <한글 요약>` (70자 이내)
- Labels: 변경 유형에 맞게 (`enhancement`, `refactor`, `documentation` 등)
- Assignees: 작업자
- 본문 템플릿 (한글 헤더, 서사형):
  ```
  ## 관련 이슈
  Closes #<issue-number>

  ## 개요

  <서사형 설명: 어떤 문제가 있었고, 왜 이 변경이 필요하며, 어떻게 해소하는지.
  bullet 나열이 아닌 문단으로 작성한다.>

  ## 구현 내용

  ### <영역 1 — 예: 도메인>
  - 변경 내용 상세

  ### <영역 2 — 예: Application>
  - 변경 내용 상세

  ## 상태 머신 / 플로우 (해당 시)

  ```mermaid
  <상태 전이 또는 흐름 다이어그램>
  ```

  ## 테스트
  - <서술형: 어떤 테스트가 어떤 케이스를 커버하는지 설명>
  ```

- **금지**: 파일 경로를 추측하지 않는다. 실제 `git diff --stat` 출력을 기반으로만 작성.
- **금지**: 영문 헤더(Summary, Changes, Test plan) 사용 금지. 한글 헤더 사용.

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
