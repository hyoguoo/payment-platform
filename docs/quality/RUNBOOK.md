# 품질 시험 실행과 장애 대응

이 문서는 로컬 환경에서 같은 시험을 다시 실행하거나, 이상이 생겼을 때 확인할 순서를 안내한다.
도구의 현재 상태와 측정 범위는 [품질 측정 도구 안내](README.md)에서 확인한다.
새 공식 실측은 아직 수행하지 않았다.
여기의 명령은 저장소 루트에서 실행한다.

## 실행 조건

Java 21, Docker Desktop, `!override`를 지원하는 Docker Compose, k6, Python 3가 필요하다.
실행 전에 호스트와 Docker에 할당한 CPU·메모리를 기록한다.
측정값은 기록한 실행 환경의 결과로 해석한다.

`quality` 모드는 아래 조건으로 서비스를 실행한다.

| 설정 | 값 |
|:---:|:---:|
| 서비스 수 | 각 서비스 1개, 저장소·관측 도구 포함 20컨테이너 |
| 모의 PG | 응답 지연 100~300ms, 무작위 실패율 0 |
| JVM heap | payment 700MiB, PG 600MiB, product·user·Gateway 각각 384MiB, Eureka 256MiB |
| 복구 검사 설정 | IN_FLIGHT 300초, AWAITING_RESULT 900초, 검사 간격 120초 |
| 트레이스 수집 | 비즈니스 서비스와 Gateway 요청 100% |
| 외부 연동 | 실제 PG 시크릿 미사용, 외부 알림 수신처 없음 |

heap은 JVM에 배정한 공간이며 컨테이너 전체 메모리 제한은 아니다.
설정 원본은 [quality Compose](../../docker/docker-compose.quality.yml)와 [로컬 알림 설정](../../observability/alertmanager/quality.yml)이다.
관측 도구를 함께 사용하므로 `--skip-obs` 옵션은 허용하지 않는다.

## 1. 새 데이터 환경 만들기

컨테이너 이름과 포트를 공유하므로 기존 스택과 새 스택을 동시에 실행할 수 없다.
먼저 종료할 프로젝트명을 확인하고, 볼륨을 보존하며 스택을 내린다.
다음 예시의 `quality-previous`를 종료할 기존 프로젝트명으로 바꾼 뒤 실행한다.

```bash
COMPOSE_PROJECT_NAME="quality-previous" bash scripts/compose-up.sh --mode quality --down
```

재시험에는 새 이름을 사용한다.
아래 두 환경변수는 이후 명령에서도 사용하므로 같은 셸에서 이어서 실행한다.

```bash
export COMPOSE_PROJECT_NAME="quality-replay-$(date +%Y%m%d-%H%M%S)"
export QUALITY_ROOT="live-drill/$COMPOSE_PROJECT_NAME"

docker volume ls --format '{{.Name}}'
docker network ls --format '{{.Name}}'
```

목록에 새 프로젝트명으로 시작하는 자원이 없어야 한다.
확인 후 기동하면 전용 네트워크와 새 저장소 볼륨 13개를 만든다.
프로젝트명을 빠뜨리면 기본값인 `docker` 프로젝트를 조회하므로 주의한다.

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 21)" bash scripts/compose-up.sh --mode quality
python3 -m unittest discover -s scripts/quality -p 'test_*.py'
```

### 데이터가 비어 있는지 확인

상품을 넣기 전에 결제 테이블 8개, Redis 두 인스턴스, Kafka의 결제 토픽을 조회한다.
아래 명령은 데이터를 변경하지 않는다.

```bash
python3 - <<'CHECK'
import json, sys
sys.path.insert(0, 'scripts/quality')
import evidence, settlement
counts = {key: int(settlement._mysql(role, f'SELECT COUNT(*) FROM {table};').strip())
          for key, (role, table, _, _) in settlement.TABLES.items()}
redis = {role: int(evidence.command(['docker', 'exec', 'payment-redis-' + role,
                                   'redis-cli', 'DBSIZE'])) for role in ('stock', 'dedupe')}
offsets = evidence.command(['docker', 'exec', 'payment-kafka', 'kafka-get-offsets',
                           '--bootstrap-server', 'localhost:9092', '--topic', 'payment.*',
                           '--time', '-1']).splitlines()
assert all(value == 0 for value in counts.values()), counts
assert all(value == 0 for value in redis.values()), redis
assert len(offsets) == 18 and all(row.rsplit(':', 1)[1] == '0' for row in offsets), offsets
print(json.dumps({'tableRows': counts, 'redisKeys': redis, 'kafkaOffsets': offsets}, indent=2))
CHECK
```

행 수와 키 수가 모두 0이고, Kafka 6토픽·18파티션의 마지막 메시지 위치가 모두 0이어야 한다.
확인 결과와 기동 전후 자원 목록은 새 증빙 폴더에 보관한다.
검사에 실패하면 프로젝트명과 남은 데이터를 조사하며, 초기화로 덮어쓰지 않는다.

### 시험 상품 준비

사용자 ID 1은 Flyway가 자동으로 생성한다.
빈 환경을 확인한 뒤 상품 100종과 각 100,000개의 재고를 한 번만 넣는다.

```bash
PRODUCT_ID_BASE=1000 PRODUCT_COUNT=100 BENCH_STOCK=100000 bash scripts/bench-seed-stock.sh
```

이 명령은 재고 잔량을 덮어쓴다.
주문이 쌓인 뒤에는 다시 실행하지 않는다.
서비스 화면은 Gateway `http://localhost:8090`, Grafana `http://localhost:3000`, Prometheus `http://localhost:9090`에서 확인한다.

