# 현재 작업 상태

> 최종 수정: 2026-08-10

## 활성 작업

- **주제**: pg 리스너 메시지 dedupe 층 제거
- **단계**: plan
- **이슈/브랜치**: #138
- **파일**: docs/topics/PG-MESSAGE-DEDUPE-LAYER-REMOVAL.md

## 재개 메모

### 문서가 코드보다 앞서 있다 (이 작업이 닫는 간극)

문서 4종을 Redis 층 제거 완료 기준으로 이미 갱신해 둔 상태에서 코드 제거를 진행한다.

- 갱신 완료: 위키 4종(`message-delivery-and-dedupe` / `architecture` / `msa-transition` / `pg-confirm-flow`) · `README.md` · 포트폴리오 데이터 4종 · 블로그 포스팅
- `redis-dedupe` 인스턴스 자체는 payment checkout 멱등성 store 가 계속 쓰므로 유지한다

### discuss 게이트에서 드러난 것 — 처리중 재전송 구멍

필터가 "값을 못 낸다"는 판단이 한 지점에서 틀렸다. **처리중 재전송을 억제하는 부수효과는 실제로 있었다.**

- 리스너 재적재 경로에만 유예가 없다(폴링은 60초). 좀비 재처리의 락은 짧은 TX 라 벤더 호출 전에 풀린다 → 벤더 호출(최대 13초) 중 재전송이 오면 호출이 겹친다
- 단순 시간 유예로는 못 막는다 — 재시도 명령이 백오프 2초 후 도착하므로 벤더 타임아웃을 덮는 유예를 잡으면 재시도까지 차단돼 지연이 60초로 늘어난다. attempt 헤더 비교도 첫 시도 중 재전송(1 == 1)을 못 가른다
- 정확한 해법은 벤더 호출 구간 표시 컬럼 + 마이그레이션 → **후속 토픽으로 분리** (사용자 결정)
- 겹침의 안전성은 벤더가 동일 멱등 키 동시 요청을 직렬화한다는 검증 불가 외부 가정에 의존한다. 깨지면 되돌릴 수단 없음(취소·환불 포트 미구현, CONCERNS L-9) — 후속 토픽 우선순위 근거

### 미커밋 변경 (다른 두 저장소)

- `payment-platform.wiki` — `architecture.md`, `message-delivery-and-dedupe.md`
- `notes/blog` — `src/content/docs/blog/msa-transition-decisions.md`, `src/data/paymentPortfolio/{arch,stages,trace,races}.ts`

### 별건 — 위키에 남은 끊긴 참조 2곳

이번 작업과 무관하며 손대지 않았다.

- `architecture.md` 의 FCG 상세 링크가 `pg-confirm-flow` 를 가리키는데 그 문서에 FCG 설명이 없다
- `message-delivery-and-dedupe.md` 서두가 "DLQ 처리를 다루며"라고 하는데 본문에 DLQ 절이 없다

### 블로그 포스팅 진행 상황

- 1편 **모놀리식 → 4서비스 분리** 초안 완료 (`notes/blog`, `msa-transition-decisions.md`) — 검수 4관점 통과, 코드 대조 10항목 통과
- 2편 **재시도 소진 이후 처리** 예정 — 1편 결말이 "payment→pg HTTP 조회 통로를 두지 않은 결정이 넉 달 뒤 문제가 됐다"로 넘어가게 써 뒀다. 소재는 `docs/archive/retry-exhaustion-disposition/`

## 최근 완료

- **RETRY-EXHAUSTION-DISPOSITION** (2026-08-06) — docs/archive/retry-exhaustion-disposition/COMPLETION-BRIEFING.md
- **BACKLOG-RESIDUE-CLEANUP** (2026-08-05) — docs/archive/backlog-residue-cleanup/COMPLETION-BRIEFING.md

전체 이력: `docs/archive/README.md` / 구 STATE 이력: `docs/archive/state-history-2026H1.md`
