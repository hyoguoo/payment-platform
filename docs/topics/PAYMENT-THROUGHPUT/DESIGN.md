# PAYMENT-THROUGHPUT 설계 상세

> 측정 도구와 병목 수정 같은 코드 태스크의 근거와 작업 기록이다. 측정 회차는 [EXPERIMENTS](EXPERIMENTS.md)에 있다.
> [BRIEF](BRIEF.md)에서 합의하지 않은 결정은 넣지 않는다. 2026-11-06 옛 PLAN(`docs/PAYMENT-THROUGHPUT-PLAN.md`)에서 옮겼다.

## 결정

| ID | 결정 | 이유 | 근거 |
|---|---|---|---|
| D1 | 측정 모드를 `legacy`(기본), `capacity`, `user-quality`로 나눈다. 새 두 모드의 k6 VU는 checkout·confirm 뒤 바로 반환하고, 같은 주문을 별도 Python 관측기가 조회한다 | 완료 대기가 주입 슬롯을 차지하면 서버 한계가 아니라 발생기 한계를 재게 된다 | 승인된 브리핑 |
| D2 | 관측은 1초 간격·최대 30회·31초 기한. 포화, 늦은 스케줄, HTTP·파싱 오류, 202 미확인, 기한 뒤 응답을 원자료로 남긴다 | 접수 미확인을 실패·미접수로 단정하지 않기 위해 | 승인된 브리핑 |
| D3 | 백분위 분모는 전체 시작 건수로 두고 미관측은 무한 지연으로 다룬다. 관측 FAILED와 DB DONE이 충돌하거나 업무 실패가 확인되면 백분위와 별개로 FAIL이다 | 관측하지 못한 주문을 빼면 지연이 실제보다 좋아 보인다 | 제안(T2.2 독립 검토 PASS) |
| D4 | 지속 처리 판정은 유입 후반 60초 이상, 4개 이상 표본, 간격 15초 이내의 결제·재고 잔량과 메모리 관측 범위를 비교한다. 원본 표본이 30초보다 오래되면 근거 부족이다 | 일시적 변동과 지속 증가를 구분하기 위해 | 제안(T2.2 독립 검토 PASS) |
| D5 | DB DONE 기록 시각은 실제 커밋 순간으로 보지 않는다. Kafka 트랜잭션 표식 lag를 업무 적체로 보지 않는다 | 기록 시각은 커밋 전에 찍힐 수 있다 | 승인된 브리핑 |
| D6 | 새 모드의 유입 경계 판정은 연속 iteration, 총량, dropped, 예정 대비 -1~100ms 시각 정밀도로 한다. legacy 판정 코드는 바꾸지 않는다 | 옛 경계 건수 규칙이 정상 회차(capacity-r80-a)를 미확정으로 만들었다 | 제안(독립 domain-expert 재검토 PASS) |

## 태스크

- [x] **T1 — 전용 환경과 계측 기준 확보**
  - 결과: quality 환경, 빈 DB·Redis·Kafka·새 볼륨 대조와 전수 주문·승인·재고 검증, 실행별 원자료·자원·소스 증거 확보. legacy 조건 10·20·40·80/s 탐색. 160/s는 유입 미달로 미확정
- [ ] **T2 — 기준 처리량과 첫 병목 판정** (T2.1~T2.4와 첫 포화 근거가 있으면 완료)
- [x] **T2.1 — 주입·관측 분리와 시험 모드**
  - 변경 위치: `scripts/k6/quality-payment.js`, `scripts/quality/run.py`와 관측 모듈, Python 테스트
  - 완료 기준: 늦은 완료와 진행 중 주문이 많아도 관측 대기가 주입을 막지 않음(모의 서버). 품질 조회는 같은 구매의 orderId를 쓰고 새 구매를 몰래 더하지 않음. 1초·최대 30회 정책과 관찰 종료·예외·응답 유실 기록. 모드·파라미터·발생기 자원·실제 명령 증빙
  - 결과: D1·D2 구현. HTTP/1.1 연결 재사용과 실패 연결 해제, 기한 직전 응답의 콜백 경합 보완. 실제 k6 + 모의 HTTP 계약 4개, 모드·피드 5개, 관측 10개 포함 Python 87개 PASS. 독립 reviewer PASS(지적한 기한 경합·실패 연결 누적은 수정 후 회귀 검증). 증거 `live-drill/throughput-tools-t21-20261029/`
