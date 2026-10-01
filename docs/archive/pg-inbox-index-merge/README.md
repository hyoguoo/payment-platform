# 힌트 없는 index_merge·데드락 재현

실험일: 2026-10-01 KST. MySQL 덤프의 시각은 UTC이므로 2026-09-30으로 표시된다.

조사 결과와 이관 범위는 [완료 브리핑](COMPLETION-BRIEFING.md)에 정리했다. 이 문서는 실험 조건·실행 방법과 원본 자료를 찾아보기 위한 기록이다.

사용자가 준비한 `mysql-server` 컨테이너(MySQL 8.0.46, 호스트 3306)에서 실행했다. 기존 `TEST` DB는 조회만 했으며, 모든 생성·수정은 별도 `codex_pg_index_merge_20261001` DB에서 수행했다.

## 확인한 내용

**UNIQUE 주문 번호 인덱스가 있는 원래 UPDATE에서, 힌트 없이 `index_merge`가 선택됐고 실제 데드락도 발생했다.**

- `INDEX_MERGE`·`FORCE INDEX` 등 인덱스 힌트를 사용하지 않았다.
- UNIQUE를 제거하거나, 원래 WHERE를 비동등 조건으로 바꾸지 않았다.
- `optimizer_switch`, 격리 수준, 비용 상수, 인덱스 통계를 인위적인 값으로 바꾸지 않았다.
- PK를 미리 붙잡아 두는 별도의 트랜잭션으로 데드락을 만들지 않았다.
- 원래 흐름의 `PENDING → IN_PROGRESS` 선점 트랜잭션은 커밋한 뒤, 외부 호출 지연을 기다리고 별도 결과 UPDATE 트랜잭션을 시작했다.

이는 당시 장애와 같은 종류의 문제를 현재 환경에서 재현한 것이다. 당시의 정확한 MySQL 패치 버전·데이터 스냅샷·발생률까지 복원한 것은 아니다.

## 1. 스키마와 데이터

프로젝트 V1~V7 마이그레이션을 별도 DB에 적용했다. V8은 변경 후 비교 단계에서만 적용한다.

관련 인덱스:

```sql
PRIMARY KEY (id)
UNIQUE KEY ux_pg_inbox_order_id (order_id)
KEY idx_pg_inbox_status (status)
```

주요 컬럼:

- `order_id VARCHAR(100) NOT NULL`
- `status ENUM('PENDING','IN_PROGRESS','APPROVED','FAILED','QUARANTINED') NOT NULL`
- `stored_status_result VARCHAR(1024)`
- `updated_at DATETIME(6) NOT NULL`
- 문자 집합 `utf8mb4`, 정렬 규칙 `utf8mb4_unicode_ci`

완료 이력 500,000건을 먼저 적재했다. 주문 번호는 서로 다르고, 초기 상태는 모두 `APPROVED`이다. 이력의 결과 문자열에는 약 400바이트의 본문을 넣었다. 이후 일부 행만 `IN_PROGRESS`로 바꿔 실행 계획을 비교했다.

실제 스키마와 설정은 [environment.json](artifacts/environment.json)에 있다.

기존 사용자 테스트 테이블은 `order_id VARCHAR(64)`, `status VARCHAR(20)`, 결과 `VARCHAR(255)`, `utf8mb4_0900_ai_ci`였다. 재현에는 프로젝트 스키마를 사용했다. 따라서 이전 사용자 실험과 결과가 다른 이유를 데이터 분포 하나만으로 확정하지 않는다.

## 2. 정적인 환경에서도 index_merge 선택

원래 WHERE를 유지한 쿼리:

```sql
UPDATE pg_inbox
SET status = 'APPROVED',
    stored_status_result = '{}',
    updated_at = NOW(6)
WHERE order_id = 'order-0500000'
  AND status = 'IN_PROGRESS';
```

최초 데이터 분포 실험 결과:

| 전체 행 수 | IN_PROGRESS | 선택한 계획 |
| ---: | ---: | --- |
| 500,000 | 1 | index_merge |
| 500,000 | 2 | index_merge |
| 500,000 | 10 | index_merge |
| 500,000 | 100 | index_merge |
| 500,000 | 1,000 | 주문 번호 range |
| 500,000 | 10,000 | 주문 번호 range |
| 500,000 | 100,000 | 주문 번호 range |
| 500,000 | 500,000 | 주문 번호 range |

