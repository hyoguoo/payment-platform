# 품질 시험 실행과 장애 대응

이 문서는 로컬 환경에서 같은 시험을 다시 실행하거나, 이상이 생겼을 때 확인할 순서를 안내한다.
도구의 현재 상태와 측정 범위는 [품질 측정 도구 안내](README.md)에서 확인한다.
아래 실행 명령은 현재 구현된 500ms 조회 품질 도구를 대상으로 한다.
기존 조건의 탐색은 [실측 보고서](../topics/PAYMENT-THROUGHPUT/tps-clean-measurements.html)에 보존한다.
주입·관측 분리 모드와 새 지연·지속 처리 판정은 구현·모드별 저부하 검증을 마쳤다.
새 모드로 측정 한계를 주장하기 전 [DESIGN](../topics/PAYMENT-THROUGHPUT/DESIGN.md)의 T2.4 기준 탐색이 필요하다.
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
| 결제 결과 소비 동시성 | 기본 1, 비교 실험에서만 `QUALITY_CONFIRMED_CONCURRENCY`로 지정 |
| 외부 연동 | 실제 PG 시크릿 미사용, 외부 알림 수신처 없음 |

heap은 JVM에 배정한 공간이며 컨테이너 전체 메모리 제한은 아니다.
설정 원본은 [quality Compose](../../docker/docker-compose.quality.yml)와 [로컬 알림 설정](../../observability/alertmanager/quality.yml)이다.
관측 도구를 함께 사용하므로 `--skip-obs` 옵션은 허용하지 않는다.

결과 소비 동시성 비교 시 기동 전 `QUALITY_CONFIRMED_CONCURRENCY=3`을 지정할 수 있다.
현재 결과 토픽은 3파티션이므로 1과 3을 비교하며, 기본값은 1을 유지한다.
각 조건은 새 컨테이너·새 볼륨에서 실행하고, 실제 consumer group의 멤버·파티션 배정을 확인한다.
적용값은 실행 증빙 `environment.json`의 payment `JAVA_TOOL_OPTIONS`에 남는다.
이 설정은 quality 실험용이며 운영 기본값의 변경이나 장애·중복 경로 검증 완료를 뜻하지 않는다.

### 회차마다 지킬 것

측정값은 실행할 때의 환경 상태에 따라 달라진다.
이전 회차의 상태가 남아 있거나 호스트가 다른 일로 바쁠 때 잰 값은 조건 차이와 구분되지 않는다.
그래서 회차마다 스택을 완전히 내렸다가 새로 올리고, 자원 상태와 안정 여부를 확인한 뒤 부하를 넣는다.

| 시점 | 확인할 것 | 지키지 못했을 때 |
|:---|:---|:---|
| 기동 전 | 이전 프로젝트의 컨테이너가 모두 내려가 `docker ps -q` 결과가 비어 있다 | 기동하지 않는다 |
| 기동 전 | 호스트 CPU·메모리, Docker에 할당한 CPU·메모리가 이전 회차와 같다 | 이전 회차와 비교하지 않는다 |
| 기동 전 | 부하 평균이 내려와 있고, Gradle 빌드·테스트나 IDE 색인 같은 무거운 작업이 돌지 않는다 | 끝날 때까지 기다린다 |
| 기동 전 | 직전 회차가 끝난 뒤 10분 이상 쉬었고, `pmset -g therm`에 열·성능 경고가 없다 | 기다린다. 쉬지 못하고 잰 회차는 통과만 근거로 쓰고 미달은 확정하지 않는다 |
| 기동 전 | 실행 JAR 해시와 컨테이너 이미지 ID가 이전 회차와 같다 | 다른 항목을 회차 기록에 조건 변경으로 남긴다 |
| 기동 | 새 프로젝트명으로 새 컨테이너·새 볼륨을 만들고, DB·Redis·Kafka가 비어 있는지 확인한다 | 이전 볼륨과 데이터를 다시 쓰지 않는다 |
| 부하 전 | 20개 컨테이너가 모두 실행 중이고 재시작·메모리 부족 종료가 없다. 기동 직후 오른 CPU가 내려와 유지된다 | 안정될 때까지 부하를 넣지 않는다 |
| 부하 중 | 측정 소스와 JAR을 바꾸지 않고 다른 무거운 작업을 함께 돌리지 않는다. 호스트·컨테이너 자원 표본과 `pmset -g therm` 기록을 남긴다 | 그 회차는 판정에 쓰지 않는다 |
| 종료 | 원자료와 DB 덤프를 회차 폴더에 저장한 뒤 볼륨까지 지우며 스택을 내린다(6절). 다음 회차는 이 표의 처음부터 다시 한다 | 덤프가 끝나기 전에는 볼륨을 지우지 않는다 |

