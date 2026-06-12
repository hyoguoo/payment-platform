# ship-ready 체크리스트

ship 단계(리뷰 + 마무리) 종료 조건. 두 섹션으로 나뉜다:

- **Gate checklist** — 리뷰 통과 후 마무리 진입 전, 메인 스레드가 순차 확인한다 (결정론적 항목 위주라 격리 판정 불필요).
- **Post-phase checklist** — Gate 통과 후 순차 실행하는 housekeeping.

---

# Gate checklist

## test & build (결정론적 백본)

- [ ] 전체 `./gradlew test` pass
- [ ] **통합테스트가 실제로 실행됨** — `build`가 UP-TO-DATE 캐시면 통합테스트가 돌지 않는다. `./gradlew integrationTest --rerun` 또는 해당 태스크 명시 실행으로 확인
- [ ] 린트 게이트 pass — `./gradlew checkstyleMain checkstyleTest spotbugsMain --continue` (또는 `check`). test 태스크만으로는 unused import 등 린트 위반을 놓친다 — CI가 PR 전수 게이트로 막으므로 로컬에서 선제 차단
- [ ] 실패가 있었다면 분류됨: (i) 이번 작업 관련 → 수정 완료, (ii) 사전 존재 → 기록 후 무시, (iii) 구조적 → 중단·보고
- [ ] JaCoCo 커버리지가 임계값 이하로 떨어지지 않음 (임계값이 설정된 경우)
- [ ] 벤치마크가 필요한 작업이었다면 k6 결과가 남음

## code review resolution (리뷰 해소)

- [ ] 리뷰 critical 전부 해소됨, 재리뷰에서 새 critical 없음
- [ ] 미해소 major/minor는 의도적으로 남긴 것이며 사유가 PLAN.md `## 리뷰 처리` 섹션에 기록됨

## documentation sync (문서 동기화)

- [ ] `docs/context/` 중 영향받는 문서가 갱신됨 (ARCHITECTURE / CONVENTIONS / TESTING / INTEGRATIONS 등 해당되는 것)
- [ ] `docs/context/TODOS.md`에 신규 기록이 필요한 경우 반영됨

---

# Post-phase checklist (메인 스레드 실행)

## archival (아카이빙)

- [ ] `docs/archive/<topic-kebab>/COMPLETION-BRIEFING.md` 작성됨
- [ ] `docs/<TOPIC>-PLAN.md` → `docs/archive/<topic-kebab>/<TOPIC>-PLAN.md` 이동 (`git mv`)
- [ ] `docs/topics/<TOPIC>.md` → `docs/archive/<topic-kebab>/<TOPIC>-CONTEXT.md` 이동
- [ ] `docs/archive/README.md`에 항목 추가됨

## state finality (상태 종결)

- [ ] STATE.md: 활성 작업 → 없음(idle), "최근 완료"에 한 줄 + 아카이브 링크, 재개 메모 비움
- [ ] 최종 커밋에 아카이브 이동 + context 문서 + STATE.md 모두 포함 (단일 `docs:` 커밋)

## git / PR

- [ ] branch가 `-u origin`으로 push됨
- [ ] PR 생성/갱신됨 (`conventions/github.md` Step 3/4 준수)
