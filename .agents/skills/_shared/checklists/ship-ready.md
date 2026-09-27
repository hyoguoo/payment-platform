# ship-ready 체크리스트

요청 범위에 포함된 마무리 항목을 적용한다.

# Gate checklist

## test & build

- [ ] [AGENTS.md](../../../../AGENTS.md)의 기준으로 영향받은 테스트·린트·통합 검사를 수행했다.
- [ ] 외부 상태를 바꾼 실측은 캐시된 Gradle 결과와 구분해 실제 실행 근거를 남겼다.
- [ ] 런타임 설정 변경의 관련 smoke 검증 또는 실행 불가 사유가 기록되어 있다.
- [ ] 이번 변경의 실패를 해결했고 기존 실패와 미검증 제한을 구분했다.

## code review resolution

- [ ] 리뷰의 실질적인 결함을 해결했거나 미해결 사유와 영향을 기록했다.
- [ ] 수정 후 관련 경로를 검증했다.

## documentation sync

- [ ] 영향받는 context 문서를 동기화하고 사실을 실제 소스·관측과 대조했다.
- [ ] 지침·스킬·context 변경은 `python3 scripts/check-agent-docs.py --strict`로 확인했다.
- [ ] TODOS·CONCERNS의 해소된 내용은 [context-update](../../context-update/SKILL.md)의 기준으로 정리했다.
- [ ] 요청된 설명 페이지가 있으면 최종 diff와 일치하고 렌더·상호작용을 확인했다.

# Post-phase checklist

## archival

- [ ] 토픽의 완료 브리핑과 실제 검증 결과가 아카이브에 남아 있다.
- [ ] 설계·PLAN 이동 후 링크와 아카이브 인덱스를 갱신했다.

## state finality

- [ ] STATE의 해당 활성 토픽을 종료하고 다른 작업의 메모는 보존했다.

## git / PR

- [ ] 요청된 커밋·푸시·PR을 완료했거나 막힌 단계와 이유를 보고했다.
