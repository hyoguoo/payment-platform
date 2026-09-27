# 커밋 컨벤션

커밋이 요청된 작업에 적용한다. 파일 편집이나 단계 완료만으로 자동 커밋하지 않는다.

## 변경과 메시지

- 파일 경로를 명시해 staging하고 staged diff를 확인한다. 무관한 사용자 변경과 비밀 파일을 제외한다.
- 구현과 그 동작을 검증하는 테스트를 같은 논리적 단위로 커밋한다.
- 기본은 새 커밋이다. 기존 커밋 수정은 사용자가 요청한 경우에만 수행한다. 검증 훅을 우회하지 않는다.
- 형식은 `<type>(<scope>): <한글 제목>`, 제목은 70자 이내다. 본문은 변경 이유를 설명한다.
- type: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `style`, `perf`, `build`, `wip`.
- scope: `payment`, `pg`, `product`, `user`, `gateway`, `eureka`, `docs`, `build`, `infra`, `deps`.
  한 범위로 묶이지 않으면 생략한다. 토픽명·태스크 ID를 scope로 쓰지 않는다.
- 마지막에 실제 에이전트의 `Co-Authored-By: 이름 <이메일>`을 적는다.
  Codex는 `Co-Authored-By: Codex <noreply@openai.com>`을 사용한다.
  다른 에이전트의 모델명·이메일을 복사하거나 알 수 없는 모델 버전을 지어내지 않는다.

## TDD와 작업 기록

개발 순서는 [testing.md](../../../../docs/context/conventions/testing.md)를 따른다.
RED 확인은 로컬에서 수행하고, 실패 테스트를 독립 커밋할 필요는 없다.
테스트만 추가하면 `test`, 기능은 `feat`, 결함 수정은 `fix`, 동작 보존 정리는 `refactor`를 쓴다.
PLAN·STATE가 있는 작업은 관련 변경과 함께 기록한다. 커밋 단위를 맞추기 위해 상태 파일만
별도 커밋하거나 불필요한 문서를 만들지 않는다. 훅 실패 후에는 원인을 고치고 실제 Git 상태를
확인한 뒤 재시도한다.