각 분포에서 통계 갱신 전후를 모두 확인했다. 위 최초 증가 구간에서는 `ANALYZE TABLE` 전후의 계획 종류가 같았다. [전체 실행 계획](artifacts/matrix-summary.json)

이는 이 실험의 관측값이며, **100/1,000건을 일반적인 선택 임계값으로 사용하면 안 된다.**

전체 50만 행을 상태 변경한 뒤 다시 10건으로 줄인 직후에는 `range`가 나왔다. 나중에 상태를 추가로 바꾸지 않고 다시 확인하자 `index_merge`가 나왔다. 논리적인 상태별 행 수만으로 계획 전체를 설명할 수는 없다. 이 차이에 대한 purge·캐시·비용 추정의 개별 영향은 이번 실험에서 분리 측정하지 않았다.

초기 matrix의 EXPLAIN은 정상 수집했지만, optimizer trace는 실험 도구의 종료 확인 SELECT가 덮어쓴 오류가 있었다. 해당 파일에 `trace_capture_valid: false`를 표시했다. 비용 분석에는 아래의 수정 후 trace만 사용한다.

## 3. UNIQUE가 있는데 왜 병합 검색을 골랐나

근거: [trace-probe-current.json](artifacts/trace-probe-current.json). trace 잘림은 0바이트다.

이 trace에서 주문 번호는 예상 1건, 상태 조건은 예상 10건이었다. UNIQUE인데 여러 건으로 잘못 추정한 것이 아니다.

단독 검색 비용:

| 후보 | 예상 행 수 | cost |
| --- | ---: | ---: |
| 주문 번호 인덱스 단독 | 1 | 1.64349 |
| 상태 인덱스 단독 | 10 | 9.44419 |

그런데 두 인덱스의 병합 후보에서는 다음과 같이 계산됐다.

| 병합 후보에 포함한 인덱스 | matching_rows_now | 누적 인덱스 검색 비용 | disk_sweep_cost | 누적 전체 비용 |
| --- | ---: | ---: | ---: | ---: |
| 주문 번호 | 1 | 0.980542 | 0.766745 | 1.74729 |
| 주문 번호 + 상태 | 0.0000212812 | 1.23301 | 0 | 1.23301 |

최종 `chosen_range_access_summary`:

```json
{
  "type": "index_roworder_intersect",
  "rows": 1,
  "cost": 1.23301,
  "covering": false
}
```

이 결과에서 확인할 점:

1. UNIQUE의 예상 행 수 1은 유지되고 있었다.
2. 병합 후보에 상태 조건까지 반영하면 남을 행 수를 1보다 훨씬 작게 추정했다.
3. 이 후보에서 `disk_sweep_cost`가 0으로 계산됐다.
4. 결과적으로 병합 비용 1.23301이 주문 번호 단독 비용 1.64349보다 작아 선택됐다.

따라서 이번 실험에서는 **드문 상태 조건을 추가했을 때의 비용 추정 때문에 병합 검색을 선택했다**고 설명할 수 있다. 상태 인덱스가 있어 반드시 사용해야 했던 것도, UNIQUE를 인식하지 못했던 것도 아니다.

`matching_rows_now`는 예상값이다. 이 쿼리가 실제 0.000021행을 수정한다는 뜻이 아니다. 대상 주문은 실제로 `IN_PROGRESS`이다. 또한 `disk_sweep_cost=0`을 실제 UPDATE가 PK 레코드를 전혀 읽거나 잠그지 않는다는 뜻으로 해석하면 안 된다.

단독 range 비용과 병합 후보의 중간 비용은 서로 다른 비용 계산 경로의 값이다. 주문 번호 단계의 1.74729를 단독 range 비용 1.64349와 같은 숫자처럼 취급하지 않는다.

## 4. 동시 실행 실험

최종 비교용 러너의 구성:

