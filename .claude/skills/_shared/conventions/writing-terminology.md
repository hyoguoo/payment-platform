# 문서 작성 컨벤션 — 용어·식별자

> `writing.md` 분할 파일. 인덱스: [`writing.md`](writing.md)

## 용어 선택 — 식별자 vs 도메인 표현

문서가 코드와 밀착되면 메서드 호출 narrative가 prose에 새어 든다.
식별자 노출 수준을 4단계로 구분해 적용한다.

### 4단계 룰

| 티어 | 대상 | 표기 | 예시 |
|:---:|:---:|:---:|:---:|
| T1 | 상태 enum / 설정 키 / 산업 표준 약어 | 백틱 또는 평문 대문자 | `IN_FLIGHT`, `PENDING`, `RETRY_LATER`, TOCTOU, CAS |
| T2 | 클래스 / 인터페이스 / 어댑터 / 포트명 | 백틱 식별자 | `PaymentEvent`, `IdempotencyStore`, `OutboxWorker` |
| T3 | T2 도메인 개념을 prose 본문에서 가리킬 때 | 한국어 도메인 어휘 | "결제 이벤트", "복구 결정", "보상 TX" |
| T4 | 메서드 호출 / 시그니처 | 한국어 행위로 치환 (prose 노출 금지) | `getOrCreate()` → "단일 원자 연산", `claimToInFlight` → "IN_FLIGHT 선점" |

판정 룰: 코드 인용 컨텍스트(코드 블록 / 구현 상세 절 / 클래스 정의 표)는 T1·T2 식별자 그대로, 그 외 prose 본문은 T3·T4 한국어 행위.

### 보존 가능한 메서드명 — 예외

prose에서도 보존하는 식별자가 있다.

- 표준 라이브러리 API: `LinkedBlockingQueue.offer()/take()`, `KafkaTemplate.send`, `Context.current()`, `MDC.put`
- 디자인 패턴 식별 메서드: Strategy 패턴의 `supports()`, Factory 패턴의 `getInstance()` 등 패턴 정체성을 이루는 메서드
- 라이브러리 customization 가이드: SLF4J `isInfoEnabled()`, Logback `addMaskPattern()`/`doLayout()` 같이 라이브러리 사용 설명

### 메서드 호출 narrative 금지

prose 본문에 "X.foo()를 호출하면 Y.bar()가 실행되어..." 형태 금지.
도메인 행위 묘사로 치환한다.

````markdown
# 잘못된 예
`OutboxProcessingService.process()`가 호출되면 `claimToInFlight`로 IN_FLIGHT 선점 후 `findPendingBatch()`로 PENDING 배치 조회.

# 올바른 예
복구 사이클이 IN_FLIGHT 선점 후 PENDING 배치를 조회한다.
````

### 즉석 코드 라벨 금지

설계 옵션·결정·방안을 `Option A/B`, `E1`, `방안 1`, `안 2` 같은 즉석 식별자로 부르지 않는다.
옵션은 그 **내용**으로 명명한다.

- 비교 섹션 안에서만 통하는 라벨은, 결정 테이블·요약 브리핑·plan·영구 문서로 새어 나가면 그 문서만 읽는 독자에게 의미를 잃는 dangling 참조가 된다.
- 예: "Option B" → "`pg_inbox.attempt` SoT 방식", "Option A" → "헤더 라운드트립 복원 방식".
- 예외: 프로젝트 표준 식별자(상태 전이 ID, TODOS 항목 코드, 토픽 코드)는 전역 참조용이라 유지한다.

---

## 동의어 통일

같은 개념을 한 가지 표기로만 사용한다.
프로젝트 내 표준은 아래와 같다.

| 의미 | 표준 | 금지 |
|:---:|:---:|:---:|
| stock restore | 재고 복구 | 재고 복원 |
| compensation TX (prose) | 보상 TX | 보상 트랜잭션 (단, mermaid 풀어쓰기는 의도적 보존 가능) |
| Worker | Worker | 워커 |
| skip | 건너뜀 | skip (mermaid `:::skip` CSS 식별자 제외) |
| lock | 락 | lock (코드 블록 / 시퀀스 액션은 예외) |
| 즉시 종료 (프로세스) | 서버 크래시 | 서버 장애 |
| 도메인 종결 (상태 머신) | 종결 | 종료, 결판 |
| 타임아웃 → PENDING 되돌림 | 되돌림 | 복구 (사이클 단위 "복구"와 구분) |
| 상태 기반 조건부 갱신 | 조건부 갱신 | CAS (CPU compare-and-swap와 혼동), claimToInFlight 등 메서드명 |

새 동의어 발견 시 위 표에 추가하고, 한 표기로 통일한 뒤 문서 전체를 sweep.

---

## 약어 풀어쓰기

도메인 약어와 산업 표준 약어는 첫 등장 시 1회 풀어쓰기 후 약어로 사용한다.

````markdown
# 올바른 예
재시도 한도가 소진되면 FCG(격리 전 최종 확인, Final Confirmation Gate)가 발동한다.
이후 FCG는 PG 상태를 1회 재호출하여...
````

풀어쓰기 대상 예: `TOCTOU`(Time-Of-Check-To-Time-Of-Use), `CAS`(Compare-And-Swap), `TX`(트랜잭션), `FCG`(Final Confirmation Gate).

영문 그대로 두는 관용 표현(`fast-fail`, `no-op`, `block-and-share`)은 한국어 대응어가 부정확한 경우만 허용한다.
