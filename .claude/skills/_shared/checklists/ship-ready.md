# ship-ready 체크리스트

ship 단계(리뷰 + 마무리) 종료 조건. 두 섹션으로 나뉜다:

- **Gate checklist** — 리뷰 통과 후 마무리 진입 전, 메인 스레드가 순차 확인한다 (결정론적 항목 위주라 격리 판정 불필요).
- **Post-phase checklist** — Gate 통과 후 순차 실행하는 housekeeping.

---

# Gate checklist

## test & build (결정론적 백본)

- [ ] 전체 `./gradlew test` pass
- [ ] **통합테스트가 실제로 실행됨** — `build`가 UP-TO-DATE 캐시면 통합테스트가 돌지 않는다. `./gradlew integrationTest --rerun` 또는 해당 태스크 명시 실행으로 확인. **다중 서비스 변경 시**: 한 토픽이 여러 서비스를 건드렸으면 일부만 재실행하지 않고, 변경이 닿은 모든 서비스의 통합테스트를 재실행함 — 일부만 돌리면 나머지는 캐시로 조용히 스킵돼, 과거 실제로 한 서비스의 통합테스트가 안 돌아 CI에서야 컨텍스트 로드 실패가 드러난 적이 있다
- [ ] 린트 게이트 pass — `./gradlew checkstyleMain checkstyleTest spotbugsMain spotbugsTest --continue` (또는 `check`). test 태스크만으로는 unused import 등 린트 위반을 놓친다 — CI가 PR 전수 게이트로 막으므로 로컬에서 선제 차단. 이 태스크 집합은 CI lint step(`_service-ci.yml`)과 동일하게 유지한다 — CI 쪽 태스크가 바뀌면 이 줄도 같이 갱신
- [ ] 실패가 있었다면 분류됨: (i) 이번 작업 관련 → 수정 완료, (ii) 사전 존재 → 기록 후 무시, (iii) 구조적 → 중단·보고
- [ ] JaCoCo 커버리지가 임계값 이하로 떨어지지 않음 (임계값이 설정된 경우)
- [ ] 벤치마크가 필요한 작업이었다면 k6 결과가 남음
- [ ] **라이브 검증 원칙**: 합성 테스트(문법·픽스처 통과)를 검증 완료로 보지 않고, 가능하면 실제 장애 주입으로 신호 경로 끝까지 관측함 — 알람 규칙에 한정되지 않고 검증 요구 전반에 적용
- [ ] 런타임 행동이 바뀐 토픽(알람 규칙·Kafka 토픽/컨슈머 설정·스케줄러·관리자 운영 경로)은 해당 `docs/smoke/` 가이드로 라이브 검증됨 — 불가 시 사유 + 미검증 항목이 COMPLETION-BRIEFING 미결/후속에 기록됨 (암묵 생략 금지)

## code review resolution (리뷰 해소)

- [ ] 리뷰 critical 전부 해소됨, 재리뷰에서 새 critical 없음
- [ ] 미해소 major/minor는 의도적으로 남긴 것이며 사유가 PLAN.md `## 리뷰 처리` 섹션에 기록됨

## explanation page (설명 페이지)

- [ ] 설명 페이지가 생성되어 사용자 게이트에서 경로가 안내됨 — `.archive/explanations/YYYY-MM-DD-<topic-kebab>.html` (사용자 생략 지시 또는 제외 후 코드 diff 없음이면 n/a, 사유 안내됨)
- [ ] 게이트 추가 수정·B1 실패 수정 등 PR 전에 코드가 바뀌었다면 같은 파일로 재생성됨

## documentation sync (문서 동기화)

- [ ] `docs/context/` 중 영향받는 문서가 갱신됨 (ARCHITECTURE / CONVENTIONS / TESTING / INTEGRATIONS 등 해당되는 것)
- [ ] `CLAUDE.md` / `.claude/**` / `docs/context/**`를 건드린 토픽이면 `python3 scripts/check-agent-docs.py` 실행 결과 확인 — 참조 무결성·중복 규칙 판정에 새 문제가 0건(정보 제공용 판정인 Mermaid 금지 문자·고아 문서는 발견 시 후속 정리로 기록)
- [ ] `docs/context/TODOS.md`에 신규 기록이 필요한 경우 반영됨
- [ ] `TODOS.md`/`CONCERNS.md` 등 대장 문서에 완료 항목을 3분류 삭제 룰(전체 삭제 / 해소분 문장만 제거 / 수용된 한계·회피된 우려는 보존)대로 정리해 ✅ 완료 마킹 잔존이 0건임 (보존 결정 항목 제외)
- [ ] 갱신한 문서의 헤더 "최종 갱신" 시점이 본문 최신 내용과 동기화됨
- [ ] 대장 문서가 완료 이력으로 비대해지지 않고 슬림 유지됨 (`docs/archive/README.md`와 중복되는 완료 섹션 없음)

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
