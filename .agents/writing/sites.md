# payment-platform 작성 정보

작성 규칙은 사용자 수준 `writing` 스킬(agent-kits 저장소)에 있다. 이 파일은 이 저장소에서 글을 쓸 때 필요한 위치와 확인 방법만 담는다.

## 글 위치와 장르

| 위치 | 장르 |
|---|---|
| 토픽 폴더의 BRIEF, 이슈·PR 본문, HTML 변경 설명 | work-doc |
| 토픽 폴더의 DESIGN·EXPERIMENTS, `docs/STATE.md`, `docs/context/` | 에이전트가 근거로 쓰는 기록. 정확성만 지킨다(work-doc 장르 첫머리) |
| `docs/guides/` | wiki(사람용 결제 흐름 안내) |
| `README.md` | general |
| 위키(별도 저장소) | wiki. 위치는 `wiki-access` 스킬 |
| 포트폴리오(blog 저장소) | portfolio. 위치는 `portfolio-access` 스킬 |

- 토픽 폴더와 HTML 변경 설명의 위치는 `workflow`, `explain-change-html` 스킬에 있다.
- 이 저장소의 표기는 [glossary.md](glossary.md)를 따른다.

## 확인

- `uv run scripts/check-agent-docs.py --strict`로 링크·경로 참조를 검사한다. Mermaid 라벨의 금지 문자도 함께 알려 준다.
