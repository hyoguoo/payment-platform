# 결제 처리량 측정과 개선 — PLAN

> 토픽: PAYMENT-THROUGHPUT · 갱신 2026-10-29 · execute T2.1/T2.2 완료, T2.3 완료, T2.4 수정 전 경계 탐색·100 TPS 실행 전 중단

추적: [#162 — 결제 처리량 한계 측정과 병목 개선](https://github.com/hyoguoo/payment-platform/issues/162). 브랜치: `#162`.

설계: [목표·측정 기준 합의](topics/PAYMENT-THROUGHPUT.md). 현재 위치: [STATE](STATE.md).

## 목표와 실행 원칙

수정 전 서버의 최고 품질 통과 TPS와 지연 허용 지속 처리 TPS를 각각 찾고, 실측 병목 수정 후 얼마나 올라갔는지 비교한다.
서버 처리 용량 시험과 사용자 품질 시험을 구분하고 각 시험에서 정상·지연·과부하 구간을 찾는다.
용량 시험은 전수 상태 폴링을 제외한다. 품질 시험은 실제 UI의 1초·최대 30회 조회를 기준으로 한다.
기존 500ms·60초 시험과 원자료는 별도 재현·스트레스 조건으로 보존한다.
새 워크로드의 수정 전 기준부터 다시 잡으며 다른 폴링 정책의 숫자로 개선 배수를 만들지 않는다.

합의 기준: checkout/confirm 응답 각각 p95 500ms 이하, confirm 시작→사용자 완료 확인 p95 3초·p99 5초 이하.
정상 Fake 시험의 API 오류·주문/승인/재고 불일치 관측 0, 유입 누락 0과 전수 대조는 두 시험에 공통이다.
완료 지연 목표를 넘겨도 유입·완료 처리율·잔량·메모리가 안정하면 지연 허용 지속 처리로 구분한다.
부하 종료 후에만 해소되는 적체나 발생기 포화를 지속 가능한 서버 TPS로 확정하지 않는다.

각 회차는 새 컨테이너·새 볼륨·빈 상태 확인·동일 시드·10/s 120초 워밍업으로 시작한다.
3분 탐색 → 통과/미달 경계 좁히기 → 후보 10분 지속 확인 → 첫 병목 하나 변경 → 같은 탐색/확인을 반복한다.
별도 요청 추적 토픽은 유지하며 성능 변경과 관련된 실패 경로만 회귀 검증에 포함한다.

## 태스크와 의존관계

- [x] T1 — 전용 환경과 계측 기준 확보 (`tdd=false`, `domain_risk=false`)
- [ ] T2 — 기준 처리량과 첫 병목 판정 (`tdd=false`, `domain_risk=false`)
- [x] T2.1 — 주입·관측 분리와 시험 모드 구현 (`tdd=true`, `domain_risk=false`)
- [x] T2.2 — 처리량·지연·잔량 판정과 증빙 구현 (`tdd=true`, `domain_risk=false`)
- [x] T2.3 — 측정 도구 검토와 낮은 부하 실구동 검증 (`tdd=false`, `domain_risk=false`)
- [ ] T2.4 — 수정 전 두 시험의 경계 탐색·10분 지속 확인 (`tdd=false`, `domain_risk=false`)
- [ ] T3 — 실측 병목의 재현·개선·회귀 검증 (`tdd=true`, `domain_risk=true`)
- [ ] T4 — 개선 후 동일 조건 한계 측정과 다음 병목 반복 (`tdd=false`, `domain_risk=true`)
- [ ] T5 — 최종 검증·보고서·컨텍스트·아카이브 정리 (`tdd=false`, `domain_risk=true`)

T2.1 → T2.2 → T2.3 → T2.4 순서로 진행한다. T2.4의 실제 병목 증거가 T3의 선행 조건이다.
T4에서 다음 병목을 찾으면 T3/T4를 반복하며, T2는 T2.1~T2.4 결과와 첫 포화 근거가 있을 때 완료한다.

## T1 — 전용 환경과 계측 기준 확보

완료: 기존 quality 환경, 빈 DB·Redis·Kafka·새 볼륨 대조와 전수 주문/승인/재고 검증, 실행별 원자료·자원·소스 증거 확보.
기존 500ms 조건의 10/20/40/80/s 탐색 완료. 160/s는 유입 미달로 미확정이다.
이 완료를 새 모드의 판정기·실구동 검증 완료로 확대하지 않는다.

## T2.1 — 주입·관측 분리와 시험 모드

대상: `scripts/k6/quality-payment.js`, `scripts/quality/run.py`와 새 관측 모듈, 관련 Python 테스트.
입력: 모드·rate·기간·VU·조회 정책. 출력: 시도별 시작·접수·사용자 관측 기록과 실행 모드/설정 증거.
용량 시험은 checkout/confirm 이후 완료 대기로 주입 슬롯을 점유하지 않는다.
품질 시험도 요청 주입과 사용자 조회 자원을 구분해 누락·관측 슬롯 포화를 따로 기록한다.
기존 500ms 모드의 재현 경로와 원래 판정은 보존하고 새 모드와 명확히 구분한다.

완료 기준:

- 지연된 완료·다수 진행 중 주문이 있어도 관측 대기가 주입을 직접 막지 않음을 모의 서버에서 확인
- 품질 조회는 같은 구매의 orderId를 사용하고 표본 관측이 새 구매를 몰래 추가하지 않음
- 1초·최대 30회 정책, 관찰 종료·예외·응답 유실을 원자료에 기록하고 접수 미확인을 실패/미접수로 단정하지 않음
- 기본/명시 모드·파라미터 범위·발생기 자원·실제 명령을 증빙하며 누락을 숨기지 않음

검증: 행동 재현 테스트 RED→GREEN, 실제 k6+모의 HTTP 계약 확인, Python 전체 도구 테스트.

## T2.2 — 처리량·지연·잔량 판정과 증빙

의존: T2.1. 대상: `scripts/quality/results.py`, `settlement.py`, `report.py`와 관련 테스트·`docs/quality/`.
기존 모듈의 책임을 확인해 필요한 범위만 변경한다. DB 완료 이력과 사용자 관측을 별도 지표로 만든다.
입력: 시도·접수·조회 기록, 완료 이력·주문·PG·재고 스냅샷, 실행 구간·자원 시계열.
출력: 유입 무결성, 품질 목표, 지속 처리/폭주 흡수/미확정 판정과 근거·종료 후 해소 시간.

완료 기준:

- 접수 p95와 완료 p95/p99를 혼동하지 않고 분모·시작/종료 시각·워밍업 제외를 명시
- 부하 중 DB DONE 처리율과 종료 후 완료를 분리하고 미종결·미확인 주문을 포함한 전수 대조 수행
- 사용자 조회 종료 뒤 완료를 사용자 기한 내 성공으로 소급하지 않음
- 유입 누락·발생기 포화·접수 미확인·증거 누락이면 최대 TPS 판정은 미확정
- 잔량 추세·유입 대비 완료·메모리와 종료 후 해소 근거 없이 지연 허용 지속 처리 PASS를 내지 않음
- DB 이력 시각은 실제 commit 순간과 같다고 가정하지 않고, Kafka TX 표식 lag도 업무 적체로 오판하지 않음
- 과거 결과의 설정·판정을 보존하고 새 SLO로 자동 재판정하지 않음

검증: 늦은 완료/기한 경계/누락/적체 지속 증가/해소/미확인/정합성 실패의 RED→GREEN, HTML 근거 링크·표시 확인.

## T2.3 — 도구 검토와 낮은 부하 실구동

의존: T2.2. 대상: 변경 도구·문서·quality Compose·새 `live-drill/` 실행 자료.
reviewer가 도구 변경·판정·계획을 독립 검토하고, domain-expert가 완료/중복/부분 실패/정합성 판정 경계를 검토한다.
제약으로 독립 호출이 불가능하면 메인 검토 결과와 미실시를 구분한다.

완료: Python 전체·모의 계약·Compose 렌더·문서 strict·diff 통과 후,
각 모드의 새 빈 환경에서 낮은 부하를 실행해 실제 주입·완료·조회·정합성을 대조한다.
기존 폴링 모드의 동작도 재현한다. 검증이 끝나기 전 고부하 기준을 새 모드로 확정하지 않는다.

## T2.4 — 수정 전 한계 탐색

의존: T2.3. 대상: 실제 quality 환경과 실행별 원자료·보고서.
기본 결과 소비 동시성1·동일 JAR·서버 자원·내구성·관측 설정으로 두 시험의 기준을 각각 새로 만든다.
각 조건을 3분 탐색하며 유입 미달이면 발생기와 서버 적체를 분리하고 경계를 좁힌다.
후보 한계는 10분 지속 확인한다. 초기 구간과 후반 추세를 나누며, 경계가 불명확하면 반복하거나 미확정으로 남긴다.

완료: 각 시험의 최고 품질 통과/지연 허용 지속 처리 구간·첫 미달 부하·탐색 간격·완료 지연·잔량·해소 시간·첫 병목 근거.
부하 발생기/호스트 한계로 더 측정할 수 없으면 그 제약과 확인 가능한 하한을 보고하고 서버 상한을 발명하지 않는다.

## T3 — 병목 재현·개선·회귀 검증

의존: T2.4. 실제 병목 모듈과 선택한 변경·대안·유지할 불변식을 근거와 함께 이 PLAN에 추가한 뒤 구현한다.
상태·동시성·멱등성·보상·트랜잭션·PG 실패 경로는 domain-expert와 reviewer가 같은 변경 기준으로 독립 검토한다.

완료: 재현 실패→수정 후 통과, 영향 모듈 테스트·정적 검사와 필요한 DB/Kafka 통합 검증, 지적 처리.
Java 변경: `./gradlew :<module>:test :<module>:checkstyleMain :<module>:checkstyleTest :<module>:spotbugsMain :<module>:spotbugsTest`.
DB·Kafka·Spring 배선 변경: `:<module>:integrationTest`. 단순 설정 변경에는 적합한 설정·실구동·실패 경로 검증을 적용한다.

### T3 사전 검증 준비 — 결과 소비 동시성 후보

160/s에서 명령·결과 양쪽 적체와 PG 승인 후 지연을 확인했으므로 결과 소비1→3을 첫 비교 후보로 둔다.
단독 원인·효과는 미확정이며 T2.4의 후보10분 확인 전 서버 기본값을 바꾸지 않는다.
독립 domain-expert는 기존 주문 잠금·결정적 상품별 키·DB/Kafka TX 배선 유지 시 후보 설계를 통과시켰으며, 다음 검증을 요구했다.

- 신규 테스트 전용3파티션/concurrency3: 각child 실제 배정, 독립주문·동일상품·중복결과의 DONE/dedupe/hold/재고 이벤트 키 불변식
- 대상 주문/파티션에 한정한 EOS commit 실패1회와 반복 실패: 다른 두 파티션 진행·재배달 복구·DLQ와 재고 미복구 구분
- 미커밋 입력을 둔 listener 재시작·재배정 후 offset 수렴. JVM 강제 종료·재시작과 실제 상품 재고차감1회는 별도 소량 실구동
- 기존 실패 보상·격리·금액 불일치 회귀 유지. 기본값 채택 전 reviewer/domain-expert 독립 검토와 모듈 검사

현재는 additive test 파일만 준비한다. 성능 실측과 Gradle/통합 테스트를 동시에 실행하지 않으며 실행 중 JAR·서버·측정 도구는 유지한다.

## T4 — 개선 후 한계 재탐색

의존: T3. 변경 요소 외 조건을 고정하고 T2.4의 두 시험을 각각 다시 수행한다.
동일 rate 전후 비교와 최고 지속 처리량의 변화를 함께 보고한다.
효과 없음·회귀이면 변경을 수정/제거하거나 보류 근거를 남긴다. 다음 병목이 있으면 T3/T4를 반복한다.
완료: 개선 효과·정합성·다음 병목 또는 자원 한계·효과 정체의 근거.

## T5 — ship

의존: T4. 변경에 맞는 정상·중복·실패·복구를 확인하고 reviewer/domain-expert의 완료 검토를 수행한다.
[workflow-ship](../.agents/skills/workflow-ship/SKILL.md)에 따라 변경 영역 컨텍스트와 HTML을 동기화하고
`docs/archive/payment-throughput/COMPLETION-BRIEFING.md`에 목적·수정 전후 한계·변경·검증·제약을 정리한다.
STATE와 추적 이슈의 완료 상태를 실제 결과에 맞춰 정리한다. 커밋·푸시·PR·병합은 요청 범위를 따른다.

## 공통 검증과 재개 위치

- 도구: `python3 -m unittest discover -s scripts/quality -p 'test_*.py'`
- 문서: `python3 scripts/check-agent-docs.py --strict`, 상대 링크·표시·`git diff --check`
- Compose: 현재 다섯 quality 파일의 병합과 실제 런타임 env/파티션 배정 대조
- 실제 재개: T2.4 — 새 환경 capacity100/s180초. 60/s 품질 시험 완료·볼륨 보존 종료, 100/s 기동/워밍업/본측정 미실행. 이후 경계 좁힘·후보10분·T3/T4 개선. 명령은 [인계](reports/tps-handoff.md) 참고

## 실행 기록

2026-10-28: discuss 범위 합의. Java 21·k6·Python 확인. Docker 접근 가능, 실행 중 컨테이너 없음.
서비스 변경·새 실측은 아직 없음. T1부터 실행한다.

2026-10-28 후속: 전용 `throughput-20261028` quality 환경 20컨테이너 기동.
결제 테이블·Redis·Kafka 18파티션의 빈 상태와 상품 100종 재고 시드 정합성 확인.
`preparation-01`(1시도/초, 30초)은 quality·settlement 모두 PASS.
측정 도구 테스트 65개, 문서 strict·로컬 링크 검사 통과.
원자료: `live-drill/throughput-20261028/`.
사용자는 Chrome 관리자·지표 캡처와 필요 시 로컬 임시 로그인 해제를 허용했다.
그러나 Computer Use는 Chrome 미승인, Codex 앱 자체 제어는 안전상 불허를 반환했다.
권한 변경을 우회하지 않고 사용자 측 Chrome 사용 권한 설정을 기다린다.
로그인 차단은 아직 확인하지 못했으며 인증 관련 코드 변경은 없다.
기준 10시도/초 실측·개선은 미수행이며 T1의 계측 확인을 이어간다.

2026-10-28 Chrome 재실행 후: 확장 호스트 오류는 남지만 Chrome 네이티브 앱 접근 복구.
관리자 목록에서 총 31건·표시된 DONE 주문, Grafana에서 DONE 31·IN_PROGRESS 0·QUARANTINED 0 확인.
두 화면을 Computer Use로 캡처해 시각 확인했으며 이미지 파일로 별도 저장하지는 않았다.
관리자와 Grafana 모두 로그인 없이 접근 가능해 인증 코드 변경은 불필요했다.
T1 완료. 기존 상태 전이 지표의 커밋 전 기록·상태 체류 시간 한계는 이후 판정에도 유지한다.

2026-10-28: warmup-01(10시도/초·120초) 1,201건 전부 DONE, quality·settlement PASS, confirm p95 21ms.
normal-01(10시도/초·600초) 기준 측정 시작. 아직 결과 미확정.
Grafana 발행 vs 종결 패널의 rate 제목과 increase 식 불일치 발견. 기준 측정 중 설정은 변경하지 않음.

2026-10-28: normal-01 완료. 6,001건 전부 DONE·5초 내 관측, dropped/API 오류/접수 미확인 0.
confirm p95 12ms, quality·settlement PASS, 잔여 미처리·정합성 오류 0.
Grafana 누적 DONE 약 7.23K·현재 IN_PROGRESS/QUARANTINED 0 확인. 총계는 준비·워밍업 포함.
DONE 가드 재수신 지표도 관측해 post-normal-01-duplicate-counters.json으로 보존. 중복 원인은 미확정.
10시도/초 최대 용량으로 해석하지 않음. 기준 재현 회차·증가 부하·서비스 개선은 아직 남음.

2026-10-28 클린 회차 r10-a: 기존 스택 볼륨 보존 종료, 새 20컨테이너·14볼륨 생성.
Kafka /etc/kafka/secrets 익명 볼륨도 기동 전 목록과 대조해 신규임을 확인.
빈 테이블·Redis·18개 Kafka 파티션 검사 PASS, 동일 100종 재고 시드.
워밍업 1,201건 전부 DONE·quality/settlement PASS, confirm p95 20ms. 본측정 진행 중.

2026-10-28 클린 회차 r10-a 완료

본측정 10시도/초·600초: 6,000건 전부 DONE·5초 내 관측, 오류·누락·미확인 0, confirm p95 12ms.
quality·settlement PASS. 다음 r20-a는 새 스택·볼륨에서 워밍업 후 20시도/초·180초 탐색.
탐색 회차는 적체 구간을 좁히는 목적이며 최종 개선 전후 비교는 동일 600초 조건으로 재검증한다.

2026-10-28 클린 회차 r20-a 완료: 새 20컨테이너·14볼륨·빈 상태 검사 PASS.
동일 워밍업 후 20시도/초·180초 3,601건 모두 DONE·quality/settlement PASS, confirm p95 13ms.
클린 r10-a/r20-a의 완료 관측 p95는 각각 517/519ms이며 폴링 간격 포함.
보고서: [클린 환경 측정 결과](reports/tps-clean-measurements.html).
다음은 다시 새 환경에서 r40-a 탐색. 첫 병목·서비스 로직 개선·최종 복구 검증은 아직 남음.
현재 r20-a 환경은 유지 중이며 다음 회차 시작 전 볼륨 보존 종료한다.

2026-10-28 재부팅 후 재개: Chrome 네이티브 접근 정상, 확장 호스트는 여전히 불가.
이전 r20-a의 관측 컨테이너 일부만 자동 재시작돼 볼륨 보존 down 실행.
빌드 JAR 해시가 이전 회차와 모두 같음을 확인한 뒤 새 r40-a 환경 구성 시작.
40시도/초·180초 탐색, 사전 워밍업은 동일 10시도/초·120초.

2026-10-28 r40-a 완료: 새 스택·14개 새 볼륨·빈 상태 검사 및 관리자 주문 0건 확인.
워밍업 1,200건 PASS 후 본측정 7,200건 모두 DONE·quality/settlement PASS.
confirm p95 17ms, 완료 관측 p95 524ms, 오류·누락·접수 미확인·미처리·정합성 오류 0.
Chrome 네이티브로 관리자·Grafana 40건/초 화면 확인·대화 내 캡처. 인증 코드 변경 없음.
수집 중 DB 연결 대기 표본 0. 최대 처리량은 아직 미확정, 다음은 새 r80-a 환경 탐색.

2026-10-28 r80-a 시작: JAR 해시가 r40-a와 동일함을 확인.
이전 스택 볼륨 보존 종료 후 새 스택·볼륨에서 동일 워밍업과 80시도/초·180초 탐색.

2026-10-28 r80-a: 예정 14,400건 중 실제 시작 14,362건, k6 dropped_iterations 39.
시작된 주문은 전부 DONE·5초 내 완료, API 오류·접수 미확인·수량 불일치 0이나 유입 미달로 INCONCLUSIVE.
부하 중 고유 주문 URL로 10만 이상 시계열 경고, VU 사전 할당 100에서 실행 중 139까지 증가.
원인 분리를 위해 T2 내 측정 도구 보완(tdd=true, domain_risk=false) 수행.
상태 URL name 태그 고정, 불변 유입 오류만 남은 추가 관찰 종료(미확정 판정 유지).
관찰 종료 회귀 테스트 3개 RED 확인 후 총 68개 GREEN, k6 모의 서버로 서로 다른 주문의 지표 이름·URL 집계 확인.
도구 수정은 r80-a 부하 종료 후 수행해 해당 실행의 sourceChanged 기록 대상. 원래 시나리오는 실행별 source/에 보존됨.
다음 r80-b: 다시 새 환경, 80시도/초·180초 유지, 본측정 VU 650 사전 할당과 URL 집계 보완. 서버 로직은 동일.

2026-10-28 r80-b 종료: sourceChanged=false, 14,239건 모두 60초 내 DONE·API 오류 0.
dropped 162로 전체 INCONCLUSIVE, 5초 내 완료율 62.2%로 목표 미달.
confirm p95 57ms, 완료 관측 p95 10,127ms·최대 12,872ms.
정합성 도구도 유입 유효성 때문에 INCONCLUSIVE이나 수량 불일치·실제 미처리·접수 미확인 0.
동일 시각 파티션 합계의 lag 표본 최대: 결과 606, PG 명령 51, 재고 3. DB 연결 대기 표본 0.
긴 지연은 checkout보다 confirm 접수 뒤 관측되며 결과 소비 구간을 우선 후보로 판단.
80건/초 최대 용량을 확정하지 않고, 다음은 새 환경에서 같은 80건/초의 결과 소비 동시성 비교 검토.
서버 로그는 r80-b/server-logs/에 보존. 실행·결과 보고서 갱신. 서비스 로직·동시성 설정은 아직 변경하지 않음.
독립 검토는 기존 사용량 제한으로 미실시, 메인이 코드·회귀 테스트·원자료를 대조.

최종 갱신 검증: Python 68개 PASS, 실제 k6 모의서버 지표 집계 PASS, 문서 strict·diff 검사 PASS.
HTML 5개 결과 행·27개 로컬 링크·구조 검사 PASS. 최신 시각 검수 시 CUA Transport closed로 미완료.
앞선 80-a Grafana 화면 캡처와 최신 파일 검증을 구분하며, 80-b 지연 수치는 원자료·시계열·로그 근거다.

2026-10-28 클린 비교 실험 재개: T2에서 같은 80시도/초·180초·VU650의 대조군 r80-c(소비 동시성1)와 실험군 r80-d(3)를 각각 새 스택·볼륨으로 실행한다.
quality Compose에 실험 환경변수 QUALITY_CONFIRMED_CONCURRENCY를 추가하되 기본1·JAR·heap·내구성·상태전이 로직은 유지한다.
Compose 1/3 렌더 결과에서 해당 JVM 프로퍼티 외 차이 없음 확인. 실제 consumer group 멤버·3파티션 배정과 실행 환경값을 증빙한다.
부하 유입·5초/60초 완료·관측 p95·topic lag·정합성을 비교하며 유입 누락 시 PASS로 승격하지 않는다.
이 비교만으로 운영 기본값 변경이나 실패·보상 경로 안전성을 확정하지 않는다.

클린 r80-c 완료: 14,400건 모두 5초 내 완료, 요청 누락·API 오류·접수 미확인·미처리·정합성 오류0.
quality/settlement/overall PASS, sourceChanged=false, confirm p95 33ms·완료관측 p95 1,553ms·최대3,099ms.
결과 lag 표본 최대83, Hikari pending 표본0. concurrency1도 유효한80/s 탐색 통과로 80/s 한계 가설은 성립하지 않는다.
이전 r80-b 로그 독립분석: 초기 지연 뒤 약105초 이후 따라잡음, 수신→DONE p95 8ms. relay완료→수신 p95 9,438ms이나 음수표본/47주문 로그부재 있어 정확한 브로커 체류시간으로 해석하지 않는다.
reviewer 도구·설정 diff PASS(Python68개), domain-expert 실험설계 PASS. r80-d 신규환경 동시성3 비교 시작.
Kafka broker는 초기+반복적인 offset혼용 WARN 다수 기록. enable.auto.commit=false 확인, 원인은 추가 조사하며 비교 도중 변경하지 않는다.

클린 r80-d 완료: 80/s·180초·VU650, 결과 소비자3개에 각각1파티션 배정 확인.
14,401건(종료 경계+1) 모두5초 내 DONE, 누락/API오류/미확인/정합성오류0, quality/settlement/overall PASS·sourceChanged=false.
confirm p95 34ms·완료관측 p95 544ms·최대1,207ms. confirmed lag 표본 최대0, 종료시결과lag0.
c/d는 JAR동일·이미지rootfs/config동일(Compose 프로젝트명라벨 제외), 실제환경동시성1↔3 외 차이없음,각35개증빙해시일치,14개씩새볼륨상호비중복 검증PASS.
한쌍의탐색에서 지연1553→544ms·결과lag83→0 관측. 초기편차가 크므로 개선배수/최대처리량/운영기본값채택을 확정하지 않음.
product group 종료lag 합3은 각파티션 마지막 TX COMMIT marker임을 broker log deep-iteration으로 확인. 잔여 업무 이벤트 아님. stock-final-tx-markers.txt에 payload 없는 메타데이터 보존. Hikari pending 표본은d의user1, 나머지0.
초기 커밋 혼용 WARN은 기본 LATEST_ONLY_NO_TX와 정상TX의혼용만으로반복될수있음(Signal counts !=업무비TX커밋횟수).
LATEST_ONLY 변경은도메인검토상별도실험가능하나 신규그룹초기TX커밋성공게이트·재시작/재배달검증필요,이번에는코드변경없음.

2026-10-29 재개: 부하·관찰 프로세스 종료와20컨테이너 healthy 확인. c/d 최종PASS·sourceChanged=false·원자료 보존 확인.
보고서 Chrome 첫 화면·하단·이전회차/원자료 펼침 시각확인, 대화에스크린샷표시(이미지파일저장없음).
최신 HTML7개결과행·44개로컬링크·문서strict57개문제0·diff PASS.
추가 HTML독립검토는사용량제한으로미완료, 메인이원자료·표시·링크검수. 이전도구/설정·도메인독립검토PASS와구분.
Java수정없으므로Java단위/정적/통합검사미실행. 현r80-d스택·모든이전볼륨유지,커밋/외부게시없음.

2026-10-29 r160-a 종료: expected28,800/started10,062/dropped18,738, 모두60초내DONE·API오류0·수량불일치0이나 유입미달로INCONCLUSIVE·sourceChanged=false.
접수p95694ms·완료관측p9516,738ms/최대21,680ms, 5초내136건(1.35%). 최종 커밋된 DONE 이력의 기록 시각 기준 부하 구간9,426·이후636(실제 commit 순간 계측 아님).
결과lag표본628·payment Hikari pending표본411. 발생기650명제한과조회경쟁이섞여상한확정불가.
사용자질문에따라품질한계와지연허용지속처리한계를분리하는측정방향논의. 주입/관측분리용량시험+기존전수폴링품질시험별도운영제안, 새모드구현/재실측아직없음.

2026-10-29 워크플로우 재정렬: 사용자 합의한 두 시험·자체 SLO·지연 허용 지속 처리 판정을 discuss 문서에 확정.
기존 실행 기록과 원자료는 보존하고 T2.1~T2.4로 측정 도구 보완·검토·기준 탐색을 구체화했다.
추적 이슈 #162 생성(enhancement/test, hyoguoo), 실제 본문·OPEN 상태 검증. 로컬 main에서 #162 브랜치 생성, 기존 미커밋 변경 보존.
최신 계획은 메인이 discuss-ready/plan-ready 항목·실제 코드 경계·로컬 링크를 대조했다. 추가 독립 계획 검토는 아직 미실시이며 T2.3의 독립 도구 검토와 구분한다.
문서 strict·작업 문서 로컬 링크 8개·diff 통과. 이번에는 Java/도구 코드·스택·실측 변경 없음, 커밋·푸시·PR 없음.
다음 실행은 T2.1 — 주입·관측 분리와 시험 모드의 재현 테스트 RED부터다.

2026-10-29 컴팩트 전 체크포인트: [인계·미해결 항목](reports/tps-handoff.md)에 서버 후보별 수정/보류·현재 수치의 의미·재개 절차를 통합했다.
160/s 결과를 HTML에 반영하고 정적 분석의 PG producer 설명을 정정했다. 초기 자료와 최신 합의/미구현 상태를 구분했다.
최신 계획·인계·보고서 reviewer와 domain-expert 검토 PASS. DONE 이력의 기록 시각을 실제 commit 순간으로 읽지 않도록 보완했다.
사용자 요청으로 워크플로우 지침과 성능 도구/분석/합의를 별도 로컬 커밋한다. 별도 요청 추적 문서·STATE 사용자 구간·live-drill 원자료는 이번 커밋에서 제외한다.
Python68·문서strict57개문제0·Compose기본1·상대링크·diff·최신HTML Chrome 시각확인 통과. Java 변경/새 실측/푸시/PR 없음.
컴팩트 후 T2.1의 RED부터 시작한다. 새 모드 구현·기준 한계 확정·T3 개선·최종 T5 완료는 아직 남음.

2026-10-29 T2.1 완료: 기존 legacy(기본)·capacity·user-quality 모드를 분리했다.
새 두 모드의 k6 VU는 checkout·confirm 뒤 즉시 반환하며 같은 주문을 별도 Python 관측기가 조회한다.
관측은 1초·최대 30회·31초 기한, 요청은 남은 기한으로 제한한다. 포화·늦은 스케줄·HTTP/파싱 오류·202 미확인·기한 이후 응답을 원자료로 보존한다.
HTTP/1.1 연결 재사용과 실패 연결 해제, 기한 직전 응답의 callback 경합을 보완했다.
RED→GREEN: 실제 k6+모의 HTTP 계약 4개·모드/피드 5개·관측 10개 포함 전체 Python 87개 PASS.
축소 주기 테스트의 인접 간격 가정을 절대 예정 시각 이전 발사 금지로 정정했으며 독립 reviewer 검토 PASS.
독립 검토 지적의 deadline 경합·실패 연결 누적은 수정과 회귀 검증으로 해소했다.
새 모드는 T2.2 판정기 전까지 schema2·INCONCLUSIVE로 제한한다. 실제 스택 기동·재시드·새 실측·Java 변경·커밋/게시 없음.
별도 요청 추적 문서 4개 해시·STATE 사용자 구간 보존 확인. 증거 `live-drill/throughput-tools-t21-20261029/`.
다음은 T2.2이며 observer errors/feedErrors/incomplete와 사용자 원자료를 판정 입력에 포함한다.

2026-10-29 T2.2 완료: schema2 새 모드의 품질 목표와 처리 구간 분류·원자료·HTML을 연결했다.
전체 시작 건수를 백분위 분모로 유지하고 미관측은 무한 지연으로 다룬다. 관측 FAILED와 DB DONE 충돌, 확인된 업무 실패는 백분위와 독립된 FAIL이다.
미확인 접수만으로 알려진 주문의 완료/재고 관찰을 종료하지 않는다.
10초+수집비용 간격의 현재 DB 상태·product 재고 확정·결제/전체 파이프라인 잔량·5앱 heap/20컨테이너 메모리/host swap을 수집한다.
고정 평가 시각의 PromQL timestamp()로 원본 표본 신선도를 확인하고 30초 초과는 근거 부족 처리한다.
유입 후반 60초 이상·4개 이상·간격15초 이내의 결제/재고 잔량과 메모리 관측 범위를 비교한다. GC·잔량의 유한 변동과 지속 증가를 구분하며 프로젝트 자체 판정 정책을 결과와 RUNBOOK에 노출했다.
DB DONE 기록 시각의 처리율/백분위/드레인과 재고 포함 전수 대조 완료 관측 상한을 구분한다. 워밍업은 phase로 명시하고 목록·본문 모두 한계 산정 제외 표시를 붙였다.
독립 reviewer·domain-expert의 major 지적은 회귀 테스트로 RED→GREEN 후 재검토 PASS. Python122개·py_compile·문서strict57개문제0·diff 통과.
기존 보존 스택에서 구매 없이 수집기 읽기 계약만 확인: 기존11,262건 DONE/재고확정11,262·잔량0, 메모리26지표. 새 TPS 실측이나 새 기준 결과가 아니다.
모의 HTML을 EXCLUDED로 표시하고 Chrome 렌더·상대 근거 링크 확인. 단위 테스트·기존 원자료·별도 요청 추적 변경 보존.
근거 `live-drill/throughput-tools-t22-20261029/`. Java/서버로직·운영 동시성 변경·신규 부하·커밋/게시 없음.
다음 T2.3: 새 환경에서 모드별 낮은 부하 계약·정합성 검증. 이후 T2.4의 새 기준 경계 탐색으로 진행한다.

2026-10-29 T2.3 완료: capacity/user-quality/legacy 각 새 프로젝트에서20컨테이너·14개새볼륨·빈DB/Redis/Kafka·동일시드·10/s120초워밍업 후1/s180초를 검증했다.
본측정은180/181/180건 시작·모두전수대조PASS·누락/오류0·소스불변. 사용자품질181건 모두관측·조회오류/포화0, 완료p951032ms·p991037ms.
새두모드의 지연목표는 충족했으나 메모리범위확장으로 전체지속분류INCONCLUSIVE를 원형보존했다. legacy는원기준PASS. 이 저부하검증을서버최대TPS로해석하지않는다.
JAR해시동일·새볼륨42개상호분리·이전볼륨보존·각회차서버로그6개보관. [도구 검증 요약](reports/tps-tool-validation.md), `live-drill/throughput-split-validation-20261029/validation-summary.json`.
각환경을 볼륨보존down후 다음환경을기동했다. 현재T2.4 capacity-r80-a 새환경에서80/s180초 수정전시험진행, 소비동시성1·정상VU650/관측최대256명시.
T2.4의 품질목표 충족과 전체 지속처리/자원미확정을 각각 보고하며, 최초포화증거전 서버로직을수정하지않는다.

2026-10-29 T2.4 첫80/s capacity-r80-a:14,401건시작/완료·dropped0·API오류0·불일치0·sourceChanged=false이나,기존경계룰의boundaryStarts2로원판정INCONCLUSIVE.
마지막두시작은nominal끝+1/+13ms, 전체예정대비최대lag23ms였다. 원자료/원판정/구드라이버와보조메타데이터를보존하고공식한계근거에서EXCLUDED.
새모드만연속iteration·총량·dropped·실제예정대비-1..100ms시각정밀도를검사해legacy의경계건수룰을대체했다. 모든실제시작은분모/대조에유지하며legacy results.py불변.
100ms는프로젝트계측허용치로명시했고초과/번호누락/접수불명은계속미확정. RED→GREEN 및전체Python124개PASS. 기존reviewer는사용량한도로호출불가, 독립domain-expert가이변경만재검토PASS.
첫회차루트experiment의구기본VU100기록은원형보존하고normal실제명령650·runtime vus_max650을supplement에명시했다. 다음v2드라이버는정상650/워밍업100/관측256을명시하며드라이버원본도보존한다.
소스증빙은품질문서의현재바이트와diff도보관하도록보완. 다음capacity-r80-b는새컨테이너/볼륨에서수정판정으로재시험한다. 서버Java/운영기본값변경없음.

2026-10-29 capacity-r80-b 재시험완료:80/s180초14,400건전량DONE/전수대조PASS·누락오류0·sourceChanged=false·arrivalVALID(maxlag18ms).
접수p95checkout37/confirm50ms, DONE기록지연p953954/p994666ms. 전체지속분류는메모리범위확장으로INCONCLUSIVE,유입/정합성/지연지표는각각보존.
독립reviewer구간분해:첫30초DONE기록p954722ms→후150초722ms, 미완료306→22로부하중회복. 80/s지속실패증거는없고초기transient가주요현상. PG승인후결과반영경로가후보지만내부DB/ACK/TX원인은미확정.
동시성1·Hikaripending표본0, 결과토픽rawlag표본271(업무건수와동일하지않음). 새user-quality-r80-a 환경에서동일80/s사용자품질시험진행.

2026-10-29 user-quality80-a 완료:14,401건최종DONE·전수대조PASS·누락/API오류0·소스불변·arrivalVALID. 관측388,027회/포화skip12,779로전체사용자p95/p99미확정,기한내DONE1687건. DB완료기록p95122.8초·잔량지속증가·payment풀대기표본195로실제서버지연도확인. 정확한품질상한으로쓰지않고낮은부하에서유효관측구간탐색.
2026-10-29 capacity160-a 완료:28,801건전수DONE/PG/재고대조PASS, dropped/API오류0·arrivalVALID·sourceChanged=false. 접수checkout/confirm p95141/397ms이나 DONE기록p95109,981/p99117,668ms. 후반잔량9528→14906·최장대기61.8→103.4초, 입력종료후DONE기록88.4초/재고포함전수관측상한94.5초. 원분류BURST_ABSORBED, 160/s지속가능과구분. 모든로그/20컨테이너이미지근거보존후볼륨보존down. 다음은새환경user-quality40/s180초와capacity중간경계탐색.

2026-10-29 user-quality40-a 완료:40/s180초7,201건전수DONE·승인/재고대조PASS·dropped/API/관측오류/포화/미관측0·arrivalVALID(max12ms)·sourceChanged=false. checkout/confirm p9520/23ms, 사용자완료p951028/p991046ms, DONE기록p95322ms. 후반pending11→8/pipeline5→7로쌓임없음. 품질목표통과·전체지속은메모리범위UNRESOLVED로원INCONCLUSIVE유지. 다음capacity120/s/사용자60/s새환경경계탐색.

2026-10-29 capacity120-a 완료:120/s180초21,601건전수DONE·대조PASS·누락/API오류0·유입VALID·소스불변. checkout/confirm p9565/90ms, DONE기록p9562032/p9967620ms. 입력중DONE14,529/후7,072, 기록drain37.7초. 원분류BURST_ABSORBED. capacity경계80~120/s, 다음quality60/s와capacity100/s새환경탐색후후보10분. T3신규통합test4개작성·reviewer/domain수정재검토PASS, 빌드/실행검증은실측과분리해후속진행.

2026-10-29 컴팩트 전 중단: user-quality60-a60/s180초10,801건 전량기한내DONE·승인/재고대조PASS, 누락/API/관측오류/포화/미관측0·유입VALID(maxlag10ms)·sourceChanged=false. checkout/confirm p9530/38ms, 사용자완료p951045/p991079ms, DONE기록p95372/p99621ms. 후반pending15→18/pipeline10→12로품질목표통과, 전체지속은메모리범위UNRESOLVED로원INCONCLUSIVE보존.
사용자가100TPS시험직전에문서만정리해중단요청. 60회차로그/이미지증거보관·볼륨보존down, 부하/컨테이너종료확인. 100/s환경기동·시드·워밍업·본측정미실행, 다음v2명령과제약은인계/STATE/로컬RESUME에동기화.
T3신규Java통합test4개작성·qualifier/DLQoffset대기보정·독립reviewer/domain재검토PASS, DONE이력1회추가단정domain확인PASS. 컴파일/Java단위/정적/통합검사미실행. 실제프로세스복구로컬driver작성만완료, 모델사용량한도로독립리뷰미완료·실행미완료. 서버메인Java/JAR/기본동시성1미변경, 커밋/게시없음.

2026-10-29 후속 커밋 요청: 검증된 T2 도구·문서·실측 체크포인트를 로컬 커밋하고 미실행 T3 신규 Java 테스트는 작업 트리에 보존해 제외한다. 별도 요청 추적4문서·STATE 사용자 구간은 기존 변경으로 제외한다. 사용자가 다음에 “중단됐던 작업 진행”이라고 하면 STATE→인계→PLAN/로컬RESUME로 PAYMENT-THROUGHPUT를 선택해 새100/s180초부터 재개하도록 기록. 현재는 프로세스/컨테이너 기동·새 실측 없이 커밋·인계 정리만 진행.
커밋 직전 현재 도구 스냅샷의 Python124개(20.6초)·문서strict57개문제0·diff검사PASS. 서비스/실측 기동없음, 로컬보고서HTTP8872도종료. 신규Java테스트는커밋제외하므로Java검증미실행상태유지.