부하가 이어지면 CPU 온도가 오르고, 온도가 높으면 CPU가 느려질 수 있다.
부하 평균이 내려와도 기기는 아직 데워져 있을 수 있어, 회차 사이에는 시간으로 쉰다.
`pmset -g therm`는 macOS가 실제로 속도를 제한했을 때만 기록을 남기므로 온도 자체를 보여 주지는 않는다.

기동 직후에는 JVM 시작과 Kafka 초기화로 CPU가 오른다.
`docker stats --no-stream`을 간격을 두고 여러 번 떠서 값이 내려와 유지되는지 본다.

태그가 버전 전체를 고정하지 않는 이미지(`mysql:8.0`, `redis:7.2-alpine`, `eclipse-temurin:21-jre`)는 다시 받으면 내용이 바뀔 수 있다.
Docker 데이터를 초기화했거나 이미지를 새로 받았다면 이전 회차 `image-proof.json`의 이미지 ID와 대조한다.

조건이 흔들린 회차의 값은 결과가 그럴듯해 보여도 판정 근거로 쓰지 않는다.
원자료는 지우지 않고, 회차 기록에 쓰지 않은 사유를 적는다.

## 1. 새 데이터 환경 만들기

컨테이너 이름과 포트를 공유하므로 기존 스택과 새 스택을 동시에 실행할 수 없다.
이전 회차의 스택이 남아 있으면 그 프로젝트명으로 6절의 종료 순서(원자료·DB 덤프 저장 → 볼륨 삭제)를 마친 뒤 시작한다.
`docker ps -q`와 `docker volume ls`에 이전 프로젝트의 자원이 남지 않아야 한다.

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
유입 누락이나 시도 기록의 유효성 문제만 남은 경우 추가 관찰로 해결되지 않으므로 미확정 상태로 종료한다.
실제 미처리 작업·수량 불일치가 있으면 계속 관찰하며, 미확인 주문은 성공으로 바꾸지 않는다.
정상 시험은 마지막 시도 이후 최대 10분 동안 남은 자동 처리를 관찰한다.

유입을 높일 때는 `--preallocated-vus`로 실행 전 가상 사용자를 확보할 수 있다(현재 상한 650).
가상 사용자 부족은 긴 서버 응답이나 발생기 설정·자원 문제 양쪽에서 생길 수 있다.
사전 할당을 늘려도 유입 누락이 사라진다고 보장하지 않으며, 누락이 남은 실행으로 최대 TPS를 확정하지 않는다.

기존 실행 ID의 폴더가 있으면 도구가 중단한다.
재시험은 새 ID를 사용해 실패 결과를 보존한다.

### 새 분리 모드의 도구 검증

기본 `--mode legacy`는 위의 500ms·60초 조회와 기존 판정을 유지한다.
`--mode capacity`는 checkout·confirm만 주입하며 전수 상태 GET을 보내지 않는다.
`--mode user-quality`는 같은 구매의 주문 ID를 별도 Python 관측기에 넘겨 주입 VU와 조회 작업을 분리한다.
**새 두 모드의 저부하 실구동을 마쳤으며 현재 수정 전 한계를 탐색 중이다. 회차별 실제 판정은 원자료를 따른다.**

각 실측 회차는 1절의 새 프로젝트·새 볼륨으로 준비한다.
다음은 그 준비를 마친 환경에서 실행하는 명령 형식이며, 같은 환경에 두 모드를 연속 실행하라는 뜻이 아니다.
워밍업과 본측정에는 같은 모드를 명시한다.

```bash
python3 scripts/quality/run.py --run-id warmup-capacity --phase warmup --mode capacity --rate 10 --duration-seconds 120 --output-root "$QUALITY_ROOT"
python3 scripts/quality/run.py --run-id normal-capacity --mode capacity --rate 10 --duration-seconds 180 --output-root "$QUALITY_ROOT"
```