- 10개 DB 연결, 연결별 서로 다른 주문 500건: 총 5,000건.
- 주문마다 `PENDING` INSERT.
- 짧은 선점 트랜잭션: PK로 `SELECT ... FOR UPDATE SKIP LOCKED`, `IN_PROGRESS` UPDATE, COMMIT.
- 트랜잭션 밖에서 100~300ms 대기. 당시 벤치마크의 외부 호출 지연 범위에 맞췄다.
- 별도 트랜잭션에서 원래 승인 결과 UPDATE, COMMIT.
- 준비 단계·결과 UPDATE·재시도 단계의 오류를 별도로 집계.
- 승인 UPDATE가 데드락으로 실패하면 롤백 후 동일 UPDATE로 재시도.
- 실행 도중 실제 대상 주문의 EXPLAIN과 trace를 세 번 수집.

전체 애플리케이션·Kafka·Outbox·복구 스케줄러는 실행하지 않았다. 이 실험의 범위는 `pg_inbox` 상태 전이와 결과 UPDATE의 DB 동시성이다. 재시도도 당시의 60초 복구 워커 대신 짧게 수행하므로 지연 지표나 장애 비율을 당시 수치와 직접 비교하지 않는다.

초기 탐색 실행 `1790782008`은 외부 대기를 1~5ms로 압축했고, 준비 단계 오류를 처리하지 않아 6개 워커가 종료됐다. 2,125회의 결과 UPDATE 시도 중 101건의 데드락을 확인했지만, **5,000건 비교 결과로 사용하지 않는다.** 오류와 덤프는 보존했다.

### 5,000건 비교 결과

| 항목 | 기존 `(status)` | 변경 후 `(status, updated_at)` |
| --- | ---: | ---: |
| 결과 UPDATE 첫 시도 | 5,000 | 5,000 |
| 첫 시도 성공 | 4,972 | 5,000 |
| 첫 시도 데드락 | **28 (0.56%)** | **0** |
| 다른 결과 UPDATE 오류 | 0 | 0 |
| 준비 단계 데드락 | 0 | 0 |
| 재시도 데드락 | 0 | 0 |
| 워커 중도 종료 | 0 | 0 |
| 실행 중 확인한 검색 계획 | index_merge | 주문 번호 range |

원본 결과: [변경 전](artifacts/concurrent-1790782129.json), [변경 후](artifacts/concurrent-1790782335.json).

실행 중 계획 표본: [변경 전](artifacts/trace-concurrent-1790782129-100.json), [변경 후](artifacts/trace-concurrent-1790782335-100.json).

각 조건 1회이며 같은 DB에 순차로 실행했다. 변경 후에는 앞선 실험의 완료 이력이 추가로 남아 있으므로 동일 스냅샷 비교는 아니다. 이 결과는 잠금 문제와 변경 후 동작을 확인하는 자료이며, 발생률·성능 차이에 대한 통계 실험으로 사용하지 않는다. 당시 보고서의 163건/5,000건과 이번 28건/5,000건은 서로 다른 실행이다.

실험 DB에 남은 `PENDING` 6건은 초기 탐색 실행에서 준비 단계 오류로 종료된 워커의 잔여 데이터다. 최종 두 실행에서 신규로 남긴 실패 건이 아니다. 변경 전 28건은 동일 UPDATE 재시도로 모두 승인 완료됐다.

최종 DB는 V8 적용 상태로 보존했다. [최종 환경과 실제 주문 ID](artifacts/environment-after.json)

### 변경 후 trace에서 확인한 병합 제외 이유

[변경 후 trace](artifacts/trace-concurrent-1790782335-100.json)에서 새 상태·시간 인덱스의 `status = 'IN_PROGRESS'` 검색은 `rowid_ordered: false`로 판정됐다. `analyzing_roworder_intersect`는 `usable: false`, `cause: too_few_roworder_scans`를 기록했다.

새 인덱스의 선두 상태 컬럼으로 범위 검색하는 후보 자체는 존재했다. 다만 기존 row ID 순서의 병합에 사용할 검색이 부족해져 intersection 후보가 제외됐고, 최종 계획은 주문 번호 인덱스의 `range_scan`이었다. 따라서 단순히 “복합 인덱스로 선택도가 좋아졌다”로 설명할 필요 없이, 실제 제외 이유까지 근거로 남길 수 있다.

