---
name: issue-commit-pr
description: Runs the full GitHub workflow — create issue, create branch, commit, push, and open PR. Use this skill whenever the user asks to "create an issue and PR", "commit and open a PR", "push this work to GitHub", or any similar request that covers the end-to-end flow from current changes to a pull request.
---

# Issue → Commit → PR Workflow

Handles the full flow from analyzing current changes to opening a GitHub pull request.

> **Language rule**: All issue titles/bodies, commit messages, and PR descriptions must be written in Korean. Code, branch names, and type prefixes stay in English.

## Step 1 — Analyze Changes

Run `git diff` (staged + unstaged) and `git status` to understand what changed and why. Use this analysis as the basis for all written content below.

## Step 2 — Create GitHub Issue

Use `mcp__github__issue_write` (not `gh` CLI — it may not be authenticated).

**Title format**: `<type>: <one-line summary in Korean>`
Example: `feat: PaymentRecoveryUseCase 모든 상태 처리 추가`

**Body structure**:
```
## 배경
Why this change was needed; what problem existed before.

## 변경 내용
### `ChangedFile/ClassName`
- What changed and how, as bullet points
```

Infer `owner`/`repo` from `git remote -v` or context.

## Step 3 — Create Branch

```bash
git checkout -b "#<issue-number>"
```

Branch name must be exactly `#<number>` (e.g., `#47`).

## Step 4 — Stage Files

Stage files by name explicitly. Never use `git add -A` or `git add .` — unintended files (`.env`, build artifacts) may be included.

## Step 5 — Commit

```bash
git commit -m "$(cat <<'EOF'
<type>: <one-line summary in Korean>

<detailed description in Korean>

Closes #<issue-number>

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

**Commit message rules**:
- Type prefix in English: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`
- Body in Korean
- Implementation code and test code go in the same commit

## Step 6 — Push Branch

```bash
git push -u origin "#<issue-number>"
```

## Step 7 — Create PR

Use `mcp__github__create_pull_request` (not `gh` CLI).

- `head`: `#<issue-number>`
- `base`: `main`
- Title: same as the commit message first line

**PR body structure**:
```markdown
## 관련 이슈
Closes #<issue-number>

## 개요
Why this change was needed and what problem it solves. One short paragraph in Korean.

## 구현 내용
### `ChangedFile/ClassName`
- What changed and how, as bullet points in Korean

## 테스트
- What was tested and how, as bullet points in Korean
```

Omit sections that don't apply (e.g., no `## 테스트` if there are no test changes). Add `## 주요 버그 수정` if bug fixes are included.

## Notes

- If README or docs contain temporary TODO notes, ask the user whether to include them before committing.
