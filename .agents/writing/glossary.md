# payment-platform 용어표

이 저장소의 문서(README, `docs/`, 브리핑, PR 본문)에 쓰는 표기다. 다른 저장소의 용어표와 다르면, 글이 실리는 저장소의 표를 따른다.

## 같은 개념은 한 가지 표기로

| 의미 | 쓰는 표기 | 쓰지 않는 표기 |
|---|---|---|
| stock restore | 재고 복구 | 재고 복원 |
| compensation TX (본문) | 보상 TX | 보상 트랜잭션 (Mermaid 안의 풀어쓰기는 허용) |
| Worker | Worker | 워커 |
| skip | 건너뜀 | skip (Mermaid CSS 식별자 제외) |
| lock | 락 | lock (코드 블록, 시퀀스 동작은 예외) |
| 프로세스 즉시 종료 | 서버 크래시 | 서버 장애 |
| 상태 머신의 끝 상태 도달 | 종결 | 종료, 결판 |
| 타임아웃으로 PENDING에 돌려놓음 | 되돌림 | 복구 (사이클 단위 복구와 구분) |
| 상태 기반 조건부 갱신 | 조건부 갱신 | CAS (CPU compare-and-swap과 혼동) |
| Kafka redelivery | 재전송 | 재배달 |

새 동의어를 발견하면 이 표에 추가하고 문서 전체를 한 표기로 맞춘다.