사용자 품질 시험에서는 두 명령 모두 `--mode user-quality`로 바꾸고 새 환경·실행 ID를 사용한다.
워밍업의 `--phase warmup`은 유지한다. 실행 ID 이름만으로 워밍업을 추정하지 않는다.
120초 워밍업은 지속 처리 판정에 필요한 후반 60초 표본이 부족해 INCONCLUSIVE로 종료할 수 있다.
워밍업의 유입·API 오류·전수 대조를 확인하고, 그 수치를 한계 산정에 포함하지 않는다.
조회는 confirm 응답 뒤 1초부터 1초 간격·최대 30회이며, 다음 타이머인 31초 시점에 관찰을 끝낸다.
HTTP 요청은 남은 관찰 기한을 제한으로 사용하며 별도의 5초 제한을 추가하지 않는다.
기한 이후의 DONE 응답은 원자료에 남기고 사용자 기한 내 완료로 바꾸지 않는다.
202 응답을 받지 못한 주문의 조회도 진단용으로 기록하며 `confirmAccepted=false`로 구분한다.
이 정책은 서버의 추가 완료·재고 대조 기한과 별개다.

- 주입 증거: `attempts.jsonl`의 start/finish·checkout/confirm 시각·응답·주문 ID, `summary.json`의 실제 iteration/누락
- 관측 증거: `observations.jsonl`의 poll 시작/응답/예외/생략과 observation_finish, `observer.json`의 설정·포화·누락·진행 건수
- 자원 증거: `resources.jsonl`의 k6 및 Python runner CPU·RSS와 관측기 상태
- 설정 증거: `command.json`의 모드·실제 k6 명령·VU 상한·관측 worker 수

`--max-vus`는 1~650이며 `--preallocated-vus` 이상이어야 한다.
`--observer-workers`는 1~256, 기본 32다.
관측기 동시 요청이 가득 차거나 예정 조회가 한 주기 이상 늦으면 해당 조회를 생략하고 포화를 기록한다.
누락된 조회를 뒤늦게 몰아서 실행하지 않으며 주입 누락과 별도로 판정할 자료를 남긴다.
새 모드에서 `--fault`는 아직 지원하지 않아 부하 시작 전에 거절한다.
HTML은 schema1 legacy와 schema2 새 모드를 구분하며 원래 조건을 새 목표로 재판정하지 않는다.

### 새 모드의 판정과 증거

`result.json`에는 품질 목표와 처리 구간 분류를 따로 기록한다.
`DELAYED_SUSTAINED`의 FAIL은 지연 목표 미달이며, 관측 구간에서 지속 처리하지 못했다는 뜻이 아니다.
capacity 모드의 `SUSTAINED`는 사용자 완료 시간을 측정했다는 뜻이 아니다.

| 분류 | 의미 |
|:---:|:---:|
| QUALITY_MET | 사용자 품질 목표·유효 유입·관측 구간의 파이프라인 안정·전수 대조 충족 |
| SUSTAINED | capacity 모드에서 관측 구간의 파이프라인 안정. 사용자 완료는 미측정 |
| DELAYED_SUSTAINED | 파이프라인은 관측 구간에서 안정, 사용자 지연 목표는 미달 |
| BURST_ABSORBED | 부하 중 잔량이 증가하고 종료 후 해소 |
| INCONCLUSIVE | 유입 누락·미확인 접수·관측 포화·필수 근거 부족 |
| FAIL | API·업무 실패나 관측 종결 상태와 DB 상태 충돌 등 별도 실패 |

새 모드는 예정 총량(종료 경계 추가1건까지)·누락0·연속 iteration 번호와 실제 시작 시각을 함께 검사한다.
각 시작은 예정 시각보다 -1ms~100ms 범위에 있어야 한다. -1ms는 시계 반올림, 100ms는 프로젝트의 주입 계측 허용치다.
100ms를 넘으면 총량이 맞아도 발생기 시각 정밀도 미확정으로 남긴다. 실제 최대/p99 주입 지연과 경계 시작 건수를 공개한다.
예정 부하 창 끝에 시작이 두 건 걸려도 미세한 스케줄 지연이고 위 조건을 충족하면 별도 누락으로 세지 않는다.
모든 실제 시작을 분모·완료 대조에 포함하며, 기존 legacy의 경계 판정은 바꾸지 않는다.