## 2. 준비 결제와 재기동 확인

먼저 작은 부하로 요청 기록과 주문·재고 대조가 동작하는지 확인한다.

```bash
python3 scripts/quality/run.py --run-id preparation-01 --rate 1 --duration-seconds 30 --output-root "$QUALITY_ROOT"
```

이 실행의 `settlement.json`이 통과하고 미처리 작업이 없는지 확인한다.
그 뒤 현재 상태를 저장하고 전체 스택을 내렸다 올린 뒤 다시 저장한다.
대조가 끝날 때까지 새 부하나 재고 시드를 실행하지 않는다.

```bash
python3 scripts/quality/settlement.py snapshot --output "$QUALITY_ROOT/before-restart.json"
bash scripts/compose-up.sh --mode quality --down
JAVA_HOME="$(/usr/libexec/java_home -v 21)" bash scripts/compose-up.sh --mode quality
python3 scripts/quality/settlement.py snapshot --output "$QUALITY_ROOT/after-restart.json"
```

다음 명령은 주문 ID·상태와 재고를 비교하고, 기존 주문 한 건을 Gateway에서 조회한다.
비교가 실패하면 다음 부하로 넘어가지 않고 차이를 조사한다.
Gateway 조회만 실패하면 서비스 탐색과 준비 상태를 확인한 뒤 조회를 다시 시도한다.

```bash
python3 - <<'CHECK'
import json, os, urllib.request
from pathlib import Path
root = Path(os.environ['QUALITY_ROOT'])
before = json.loads((root / 'before-restart.json').read_text())
after = json.loads((root / 'after-restart.json').read_text())
def orders(snapshot):
    return sorted((row['orderId'], row['status']) for row in snapshot['paymentEvents'])
assert orders(before) and all(status == 'DONE' for _, status in orders(before))
assert orders(before) == orders(after), '주문 ID 또는 상태가 다름'
assert before['products'] == after['products'], '상품 재고가 다름'
order_id = orders(after)[0][0]
with urllib.request.urlopen(f'http://localhost:8090/api/v1/payments/{order_id}/status', timeout=5) as response:
    payload = json.load(response)
body = payload.get('data', payload)
assert body.get('orderId') == order_id and body.get('status') == 'DONE', body
print(f'주문 {len(orders(after))}건·재고 보존 및 Gateway 조회 확인')
CHECK
```

이 단계는 저장된 데이터가 남는지 확인하는 절차다.
처리 중 주문의 장애 복구는 뒤의 payment 중단 시험에서 확인한다.
기동 스크립트는 quality 모드에서 재고 캐시를 DB 값으로 덮어쓰지 않는다.

## 3. 정상 부하 두 번 측정

매 측정 전에 2분간 워밍업한다.
실행 도구는 앞선 주문 처리가 남아 있으면 새 부하를 시작하지 않으며, 회차별 시작 재고를 따로 기록한다.

```bash
python3 scripts/quality/run.py --run-id warmup-01 --rate 10 --duration-seconds 120 --output-root "$QUALITY_ROOT"
python3 scripts/quality/run.py --run-id normal-01 --rate 10 --duration-seconds 600 --output-root "$QUALITY_ROOT"
python3 scripts/quality/run.py --run-id warmup-02 --rate 10 --duration-seconds 120 --output-root "$QUALITY_ROOT"
python3 scripts/quality/run.py --run-id normal-02 --rate 10 --duration-seconds 600 --output-root "$QUALITY_ROOT"
```

각 실행에서 아래 기준을 확인한다.

| 확인 항목 | 통과 기준 |
|:---:|:---:|
| 요청 유입 | 초당 10시도, 예정된 요청 누락 없음 |
| confirm 접수 응답 | p95 500ms 이하 |
| 빠른 완료 | 전체 시도의 95% 이상이 confirm 시작 후 5초 안에 완료 |
| 기한 내 완료 | 전체 시도의 99.9% 이상이 주문 접수 시작 후 60초 안에 완료 |
| 주문·재고 | 완료 주문에서 계산한 기대 재고와 DB·Redis 일치, 필수 기록 누락이나 설명되지 않는 미처리 작업 없음 |

종료 경계에서 계획보다 1건 더 시작되더라도 전체 시도에 포함한다.
기한을 넘겨 나중에 완료된 주문은 사용자 성공률에 다시 더하지 않는다.
정상 시험은 마지막 시도 이후 최대 10분 동안 남은 자동 처리를 관찰한다.

