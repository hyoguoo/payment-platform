# verify-ready 체크리스트

verify 단계 종료 조건. 모든 항목이 **yes**여야 작업을 idle 상태로 종결할 수 있다.
Verifier / PR Manager 페르소나가 이 체크리스트를 판정 기준으로 사용한다.

---

## test & build (결정론적 백본)

- [ ] 전체 `./gradlew test` pass
- [ ] 전체 `./gradlew build` 성공
- [ ] 실패가 있었다면 분류됨: (i) 이번 태스크 관련 → 수정 완료, (ii) 사전 존재 → 기록 후 무시, (iii) 구조적 → 중단
- [ ] JaCoCo 커버리지가 임계값 이하로 떨어지지 않음 (임계값이 설정된 경우)
- [ ] 벤치마크가 필요한 작업이었다면 k6 결과가 남음

## code review resolution (코드 리뷰 해결)

- [ ] review 단계의 CRITICAL 전부 해결됨
- [ ] 미해결 WARNING은 의도적으로 남긴 것이며 사유가 기록됨
- [ ] 재리뷰 후 새 CRITICAL 없음

## documentation sync (문서 동기화)

- [ ] `docs/context/` 중 영향받는 문서가 갱신됨 (ARCHITECTURE / CONVENTIONS / TESTING / INTEGRATIONS 등 해당되는 것)
- [ ] `docs/context/TODOS.md`에 신규 기록이 필요한 경우 반영됨

## archival (아카이빙)

- [ ] `docs/<TOPIC>-PLAN.md` → `docs/archive/<topic>/PLAN.md` 이동 (`git mv`)
- [ ] `docs/topics/<TOPIC>.md` → `docs/archive/<topic>/TOPIC.md` 이동
- [ ] `docs/archive/README.md`에 항목 추가됨
- [ ] 라운드 문서(`docs/rounds/<topic>/*.md`)도 아카이브로 이동 (라운드 파일화 도입 이후)

## state finality (상태 종결)

- [ ] STATE.md stage → `idle`, "최근 완료" 섹션에 링크
- [ ] `.continue-here.md` 삭제
- [ ] 최종 커밋에 아카이브 이동 + context 문서 + STATE.md 모두 포함

## git / PR

- [ ] branch가 `-u origin`으로 push됨
- [ ] PR 생성됨 (title / body 포맷 준수)
- [ ] PR 본문이 "개요 / 구현 내용 / 테스트 / 주요 버그 수정" 섹션 포함
- [ ] PR이 기존 PR 업데이트인 경우 history 보존 (append, not replace)