## 5. 실제 덤프에서 확인한 잠금 충돌

가장 단순하게 읽을 수 있는 원본: [deadlock-1790782129-5-48.txt](artifacts/deadlock-1790782129-5-48.txt).

두 UPDATE는 서로 다른 주문을 지정했다.

| 구분 | 트랜잭션 ID | UPDATE의 주문 번호 |
| --- | ---: | --- |
| T1 | 15441 | `run-1790782129-5-48` |
| T2 | 15440 | `run-1790782129-4-46` |

충돌한 레코드의 PK는 `502596`이다. 덤프의 정수 저장 표기 `800000000007ab44`에서 부호 정렬 비트를 제외한 값이며, 덤프의 주문 번호 필드도 T1의 주문임을 보여준다.

| 트랜잭션 | 가진 잠금 | 기다리는 잠금 |
| --- | --- | --- |
| T1 | PRIMARY의 PK 502596, `X locks rec but not gap` | 상태 인덱스의 `(IN_PROGRESS, 502596)`, `X waiting` |
| T2 | 상태 인덱스의 `(IN_PROGRESS, 502596)`, `X` | PRIMARY의 PK 502596, `X locks rec but not gap waiting` |

양쪽 쿼리 상태는 `Searching rows for update`이다. T2의 WHERE는 다른 주문인데, 요청한 PK에는 T1의 주문 번호가 들어 있다. **“B를 수정하는 UPDATE가 A의 PK를 기다리는가”가 실제 덤프에서 확인됐다.**

상태 인덱스는 `lock_mode X`로 출력된다. 이 표는 충돌 레코드를 표시한 것이며, 상태 인덱스 잠금을 모두 `rec but not gap`인 순수 레코드 잠금이라고 단정하지 않는다.

다른 덤프에는 같은 트랜잭션이 여러 상태 인덱스 항목을 가진 경우도 있다. 따라서 모든 데드락이 반드시 이 가장 단순한 두 레코드 모양이라고 일반화하지 않는다.

덤프는 충돌 시점의 보유·대기 관계를 증명한다. 각 인덱스 접근의 시작 시각과 모든 이전 획득 순서를 직접 기록한 실행 추적은 아니다. 당시 8월 사건의 원본 덤프와도 구분한다.

## 실행 방법과 보관 자료

스크립트: [reproduce.py](scripts/reproduce.py). Python 표준 라이브러리와 Docker 컨테이너의 mysql CLI를 사용한다. 비밀번호는 컨테이너 환경 변수에서 읽고 출력하지 않는다.

Payment Platform 저장소 루트에서 실행한다.

```sh
# 최초 한 번만: 같은 이름의 DB가 있으면 실패하며 기존 DB를 지우지 않는다.
python3 docs/archive/pg-inbox-index-merge/scripts/reproduce.py init

# 별도 실험 DB의 상태 분포를 변경한다.
python3 docs/archive/pg-inbox-index-merge/scripts/reproduce.py matrix

# 현재 데이터에서 원래 UPDATE의 계획과 비용 기록.
python3 docs/archive/pg-inbox-index-merge/scripts/reproduce.py probe

# 기존 상태 단일 인덱스에서 5,000건 실험.
python3 docs/archive/pg-inbox-index-merge/scripts/reproduce.py concurrent

# V8 인덱스 변경 후 같은 러너로 5,000건 비교.
python3 docs/archive/pg-inbox-index-merge/scripts/reproduce.py composite
```

`concurrent`는 준비 과정에서 이 실험 DB의 기존 `IN_PROGRESS`를 정리한다. 운영용 러너가 아니다. `composite`는 V8을 적용하므로 같은 DB에서 중복 실행할 수 없다. 컨테이너 이름은 당시 환경의 `mysql-server`를 사용한다. 프로젝트 루트는 스크립트 위치에서 찾고, 실행 결과는 이 조사 폴더의 `artifacts/`에 저장한다. 재실행 시 동일한 이름의 환경·trace 파일은 갱신된다.

trace 파일의 비용은 밀리초가 아니라 옵티마이저 내부 비용이다. 결과 JSON은 승인 UPDATE 첫 시도와 재시도·준비 단계 오류를 나누어 읽는다.