- [x] **T2.2 — 처리량·지연·잔량 판정과 증빙**
  - 변경 위치: `scripts/quality/results.py`, `settlement.py`, `report.py`, `docs/quality/`
  - 완료 기준: 접수 p95와 완료 p95·p99를 구분하고 분모·시각·워밍업 제외 명시. 부하 중 DB DONE 처리율과 종료 뒤 완료 분리, 미종결·미확인 포함 전수 대조. 조회 종료 뒤 완료를 기한 안 성공으로 소급하지 않음. 유입 누락·발생기 포화·접수 미확인·증거 누락이면 최대 TPS 미확정. 잔량·메모리·해소 근거 없이 지연 허용 지속 처리 PASS를 내지 않음. 과거 결과를 새 기준으로 자동 재판정하지 않음
  - 결과: D3~D5 구현. 10초 + 수집 비용 간격으로 DB 상태, product 재고 확정, 결제·전체 파이프라인 잔량, 앱 5개 heap·컨테이너 20개 메모리·host swap 수집. 독립 reviewer·domain-expert major 지적을 회귀 테스트로 고친 뒤 재검토 PASS. Python 122개 PASS. 증거 `live-drill/throughput-tools-t22-20261029/`
- [x] **T2.3 — 도구 검토와 낮은 부하 실구동**
  - 결과: 모드별 새 환경 1건/초·180초 검증 PASS(EXPERIMENTS의 "준비와 도구 검증"). JAR 해시 동일, 새 볼륨 42개 상호 분리. 요약 [도구 검증](tps-tool-validation.md), `live-drill/throughput-split-validation-20261029/validation-summary.json`
- [ ] **T2.4 — 수정 전 두 시험의 경계 탐색과 10분 지속 확인**
  - 완료 기준: 각 시험의 최고 품질 통과·지연 허용 지속 처리 구간, 첫 미달 부하, 탐색 간격, 완료 지연, 잔량, 해소 시간, 첫 병목 근거. 발생기·호스트 한계로 더 잴 수 없으면 그 제약과 확인 가능한 하한을 보고하고 서버 상한을 만들어 내지 않는다
  - 진행: capacity 80·120·160/s, user-quality 40·60·80/s 3분 탐색 완료. D6 판정 보완(Python 124개 PASS). **다음은 capacity 100/s·180초**
- [ ] **T3 — 실측 병목의 재현·개선·회귀 검증** (tdd, 결제 위험 경로)
  - 의존: T2.4. 실제 병목 모듈, 선택한 변경과 대안, 지킬 불변식을 근거와 함께 이 문서에 추가한 뒤 구현한다
  - 첫 후보: 결과 소비 동시성 1 → 3. 160/s에서 명령·결과 양쪽 적체와 PG 승인 뒤 지연을 확인했다. T2.4의 후보 10분 확인 전에는 서버 기본값을 바꾸지 않는다
  - 독립 domain-expert는 기존 주문 잠금, 결정적 상품별 키, DB·Kafka 트랜잭션 배선을 유지하면 후보 설계를 통과시켰고 아래 검증을 요구했다
    - 테스트 전용 3파티션·동시성 3에서 각 child 실제 배정, 독립 주문·같은 상품·중복 결과의 DONE·dedupe·hold·재고 이벤트 키 불변식
    - 대상 주문·파티션에 한정한 EOS 커밋 1회 실패와 반복 실패: 다른 두 파티션 진행, 재전송 복구, DLQ와 재고 미복구 구분
    - 미커밋 입력을 둔 리스너 재시작·재배정 뒤 offset 수렴. JVM 강제 종료·재시작과 실제 상품 재고 차감 1회는 별도 소량 실구동
    - 기존 실패 보상·격리·금액 불일치 회귀 유지. 기본값 채택 전 reviewer·domain-expert 독립 검토와 모듈 검사
  - 준비 상태: `ConfirmedConsumerConcurrencyIntegrationTest`(4개 시나리오) 작성, 독립 reviewer·domain-expert 재검토 PASS, 2026-11-06 커밋(`483fd9e2`). 테스트 컴파일만 확인했고 통합 테스트 실행은 아직이다. 로컬 프로세스 복구 드라이버 `run-consumer-recovery.py`는 작성만 했고 독립 검토·실행 전이다(Git 제외)
  - 검증: `./gradlew :payment-service:test :payment-service:checkstyleMain :payment-service:checkstyleTest :payment-service:spotbugsMain :payment-service:spotbugsTest`, DB·Kafka 배선 변경이면 `:payment-service:integrationTest`. 성능 실측과 Gradle 통합 테스트를 동시에 돌리지 않는다
