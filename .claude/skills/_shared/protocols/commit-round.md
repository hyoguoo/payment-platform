# commit-round 프로토콜

커밋 생성 시 모든 페르소나가 따라야 하는 공통 규칙. Implementer / Planner 등이
파일 변경 후 커밋할 때 호출한다.

## 원칙
1. **amend 금지** — 실패 후 수정도 새 커밋
2. **명시 staging** — `git add -A` / `git add .` 금지. 파일 경로 명시
3. **한 커밋 한 의도** — 논리적 단위 분리
4. **hook 우회 금지** — `--no-verify`, `--no-gpg-sign` 금지

## 메시지 포맷
```
<type>(<scope>): <한글 제목>

<한글 본문 — 선택>

Co-Authored-By: <에이전트 이름> <이메일>
```

- **type (영문)**: `feat` / `fix` / `refactor` / `test` / `docs` / `chore` / `style` / `perf` / `build`
- **scope (영문, 변경의 주 위치)** — "자기 멋대로"를 막기 위해 아래 고정 어휘만 사용한다:
  - 서비스: `payment` / `pg` / `product` / `user` / `gateway` / `eureka`
  - 횡단: `docs`(영구 문서·컨벤션) / `build`(gradle·정적분석) / `infra`(docker·compose) / `deps`(의존성)
  - 여러 서비스에 동일 성격 변경이라 한 scope 로 못 묶으면 **scope 를 생략**한다 (`<type>: <제목>`).
  - **토픽명·태스크 ID(예: `payment-eos-transition`, `PET-8`)를 scope 로 쓰지 않는다.**
- **제목/본문은 한글**, 제목 70자 이내
- **본문은 "왜"에 초점** (diff로 "무엇"은 읽을 수 있음)
- **Co-Authored-By 트레일러 필수** — 마지막 줄에 에이전트 식별 트레일러를 일관되게 포함한다 (형식: `Co-Authored-By: 이름 <이메일>`, 본문과 빈 줄로 구분).

## TDD 커밋 분리
- RED: `test: <실패 테스트 추가>`
- GREEN: `feat: <구현 + PLAN.md 체크박스 + STATE.md>` — 단일 커밋
- REFACTOR (선택): `refactor: <개선 내용>`

## 문서 커밋
- plan 단계 산출물(PLAN.md + context 문서)은 **단일 커밋**
- verify 단계 최종 문서 스냅샷은 **독립 커밋**
- **STATE.md 단독 커밋 금지** — 항상 관련 코드/문서 커밋에 포함

## 금지 조합
- STATE.md만 바뀐 커밋
- 테스트 코드와 구현 코드 분리 커밋 (GREEN에서 함께)
- 비밀 파일(.env, credentials.*) 포함

## 실패 처리
pre-commit hook 실패 → 원인 수정 → **새 커밋** (amend 금지)