지연 백분위의 분모는 워밍업을 제외한 해당 실행의 전체 시작 건수다.
기한 내 완료를 관측하지 못한 건은 무한 지연으로 두고 순위를 계산한다.
백분위가 기한 내 관측 범위를 벗어나면 숫자를 만들지 않고 `null`과 미관측 건수를 남긴다.
1건의 관측 FAILED를 지연 꼬리로 흡수해 DB DONE과의 충돌을 숨기지 않는다.
미확인 접수 때문에 판정은 미확정이어도, 다른 알려진 주문의 자동 처리·재고 대조는 상한까지 계속 관찰한다.

`capacity-samples.jsonl`은 10초 간격에 수집 비용을 더한 주기로 아래를 읽는다.

- DB 현재 커밋 상태: 워밍업 뒤 새 주문·DONE·미완료·가장 오래된 미완료 나이
- product 재고 확정: 워밍업 뒤 `stock_commit_dedupe` 증가·결제 DONE 이후 재고 대기·전체 파이프라인 잔량
- 메모리: 앱 5개 heap·실행 프로젝트 20컨테이너의 Docker 메모리 사용량·호스트 swap
- 신선도: `timestamp()`로 확인한 heap/up 원본 표본 시각, 수집 시작·DB 관측·수집 종료 시각

단일 상품·수량 1 시나리오이므로 재고 확정 표식 1건을 구매 1건과 대조한다.
DB 간 조회는 원자적이지 않으므로 조회 구간도 보존하며, 음수 잔량을 처리 누락으로 해석하지 않는다.
표식 수가 기준보다 줄면 정리 작업 등의 영향을 조사하고 해당 증거로 지속 처리를 확정하지 않는다.
heap/up 표본은 조회 기준 30초 이내만 인정한다. 응답의 평가 시각만으로 최신 수집을 가정하지 않는다.
[Prometheus 표본 선택](https://prometheus.io/docs/prometheus/latest/querying/basics/)과
[`timestamp()` 정의](https://prometheus.io/docs/prometheus/2.55/querying/functions/#timestamp)을 근거로 원본 수집 시각을 확인한다.

안정 판정은 부하 후반의 최소 60초·4개 이상 표본, 최대 표본 간격 15초를 요구한다.
그 구간을 다시 앞뒤로 나눠 결제·재고 잔량과 메모리의 최소/최대 관측 범위가 확장되지 않는지 비교한다.
완료 증가가 유입을 따라잡는지도 보며, 초기 구간에서 관측한 잔량 변동 범위만 허용한다.
GC 진동을 단순 평균 증가로 단정하지 않고, 계속 증가하는 잔량·메모리를 안정으로 승격하지 않는다.
이 조건은 프로젝트의 보수적인 유한 구간 판정이며 업계 표준·장기 운영 보장이 아니다.
조건이 애매하면 INCONCLUSIVE로 남기고 T2.4에서 긴 시험과 원자료로 확인한다.

DB DONE 기록 시각의 드레인과 재고 포함 전수 대조를 끝낸 관측 상한은 별도 필드다.
어느 것도 실제 DB 커밋 순간이나 정확한 재고 적용 순간으로 표현하지 않는다.

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

회차를 마칠 때는 볼륨까지 지운다.
나중에 분석할 수 있도록 지우기 전에 DB 네 개를 덤프해 회차 폴더에 남긴다.
서비스 로그와 주요 Prometheus 지표는 측정 도구가 이미 회차 폴더에 저장한다.
Tempo 트레이스, Loki, Prometheus 전체 데이터는 남기지 않는다.

먼저 자동 처리가 끝났는지 확인하고, 실행 중인 스택에서 덤프한다.
비밀번호는 컨테이너 안의 환경변수로 넘겨 명령 인자에 남기지 않는다.

```bash
mkdir -p "$QUALITY_ROOT/db-dump"
for role in payment pg product user; do
  docker exec "payment-mysql-$role" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -u root --single-transaction "$MYSQL_DATABASE"' \
    | gzip > "$QUALITY_ROOT/db-dump/$role.sql.gz"
done
ls -l "$QUALITY_ROOT/db-dump"
```

덤프 파일 네 개가 모두 비어 있지 않은지 확인한 뒤 볼륨까지 지우며 스택을 내린다.

```bash
bash scripts/compose-up.sh --mode quality --clean
docker volume ls --format '{{.Name}}' | grep "^${COMPOSE_PROJECT_NAME}_" || echo "볼륨 없음"
```

다음 회차는 1절부터 새 프로젝트명으로 시작한다.