기존 실행 ID의 폴더가 있으면 도구가 중단한다.
재시험은 새 ID를 사용해 실패 결과를 보존한다.

## 4. payment 프로세스 중단 시험

워밍업 후 초당 10시도를 보내면서 payment만 강제로 종료한다.
요청이 오래 대기해도 유입을 유지하도록 k6의 가상 사용자 650개를 미리 준비한다.

```bash
python3 scripts/quality/run.py --run-id warmup-fault --rate 10 --duration-seconds 120 --output-root "$QUALITY_ROOT"
python3 scripts/quality/run.py --run-id fault-01 --rate 10 --duration-seconds 600 --fault --preallocated-vus 650 --output-root "$QUALITY_ROOT"
```

도구는 부하 시작 약 60초 뒤 payment에 SIGKILL을 보내고, 120초 뒤 같은 컨테이너를 시작한다.
DB·Kafka·PG·product는 유지한다.
시도 하나가 최대 60초를 기다리므로 초당 10시도에 필요한 동시 실행 공간 600개에 여유 50개를 더한 설정이다.

### 무엇을 복구 성공으로 볼 것인가

강제 종료 직후 DB에 접수됐지만 payment 처리가 끝나지 않은 주문이 최소 1건 있어야 한다.
이미 결제 완료 상태이고 재고 반영만 남은 주문으로 이 조건을 대신하지 않는다.
서비스 상태 검사가 정상으로 돌아올 때까지 접수된 주문을 복구 대상으로 고정하고, 이후 새 주문과 구분한다.

알람이 발생한 시점과 상태 검사가 정상으로 돌아온 시점을 각각 기록한다.
중단 후 120초 안에 알람이 발생하고, 상태 검사 정상 후 300초 안에 대상 주문의 완료·재고 반영을 확인하는 것이 목표다.
복구가 확인될 때까지 목표 유입도 유지돼야 한다.

`fault.json`의 `loadMaintainedThroughRecovery`와 `run.json`의 판정을 함께 확인한다.
부하 도구가 살아 있다는 이유만으로 요청 유입이 유지됐다고 판단하지 않는다.
장애 중 사용자 오류와 시간 초과는 정상 부하 결과에 합치지 않고 별도로 기록한다.

## 5. 이상이 생겼을 때

| 상황 | 확인·조치 순서 |
|:---|:---|
| ServiceDown 알람 | 컨테이너와 의존성 상태 확인 → 동일 설정으로 재기동 → 상태 검사와 Gateway 요청 확인 |
| 결제 완료 지연 | 주문 이력 → payment 발행 대기 메시지 → PG 수신·발행 기록 → 결제 결과 적용 → 재고 반영 순서로 확인 |
| 복구 목표 300초 초과 | 해당 주문의 마지막 상태와 오류, 자동 처리 진행 여부를 보존하고 원인 조사 |
| QUARANTINED 상태 | PG 승인·금액·재고 근거를 확보한 뒤 격리 사유별 판단 |
| 오류 응답에 주문 ID가 없음 | 멱등키를 단서로 DB 주문과 이력을 조회하고, 연결할 수 없으면 접수 여부 미확정으로 기록 |
| 정상 부하 목표 미달 | 부하를 늘리기 전에 원인 조사, 수정 내용과 재시험 결과 기록 |
| 메모리 부족 종료 또는 주문·재고 불일치 | 운영자가 새 부하를 중단하고 원자료 보존 |

멱등키 결과는 Redis에 10초만 유지되고 DB 저장과 동시에 확정되지 않는다.
키가 없다고 주문이 접수되지 않았다고 단정할 수 없으며, Redis에만 연결 정보가 있어도 DB 접수로 인정하지 않는다.
시험 통과를 위해 주문 상태를 강제로 실패나 성공으로 바꾸지 않는다.

메모리 부족 종료나 정합성 위반을 실시간으로 탐지해 부하를 자동 중단하는 기능은 현재 도구에 없다.
실행 중 측정 코드를 수정한 경우에는 `run.json`의 `sourceChanged`를 확인하며, 해당 실행을 확정된 통과 근거로 사용하지 않는다.

## 6. 보고서 열기와 종료

```bash
python3 scripts/quality/report.py --root "$QUALITY_ROOT"
python3 -m http.server 8899 --bind 127.0.0.1 --directory live-drill
```

브라우저에서 `http://localhost:8899/<프로젝트명>/report.html`을 연다.
열람을 마치면 정적 서버를 실행한 터미널에서 Ctrl+C로 종료한다.
원자료는 실행 폴더에 보존하며, 공개 보고서에는 새 측정으로 확인한 결과만 포함한다.

서비스 스택도 내릴 때는 먼저 자동 처리가 끝났는지 확인하고 다음 명령을 실행한다.

```bash
bash scripts/compose-up.sh --mode quality --down
```

볼륨은 보존되므로 같은 프로젝트명으로 다시 기동하면 기존 데이터가 남는다.
새 데이터로 재시험하려면 1단계부터 다른 프로젝트명을 사용한다.