- [ ] **T4 — 개선 뒤 같은 조건으로 한계 재탐색**
  - 바꾼 요소 외 조건을 고정하고 T2.4의 두 시험을 다시 한다. 같은 rate의 전후 비교와 최고 지속 처리량의 변화를 함께 보고한다. 효과가 없거나 회귀면 변경을 고치거나 빼거나 보류 근거를 남긴다. 다음 병목이 있으면 T3·T4를 반복한다
- [ ] **T5 — ship**
  - 변경에 맞는 정상·중복·실패·복구 확인, reviewer·risk-reviewer 완료 검토, context 문서와 HTML 동기화, BRIEF 완료 요약, 아카이브

## 리뷰 처리

## 진행 기록

- 2026-11-06: 지침 개편(#163)으로 이 토픽 문서를 새 형식으로 옮겼다. 옛 합의 문서는 BRIEF, PLAN 실행 기록은 EXPERIMENTS와 이 문서로 나눴고, `docs/reports/tps-*` 보고서는 이 폴더로 옮겼다. 측정 조건·결과·판정은 바꾸지 않았다. 같은 날 `#162`를 지침 개편 브랜치(PR #164) 위로 리베이스했다. `#162`의 지침 정비 커밋(`549a8083`, 정식 workflow의 이슈·브랜치 추적)은 바꾼 파일이 모두 개편에서 새로 쓰이거나 삭제됐고 내용도 새 지침에 들어 있어 다시 적용하지 않았다. 리베이스 전 상태는 로컬 브랜치 `#162-before-rebase`에 있다. PR #164가 squash로 병합되면 `git rebase --onto main agent-structure-overhaul '#162'`로 한 번 더 옮긴다.
- 2026-11-06: 로컬 `live-drill/throughput-split-measurements-20261029/RESUME.md`(Git 제외, 수정하지 않음)는 옛 경로를 가리킨다. `docs/reports/tps-handoff.md` → 이 폴더의 `tps-handoff.md`, PLAN → 이 문서, 옛 합의 문서 → `BRIEF.md`로 읽는다.
- 2026-10-29 중단 (재개 지점): 사용자 요청으로 100/s 시험 직전에 중단했다.
  - 환경: 60/s 품질 시험을 마치고 로그·이미지·원자료 저장 후 볼륨 보존 종료. 실행 중인 컨테이너와 부하 없음. 100/s 환경 기동·시드·워밍업·본측정은 실행하지 않았다.
  - 재개 순서: STATE → [인계](tps-handoff.md) → 이 문서와 로컬 `live-drill/throughput-split-measurements-20261029/RESUME.md`. 완료한 단계를 반복하지 않는다. 호스트·Docker·기존 컨테이너·JAR·프로젝트 이름을 확인하고 새 100/s 환경부터 기동한다. 기존 상태를 재사용하지 않는다.
  - 조건: 매 실측 새 프로젝트·컨테이너·볼륨, 빈 DB·Redis·Kafka 확인, 같은 시드. 기존 자료 보존, 재시드·`down -v`·clean·reset-db 금지. 실행 중 측정 소스 동결, 무거운 테스트 병행 금지.
  - 서버: 메인 Java·실행 JAR·운영 기본 소비 동시성 1은 바꾸지 않았다.
  - 검토 제약: 복구 드라이버 검토 중 서브에이전트 모델 사용량 한도에 걸렸다. 같은 호출을 반복하지 않고 다른 모델로 대체하거나 메인이 직접 검토한다. 대체 검토는 아직 하지 않았다.
- 2026-10-29: 검증된 T2 도구·문서·측정 체크포인트를 로컬 커밋(`a6c0aa0e` 등). 원자료와 로컬 드라이버는 Git 제외.
- 원자료 위치(`live-drill/`, Git 제외): `throughput-20261028/`, `throughput-clean-20261028/`, `throughput-tools-t21-20261029/`, `throughput-tools-t22-20261029/`, `throughput-split-validation-20261029/`, `throughput-split-measurements-20261029/`, 측정 도구 준비 단계 자료 `baseline-preparation-20261012/`
- 보고서: [클린 환경 측정 결과](tps-clean-measurements.html), [검토 요약](tps-review-summary.html), [서버 정적 분석](tps-server-analysis.md), [도구 검증](tps-tool-validation.md), [인계](tps-handoff.md)
