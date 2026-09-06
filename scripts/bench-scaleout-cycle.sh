#!/usr/bin/env bash
# bench-scaleout-cycle.sh — 사이클 하나(조건 값 조합)를 인자만 주고 끝까지 무인 실행한다.
#
# 순서: 스택 기동(관측 스택 포함) → 클러스터 구성 → 시드 → 부하 → 종결 대기 → 정합 검증
#       → (통과 시에만) 재구성
#
# 정합 판정은 scripts/k6/verify-settlement.sh 의 종료 코드로 받는다:
#   0(PASS)         — 처리율/지연/복제 지연을 결과 파일에 남기고 bench-cycle-reset.sh 로 재구성
#   2(INCONCLUSIVE) — INCONCLUSIVE_RETRY_WAIT_SECONDS 만큼 기다렸다 최대 INCONCLUSIVE_MAX_RETRIES
#                     회까지 재검증. 그래도 보류면 사이클 실패 — 재구성을 부르지 않는다
#   3(MISMATCH)     — 즉시 사이클 실패. 재시도도 재구성도 하지 않는다(캐시를 비우면 어긋난
#                     값과 선차감 기록이 지워져 원인을 되짚을 수 없다)
#   1(전제 실패)     — 접속 실패, 또는 부하가 시스템에 닿지 않아(k6 제출 0 / DB 총합 0) 정합
#                     판정 자체가 성립하지 않는 경우. 재시도도 재구성도 하지 않는다 —
#                     교차식은 값이 0 이면 전부 자동으로 맞아, 통과로 읽으면 부하가 통째로
#                     무효인 사고를 그냥 지나친다(실측으로 한 번 겪었다)
#   그 외            — 이 러너가 모르는 코드. 통과로 읽지 않고 실패로 다룬다
#
# 실패로 멈춘 사이클의 잔류는 이 스크립트가 치우지 않는다 — 사람이 scripts/bench-cycle-reset.sh
# 를 따로 돌린다. 격리 종결처럼 사람 판단이 필요한 자리가 있어 러너가 알아서 밀고 가면 안 된다.
#
# ⚠️ bench-cycle-reset.sh 도 거부하는 잔류(예: 영구 미종결)를 사람이 직접 payment 원장 여섯
# 테이블을 TRUNCATE 해 치울 때는, 그 전에 반드시 Kafka consumer group lag(payment-service on
# payment.events.confirmed, pg-service on payment.commands.confirm)이 0(또는 안정)인지 먼저
# 확인한다. lag 가 남은 채로 원장만 비우면, 컨슈머가 재기동 후 그 lag 를 재생하면서 이미
# 지워진 orderId 를 찾다가 PaymentFoundException 재시도 루프에 갇혀 뒤에 쌓인 새 메시지까지
# 전부 막는다(Task 20 실측 — task20-r75 사이클이 이 경로로 오염돼 db_done=0 으로 나왔다).
# 이미 걸렸다면: 대상 컨슈머(payment-service/pg-service)를 멈추고
# `kafka-consumer-groups --bootstrap-server localhost:9092 --group <group> --reset-offsets
# --to-latest --execute --topic <topic>` 로 밀린 offset 을 건너뛴 뒤 원장을 비운다.
#
# 사용법:
#   INSTANCES=2 STOCK_MASTERS=4 bash scripts/bench-scaleout-cycle.sh
#   # 짧은 흐름 확인(smoke) — PEAK_RATE/STAGE_SEC 로 부하를 짧게 줄인다:
#   INSTANCES=1 STOCK_MASTERS=1 K6_EXTRA_ARGS="-e PEAK_RATE=10 -e STAGE_SEC=5" \
#     bash scripts/bench-scaleout-cycle.sh
#
# 조건 값 (환경 변수):
#   INSTANCES        — payment-service 인스턴스 수, 1/2/3/4 (기본 1)
#   PG_INSTANCES     — pg-service 인스턴스 수 (기본 1). 확정 명령을 소비해 벤더를 호출하는
#                      돈 경로의 공유 자원 — Task 21 까지의 측정은 이 축을 한 번도 늘리지
#                      않았다(payment 만 늘렸다). 소비 병렬도는 payment.commands.confirm
#                      파티션 수(3)까지만 의미가 있다
#   STOCK_MASTERS     — 재고 캐시 클러스터 마스터 수, 1/2/4 (기본 2)
#   DEDUPE_MASTERS    — 멱등 저장소 클러스터 마스터 수 — 설계상 변수 아님, 고정 3 (기본 3)
#   POLLING_ROUTE     — 폴링 조회를 복제본으로 보낼지, on/off (기본 on)
#   ITEMS_PER_ORDER   — 주문당 상품 수 (기본 1, 다중 상품 축에서만 3)
#   VENDOR_LATENCY    — pg fake gateway 지연, low/high (기본 low. low=100~300ms, high=800~1500ms)
#   CASE_NAME         — 결과 파일명(미지정 시 조건 값으로 자동 합성)
#
# 측정 튜닝 (환경 변수, 대개 기본값 그대로 둔다):
#   BASE_URL                  — k6 요청 기저 URL (기본 http://localhost:8090 — gateway 경유)
#   RECONCILER_TIMEOUT        — payment reconciler IN_PROGRESS 회수 기준 초 (기본 300 — 코드
#                               default 와 동일. 커넥션 풀 80/피크 400 조합이 포화에 닿을 때
#                               확정 지연 꼬리가 벌어질 수 있어, 회수가 아직 진행 중인 확정
#                               결과를 앞지르지 않도록 짧게 단축하지 않는다)
#   RECONCILER_SCAN_MS        — payment reconciler 스캔 주기 ms (기본 15000)
#   HIKARI_MAX_POOL           — payment Hikari DB 커넥션 풀 상한 (기본 80 — 부하 곡선 피크
#                               400 req/s 에 맞춘 값. docker-compose.benchmark.yml 이 이 값을
#                               SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE 로 그대로 받는다)
#   CONFIRMED_CONSUMER_CONCURRENCY — payment-service ConfirmedEventConsumer 리스너 동시성 (기본
#                               1 — 코드 default 와 동일한 현재 운영 동작). payment.events.confirmed
#                               파티션 수(3)까지만 늘리는 의미가 있다(Task 20 이 지목한 1순위 후보)
#   PG_INBOX_WORKERS          — pg-service 벤더 confirm 호출 동시성 (기본 5 — 코드 default)
#   PG_OUTBOX_WORKERS         — pg-service 확정 결과 Kafka 릴레이 워커 수 (기본 1 — 코드 default)
#   FAKE_FAIL_RATE            — pg fake gateway 실패율 (기본 0 — baseline 고정)
#   PRODUCT_COUNT / PRODUCT_ID_BASE / BENCH_STOCK — scripts/bench-seed-stock.sh 와 동일
#   K6_EXTRA_ARGS              — k6 run 에 추가 전달할 -e KEY=VALUE 인자(공백 구분)
#   PRE_LOAD_HOOK              — 부하 직전(인스턴스가 전부 뜬 뒤, 표본화 시작 전)에 실행할 명령.
#                               실패하면 사이클을 중단한다. 예: scripts/bench-pin-cpus.sh
#   EXTRA_COMPOSE_FILES        — compose 스택 뒤에 얹을 override 파일들(공백 구분)
#   REPLICA_SAMPLE_INTERVAL_SECONDS — 복제 지연 표본 주기 초 (기본 5)
#   K6_CPU_SAMPLE_INTERVAL_SECONDS  — k6 프로세스 CPU 표본 주기 초 (기본 2) — 측정 대상(payment
#                               앱)과 CPU 를 다투는지 확인하기 위해 부하 도구 자신의 점유도 남긴다
#   LATENCY_SAMPLE_MAX_VUS          — 지연 표본 VU 상한 (기본 300). 필요한 VU = 도착률 × 종결시간
#   LATENCY_SAMPLE_PRE_VUS          — 지연 표본 사전 할당 VU (기본 50)
#   LATENCY_POLL_TIMEOUT_MS         — 지연 표본 폴링 타임아웃 ms (기본 180000). 이 값에서 잘리면
#                               백분위가 성립하지 않으므로 종결 꼬리보다 크게 잡는다
#   LATENCY_SAMPLE_RATE_PER_SEC     — 지연 표본 시나리오 도착률 req/s (기본 2). 0 이면 표본
#                               시나리오를 켜지 않는다(체감 지연 결과가 비게 된다)
#   INCONCLUSIVE_MAX_RETRIES        — 판단 보류 재검증 최대 횟수 (기본 3)
#   INCONCLUSIVE_RETRY_WAIT_SECONDS — 재검증 사이 대기 초 (기본 20)
#   RESOURCE_SAMPLE_INTERVAL_SECONDS — MySQL·Redis·컨테이너 자원 표본 주기 초 (기본 15) — Redis
#                               노드마다 PING 왕복 지연을 순차로 재기 때문에(노드 수 × 약 1.2초)
#                               너무 짧게 잡으면 한 틱이 다음 틱을 밀어낸다
#   BACKLOG_SAMPLE_INTERVAL_SECONDS — 부하 구간 동안 미종결(payment_event READY/IN_PROGRESS/
#                               RETRYING/AWAITING_RESULT) 건수를 재는 주기 초 (기본 10). 능력 판정의 핵심 근거 —
#                               부하 중 이 값이 단조 증가하면 그 도착률이 능력을 넘었다는 뜻이고,
#                               일정 범위에서 오르내리기만 하면 능력 안이라는 뜻이다. 부하가 끝나는
#                               시점(settle 대기 진입 직전)에 표본화도 함께 멈춘다 — 판정 대상은
#                               "부하 중" 추이지 settle 대기의 회수 추이가 아니다
#
# 부하 시나리오는 확정 접수까지만 확인하고 VU 를 놓아준다(async-payment.js 에 SKIP_POLL=true 로
# 고정 전달) — 종결 폴링으로 VU 를 붙잡던 옛 구조는 도착률이 VU 상한 ÷ 종결 시간에 갇혀 목표
# 도착률에 못 닿고 dropped_iterations 만 쌓았다. 체감 지연은 별도의 낮은 도착률 표본
# 시나리오(latency_sample)로 재고, 처리율은 부하 도구 관측 여부와 무관하게 DB 종결 건수를
# 부하 구간으로 나눠 낸다(throughput.db_done_per_load_sec).
#
# 자원 표본화 — 부하 구간 동안 네 계열을 주기로 표본화해 결과 파일에 최대·평균으로 남긴다.
# 전용 exporter/사이드카를 새로 띄우지 않고 기존 컨테이너에 docker exec/docker stats 로
# 직접 묻는다(이미 있는 복제 지연·k6 CPU 표본화와 같은 결) — 관측 스택에 상시 스크랩 대상을
# 늘리지 않아 측정 대상과 자원을 다투지 않는다.
#   - MySQL(원본·복제본): 실행 중 스레드(Threads_running) · 행 잠금 대기(Innodb_row_lock_
#     current_waits) · 커밋/IO fsync 대기(Innodb_os_log_pending_fsyncs + Innodb_data_
#     pending_fsyncs) 를 performance_schema.global_status 단일 SELECT 로 표본화
#   - Redis(재고 캐시·멱등 저장소 클러스터): 클러스터를 구성하는 실행 중 노드 전부를 매 틱
#     순회해 초당 명령(instantaneous_ops_per_sec 합) · 블록된 클라이언트(blocked_clients 합) ·
#     PING 왕복 지연(redis-cli --latency 1초 표본의 노드 간 최댓값)을 표본화
#   - 컨테이너별 CPU·메모리·네트워크·디스크 IO(kafka 포함): docker stats 로 CPU%·메모리
#     사용량을 틱마다, 네트워크·디스크 누적 바이트는 표본 구간의 처음·끝 값 차이로 평균
#     처리율(byte/s)을 낸다. kafka 는 확정 명령/결과 발행이 전부 거쳐 가는 단일 브로커라
#     Task 21 재측정에서 추가했다(그 전까지 표본 대상에서 빠져 있었다)
#   - Kafka 발행 지연: 전용 비운영 토픽(payment.bench.probe)에 acks=all 로 레코드 1건을
#     보내 왕복 지연을 잰다(kafka-producer-perf-test). 매 호출 새 JVM 을 띄워 절대값에
#     기동 오버헤드가 섞이므로(idle 137~143ms) 절대값보다 부하 구간에서 이 기저선 대비
#     얼마나 튀는지가 신호다 — 애플리케이션 토픽에 합성 메시지를 섞으면 소비자가
#     역직렬화/도메인 검증에 실패해 재시도 루프에 빠지므로(Task 21 r100 재측정 실측) 별도
#     토픽에만 쏜다
#
# 결과 파일:
#   results/<CASE_NAME>-cycle.json — 조건 값, 처리율, 부하 무결성(dropped_iterations), k6 자체
#   CPU 점유, 백분위 지연(표본), 복제 지연, MySQL·Redis·컨테이너 자원 표본(최대·평균), 정합 판정.
#   부하 도구가 쓰는 results/<CASE_NAME>.json 과 verify-settlement.sh 의
#   results/<CASE_NAME>-verdict.json 어느 쪽도 건드리지 않는다.
#
# 선행 조건:
#   - docker / k6 / jq 설치
#   - docker-compose.infra.yml 로 뜨는 인프라가 이미 기동 중이어야 하는 것은 아니다 —
#     이 스크립트가 필요한 서비스를 직접 올린다. 단 payment-infra-network 는 이미 있어야
#     한다(scripts/bench-redis-cluster.sh 의 선행 조건과 동일)
#
# 종료 코드:
#   0 — 사이클 성공(정합 PASS) — 결과 기록 + 재구성까지 완료
#   1 — 선결 조건 실패(도구 미설치, 스택 기동 실패, 시드 실패, k6 실행 오류 등) — 캐시 상태 불명,
#       사람이 직접 확인 필요
#   2 — 정합 판단 보류 소진(INCONCLUSIVE) — 캐시를 비우지 않고 실패
#   3 — 정합 불일치(MISMATCH) — 캐시를 비우지 않고 즉시 실패
#   4 — 정합 검증이 접속·전제 실패(exit 1) 또는 이 러너가 모르는 코드로 끝남 — 캐시를 비우지
#       않고 실패(통과로 읽지 않는다)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=common.sh
source "${ROOT_DIR}/scripts/common.sh"

# ---------------------------------------------------------------------------
# 조건 값
# ---------------------------------------------------------------------------

INSTANCES="${INSTANCES:-1}"
STOCK_MASTERS="${STOCK_MASTERS:-2}"
DEDUPE_MASTERS="${DEDUPE_MASTERS:-3}"
POLLING_ROUTE="${POLLING_ROUTE:-on}"
ITEMS_PER_ORDER="${ITEMS_PER_ORDER:-1}"
VENDOR_LATENCY="${VENDOR_LATENCY:-low}"

if ! [[ "${INSTANCES}" =~ ^[1-4]$ ]]; then
    print_error "❌ INSTANCES 는 1~4 여야 한다 (입력: '${INSTANCES}')"
    exit 1
fi
if ! [[ "${STOCK_MASTERS}" =~ ^[0-9]+$ ]] || [[ "${STOCK_MASTERS}" -lt 1 ]]; then
    print_error "❌ STOCK_MASTERS 는 1 이상의 정수여야 한다 (입력: '${STOCK_MASTERS}')"
    exit 1
fi
if [[ "${POLLING_ROUTE}" != "on" && "${POLLING_ROUTE}" != "off" ]]; then
    print_error "❌ POLLING_ROUTE 는 on 또는 off 여야 한다 (입력: '${POLLING_ROUTE}')"
    exit 1
fi
if [[ "${VENDOR_LATENCY}" != "low" && "${VENDOR_LATENCY}" != "high" ]]; then
    print_error "❌ VENDOR_LATENCY 는 low 또는 high 여야 한다 (입력: '${VENDOR_LATENCY}')"
    exit 1
fi

CASE_NAME="${CASE_NAME:-cycle-i${INSTANCES}-m${STOCK_MASTERS}-poll${POLLING_ROUTE}-items${ITEMS_PER_ORDER}-${VENDOR_LATENCY}}"

# ---------------------------------------------------------------------------
# 측정 튜닝
# ---------------------------------------------------------------------------

BASE_URL="${BASE_URL:-http://localhost:8090}"
RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT:-300}"
RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS:-15000}"
HIKARI_MAX_POOL="${HIKARI_MAX_POOL:-80}"
CONFIRMED_CONSUMER_CONCURRENCY="${CONFIRMED_CONSUMER_CONCURRENCY:-1}"
PG_INSTANCES="${PG_INSTANCES:-1}"
PG_INBOX_WORKERS="${PG_INBOX_WORKERS:-5}"
PG_OUTBOX_WORKERS="${PG_OUTBOX_WORKERS:-1}"
FAKE_FAIL_RATE="${FAKE_FAIL_RATE:-0}"
PRODUCT_COUNT="${PRODUCT_COUNT:-100}"
PRODUCT_ID_BASE="${PRODUCT_ID_BASE:-1000}"
BENCH_STOCK="${BENCH_STOCK:-10000000}"
K6_EXTRA_ARGS="${K6_EXTRA_ARGS:-}"
REPLICA_SAMPLE_INTERVAL_SECONDS="${REPLICA_SAMPLE_INTERVAL_SECONDS:-5}"
K6_CPU_SAMPLE_INTERVAL_SECONDS="${K6_CPU_SAMPLE_INTERVAL_SECONDS:-2}"
LATENCY_SAMPLE_RATE_PER_SEC="${LATENCY_SAMPLE_RATE_PER_SEC:-2}"
# 지연 표본은 종결까지 폴링하며 VU 를 붙잡는다 — 필요한 VU 는 (도착률 × 종결시간)이다.
#
# 이 값은 부하 구간에 따라 정반대로 위험해진다. 양쪽 다 실측으로 겪었다:
#   - 너무 작으면(20) 포화 구간에서 표본이 VU 부족으로 드롭되고 남은 것마저 타임아웃에 걸려
#     지연 지표가 통째로 빈다(32,000건 부하에서 종결 관측 4~31건, 타임아웃 50~59건).
#   - 너무 크면(300) 포화 구간에서 VU 170개가 살아남아 0.5초마다 조회를 쏜다 — 초당 76건으로
#     본 부하(96/s)의 80% 에 달하는 추가 요청이다. 게다가 느린 구성일수록 VU 가 더 오래
#     붙잡혀 세금을 더 내므로 1대가 2대보다 불리해져 배수가 실제보다 좋게 나온다.
#
# 그래서 한 값으로 전 구간을 덮을 수 없다. 능력 이하 구간(종결 1초 안팎)은 넉넉히, 포화 구간
# (종결 수십 초)은 표본 도착률을 낮추거나 표본을 끄고(LATENCY_SAMPLE_RATE_PER_SEC=0) 처리량만
# 재는 것이 맞다 — 포화 구간의 지연은 어차피 전부 큐 대기라 서비스 품질을 뜻하지 않는다.
# 아래 예상 부하 경고가 그 판단을 돕는다.
LATENCY_SAMPLE_MAX_VUS="${LATENCY_SAMPLE_MAX_VUS:-300}"
LATENCY_SAMPLE_PRE_VUS="${LATENCY_SAMPLE_PRE_VUS:-50}"
# 폴링 타임아웃 — 이 값에서 잘린 표본은 백분위에 넣을 수 없다(잘린 분포의 백분위는 백분위가
# 아니다). 종결 지연 꼬리보다 크게 잡아야 관측이 성립한다.
LATENCY_POLL_TIMEOUT_MS="${LATENCY_POLL_TIMEOUT_MS:-180000}"
INCONCLUSIVE_MAX_RETRIES="${INCONCLUSIVE_MAX_RETRIES:-3}"
INCONCLUSIVE_RETRY_WAIT_SECONDS="${INCONCLUSIVE_RETRY_WAIT_SECONDS:-20}"
RESOURCE_SAMPLE_INTERVAL_SECONDS="${RESOURCE_SAMPLE_INTERVAL_SECONDS:-15}"
BACKLOG_SAMPLE_INTERVAL_SECONDS="${BACKLOG_SAMPLE_INTERVAL_SECONDS:-10}"

if [[ "${VENDOR_LATENCY}" == "low" ]]; then
    FAKE_LATENCY_MIN=100
    FAKE_LATENCY_MAX=300
else
    FAKE_LATENCY_MIN=800
    FAKE_LATENCY_MAX=1500
fi

RESULTS_DIR="${RESULTS_DIR:-${ROOT_DIR}/results}"
CYCLE_JSON="${RESULTS_DIR}/${CASE_NAME}-cycle.json"

MYSQL_PAYMENT_CONTAINER="${MYSQL_PAYMENT_CONTAINER:-payment-mysql-payment}"
MYSQL_PAYMENT_REPLICA_CONTAINER="${MYSQL_PAYMENT_REPLICA_CONTAINER:-payment-mysql-payment-replica}"
# 돈 경로의 두 번째 DB — 확정 명령/결과가 pg_inbox·pg_outbox 를 거친다. 여기를 표본화하지 않아
# 실제 잠금 경합(pg_inbox UPDATE 데드락)을 오래 못 봤다. payment 원본만 보고 "행 잠금 대기 0"
# 이라 잠금을 배제한 것이 그 사각지대다.
MYSQL_PG_CONTAINER="${MYSQL_PG_CONTAINER:-payment-mysql-pg}"
MYSQL_PAYMENT_ROOT_PASSWORD="${MYSQL_PAYMENT_ROOT_PASSWORD:-payment123}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-payment-kafka}"
KAFKA_PROBE_TOPIC="${KAFKA_PROBE_TOPIC:-payment.bench.probe}"

COMPOSE_ARGS=(
    -f "${ROOT_DIR}/docker/docker-compose.infra.yml"
    -f "${ROOT_DIR}/docker/docker-compose.apps.yml"
    -f "${ROOT_DIR}/docker/docker-compose.observability.yml"
    -f "${ROOT_DIR}/docker/docker-compose.benchmark.yml"
    -f "${ROOT_DIR}/docker/docker-compose.scaleout.yml"
)

# EXTRA_COMPOSE_FILES — 위 스택 뒤에 얹을 override 파일들(공백 구분, ROOT_DIR 기준 상대경로 가능).
# CPU 상한(docker-compose.cpulimit.yml)처럼 측정 회차마다 켜고 끄는 구성을 스택에 끼우는 통로다.
# 뒤에 얹히므로 같은 키를 다시 선언하면 이긴다 — command 같은 배열 키는 병합되지 않고 통째로
# 덮어쓰니, 덧붙이는 파일에서는 이미 선언된 배열 키를 다시 쓰지 않는다.
if [[ -n "${EXTRA_COMPOSE_FILES:-}" ]]; then
    for _extra in ${EXTRA_COMPOSE_FILES}; do
        if [[ "${_extra}" != /* ]]; then
            _extra="${ROOT_DIR}/${_extra}"
        fi
        if [[ ! -f "${_extra}" ]]; then
            echo "EXTRA_COMPOSE_FILES 에 지정한 파일이 없다: ${_extra}" >&2
            exit 1
        fi
        COMPOSE_ARGS+=(-f "${_extra}")
    done
fi

dc() {
    docker compose "${COMPOSE_ARGS[@]}" "$@"
}

# ---------------------------------------------------------------------------
# 헬퍼
# ---------------------------------------------------------------------------

wait_healthy() {
    local service="$1" timeout="${2:-120}" attempt=0 cid status="unknown"
    while true; do
        cid=$(dc ps -q "${service}" 2>/dev/null | head -n1)
        if [[ -n "${cid}" ]]; then
            status=$(docker inspect --format '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo "unknown")
            [[ "${status}" == "healthy" ]] && return 0
        fi
        attempt=$((attempt + 1))
        if [[ "${attempt}" -ge "${timeout}" ]]; then
            print_error "❌ ${service} 가 시간 내 healthy 로 전환되지 않음 (마지막 상태: ${status})"
            return 1
        fi
        sleep 1
    done
}

# 스케일된 서비스 전부(expected_count 대)가 healthy 인지 확인한다.
wait_all_healthy() {
    local service="$1" expected="$2" timeout="${3:-150}" attempt=0
    while true; do
        local cids healthy_count=0
        cids=($(dc ps -q "${service}" 2>/dev/null))
        if [[ "${#cids[@]}" -eq "${expected}" ]]; then
            for cid in "${cids[@]}"; do
                local status
                status=$(docker inspect --format '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo "unknown")
                [[ "${status}" == "healthy" ]] && healthy_count=$((healthy_count + 1))
            done
            if [[ "${healthy_count}" -eq "${expected}" ]]; then
                return 0
            fi
        fi
        attempt=$((attempt + 1))
        if [[ "${attempt}" -ge "${timeout}" ]]; then
            print_error "❌ ${service} ${expected}대 전부 healthy 전환 실패 (컨테이너 ${#cids[@]}개, healthy ${healthy_count}개)"
            return 1
        fi
        sleep 1
    done
}

# Eureka 등록(status=UP) 확인 — 컨테이너 healthy 와 Eureka 등록은 별개다. payment-service 를
# user-service 보다 먼저 올리면 Eureka 클라이언트의 시작 시점 스냅샷 누락으로 checkout 이
# 일시 503 을 낸다(Task 12 실측) — user-service 를 먼저 확인해 재발을 막는다.
wait_eureka_registered() {
    local app_name="$1" timeout="${2:-60}" attempt=0
    while true; do
        if curl -s -H 'Accept: application/json' "http://localhost:8761/eureka/apps/${app_name}" 2>/dev/null \
            | grep -q '"status":"UP"'; then
            return 0
        fi
        attempt=$((attempt + 1))
        if [[ "${attempt}" -ge "${timeout}" ]]; then
            print_error "❌ ${app_name} 가 시간 내 Eureka 에 UP 으로 등록되지 않음"
            return 1
        fi
        sleep 1
    done
}

# 게이트웨이가 payment-service 로 실제 라우팅할 수 있는지 확인한다 — wait_eureka_registered 는
# Eureka 서버 레지스트리만 보고, 게이트웨이 자신의 로컬 LoadBalancer 캐시는 별개 폴링 주기로
# 갱신된다(Task 20 실측: force-recreate 직후 Eureka 서버는 UP 인데 게이트웨이 로그에
# "No servers available for service: payment-service" WARN 이 10여 초 이어지며 그 구간의
# checkout 이 전부 503 으로 실패했다 — 부하 시작 직후 몰린 체크 실패가 시스템 포화가 아니라
# 이 레이스였다). 실제 상태 조회 요청을 게이트웨이 경유로 보내 200 대/400 대(503 이 아닌) 응답이
# 오는지로 확인한다 — 존재하지 않는 orderId 라 404 류가 정상이고, 그 자체가 라우팅 성공의 증거다.
wait_gateway_routes_payment_service() {
    local timeout="${1:-30}" attempt=0 code
    while true; do
        code=$(curl -s -o /dev/null -w '%{http_code}' \
            "${BASE_URL}/api/v1/payments/00000000-0000-0000-0000-000000000000/status" 2>/dev/null || echo "000")
        if [[ "${code}" != "000" && "${code}" != "503" && "${code}" != "502" && "${code}" != "504" ]]; then
            return 0
        fi
        attempt=$((attempt + 1))
        if [[ "${attempt}" -ge "${timeout}" ]]; then
            print_error "❌ 게이트웨이가 시간 내 payment-service 로 라우팅하지 못함 (마지막 응답 코드: ${code})"
            return 1
        fi
        sleep 1
    done
}

# 지정 서비스의 실행 중 컨테이너 이름을 "name1:6379,name2:6379,..." 형태로 합성한다
# (scripts/bench-cluster-check.sh 와 동일 방식).
cluster_nodes_csv() {
    local service="$1"
    local cids names=() cid name
    cids=($(dc ps -q "${service}" 2>/dev/null))
    for cid in "${cids[@]}"; do
        name=$(docker inspect --format '{{.Name}}' "${cid}" | sed 's#^/##')
        names+=("${name}")
    done
    local IFS=,
    local parts=()
    for name in "${names[@]}"; do
        parts+=("${name}:6379")
    done
    echo "${parts[*]}"
}

REPLICA_LAG_LOG=""
REPLICA_SAMPLER_PID=""

start_replica_lag_sampler() {
    REPLICA_LAG_LOG="$(mktemp "${ROOT_DIR}/results/.replica-lag.${CASE_NAME}.XXXXXX")"
    (
        # 이 서브셸은 함수가 아니라 while 루프 본문이라 'local' 을 쓸 수 없다(bash 가
        # "local: can only be used in a function" 오류를 내고도 계속 진행은 하지만,
        # 매 반복 stderr 에 잡음을 남긴다) — 평범한 변수로 둔다.
        while true; do
            raw=$(docker exec "${MYSQL_PAYMENT_REPLICA_CONTAINER}" mysql \
                -u root -p"${MYSQL_PAYMENT_ROOT_PASSWORD}" -e "SHOW REPLICA STATUS\G" 2>/dev/null \
                | awk -F': ' '/Seconds_Behind_Source/ { print $2 }')
            echo "$(date +%s) ${raw:-NULL}" >> "${REPLICA_LAG_LOG}"
            sleep "${REPLICA_SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    REPLICA_SAMPLER_PID=$!
}

# SIGTERM 는 그 순간 표본화 루프가 docker exec 자식에 블록돼 있으면 못 받을 수 있고,
# 뒤이어 종료를 wait 로 확인하는 방식은 그 확인 자체가 이 환경에서 무기한 걸리는 것을
# 실측으로 겪었다(호스트 자원이 눌린 상태로 추정 — 원인을 이 세션에서 완전히 못 좁혔다).
# 그래서 확인 없이 SIGKILL 만 쏘고 끝낸다 — kill 자체는 블로킹 시스템 콜이 아니라 이
# 호출은 걸리지 않는다. 죽었는지 확인하지 않으므로 표본화 루프가 아주 짧게 한 바퀴 더
# 돌 수는 있지만(시드가 끝난 뒤라 재시드와 겹치지 않는다), 좀비로 남는 것은 이 스크립트
# 프로세스가 끝나면 함께 정리된다.
stop_replica_lag_sampler() {
    if [[ -n "${REPLICA_SAMPLER_PID}" ]]; then
        kill -9 "${REPLICA_SAMPLER_PID}" 2>/dev/null || true
        REPLICA_SAMPLER_PID=""
    fi
}

# 표본 로그(초 단위 정수, "NULL" 은 IO 스레드 중단 등으로 조회 불가했던 표본)에서
# 개수/최소/평균/최대를 뽑아 jq 조각으로 낸다. 표본이 하나도 없으면 samples=0 만 낸다.
replica_lag_stats_json() {
    if [[ -z "${REPLICA_LAG_LOG}" || ! -s "${REPLICA_LAG_LOG}" ]]; then
        echo '{"samples":0,"min":null,"avg":null,"max":null,"null_samples":0}'
        return
    fi
    awk '
        {
            total++
            if ($2 == "NULL") { nulls++; next }
            if ($2 !~ /^[0-9]+$/) { next }
            n++
            sum += $2
            if (n == 1 || $2 < lo) { lo = $2 }
            if (n == 1 || $2 > hi) { hi = $2 }
        }
        END {
            if (n == 0) {
                printf "{\"samples\":0,\"min\":null,\"avg\":null,\"max\":null,\"null_samples\":%d}\n", nulls + 0
                exit
            }
            printf "{\"samples\":%d,\"min\":%d,\"avg\":%.2f,\"max\":%d,\"null_samples\":%d}\n", n, lo, sum / n, hi, nulls + 0
        }
    ' "${REPLICA_LAG_LOG}"
}

K6_CPU_LOG=""
K6_CPU_SAMPLER_PID=""

# k6 프로세스 자신의 CPU 점유를 표본화한다 — 측정 대상(payment 앱)과 CPU 를 다투면 그것도
# 부하 도구발 오염이라 별도로 남긴다. k6 가 종료하면 kill -0 이 실패해 루프가 스스로 끝난다.
start_k6_cpu_sampler() {
    local k6_pid="$1"
    K6_CPU_LOG="$(mktemp "${ROOT_DIR}/results/.k6-cpu.${CASE_NAME}.XXXXXX")"
    (
        while kill -0 "${k6_pid}" 2>/dev/null; do
            cpu=$(ps -o %cpu= -p "${k6_pid}" 2>/dev/null | tr -d ' ')
            echo "$(date +%s) ${cpu:-NULL}" >> "${K6_CPU_LOG}"
            sleep "${K6_CPU_SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    K6_CPU_SAMPLER_PID=$!
}

# 루프가 k6 종료로 스스로 끝나므로 보통 필요 없지만, k6 가 비정상 종료해 표본화 서브셸이
# 아직 자고 있는 경우를 대비해 정리한다(REPLICA 표본화와 같은 이유로 확인 없이 SIGKILL 만 쏜다).
stop_k6_cpu_sampler() {
    if [[ -n "${K6_CPU_SAMPLER_PID}" ]]; then
        kill -9 "${K6_CPU_SAMPLER_PID}" 2>/dev/null || true
        K6_CPU_SAMPLER_PID=""
    fi
}

# 표본 로그(초 단위 정수 epoch, %cpu 값)에서 개수/최소/평균/최대를 뽑는다. %cpu 는 코어 하나
# 기준 퍼센트라 멀티스레드 프로세스는 100 을 넘을 수 있다 — 그대로 낸다.
k6_cpu_stats_json() {
    if [[ -z "${K6_CPU_LOG}" || ! -s "${K6_CPU_LOG}" ]]; then
        echo '{"samples":0,"min":null,"avg":null,"max":null}'
        return
    fi
    awk '
        {
            if ($2 !~ /^[0-9.]+$/) { next }
            n++
            sum += $2
            if (n == 1 || $2 + 0 < lo) { lo = $2 + 0 }
            if (n == 1 || $2 + 0 > hi) { hi = $2 + 0 }
        }
        END {
            if (n == 0) {
                print "{\"samples\":0,\"min\":null,\"avg\":null,\"max\":null}"
                exit
            }
            printf "{\"samples\":%d,\"min\":%.1f,\"avg\":%.2f,\"max\":%.1f}\n", n, lo, sum / n, hi
        }
    ' "${K6_CPU_LOG}"
}

# ---------------------------------------------------------------------------
# 자원 표본화 — MySQL(원본·복제본) / Redis(재고 캐시·멱등 저장소 클러스터) / 컨테이너별
# CPU·메모리·네트워크·디스크 IO. 셋 다 복제 지연 표본화와 같은 결(백그라운드 루프 → 로그 파일 →
# 사후 awk 집계)로 만든다. 전용 exporter 를 새로 띄우지 않고 이미 떠 있는 컨테이너에
# docker exec/docker stats 로 직접 묻는다 — 관측 스택에 상시 스크랩 대상을 늘리지 않는다.
# ---------------------------------------------------------------------------

MYSQL_STAT_LOG=""
MYSQL_STAT_SAMPLER_PID=""

# 컨테이너 하나의 실행 중 스레드 · 행 잠금 대기 · 커밋/IO fsync 대기를 한 행으로 낸다.
# SHOW GLOBAL STATUS 대신 performance_schema.global_status 를 세 개의 상관 서브쿼리로 묶어
# 컬럼 순서를 고정한다 — SHOW 결과의 행 순서는 서버 버전마다 보장되지 않는다.
# 실행 스레드 / 현재 잠금 대기 / fsync 대기에 더해 누적 잠금 대기 횟수와 평균 대기 시간을 낸다.
# "현재 대기 중"(CURRENT_WAITS)만 보면 짧게 스치는 경합이 표본 시점 사이로 빠져나간다 —
# 실제로 pg_inbox 에서 데드락이 초당 수 건씩 나는 동안에도 payment 원본의 CURRENT_WAITS 는
# 계속 0 이었다. 누적 카운터(ROW_LOCK_WAITS)는 그 사이 발생분을 놓치지 않는다.
mysql_stat_row() {
    local container="$1"
    docker exec "${container}" mysql -u root -p"${MYSQL_PAYMENT_ROOT_PASSWORD}" -N -B -e "
        SELECT
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='THREADS_RUNNING'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='INNODB_ROW_LOCK_CURRENT_WAITS'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='INNODB_OS_LOG_PENDING_FSYNCS')
          + (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='INNODB_DATA_PENDING_FSYNCS'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='INNODB_ROW_LOCK_WAITS'),
          (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='INNODB_ROW_LOCK_TIME_AVG');
    " 2>/dev/null
}

start_mysql_stat_sampler() {
    MYSQL_STAT_LOG="$(mktemp "${ROOT_DIR}/results/.mysql-stat.${CASE_NAME}.XXXXXX")"
    (
        while true; do
            source_row=$(mysql_stat_row "${MYSQL_PAYMENT_CONTAINER}")
            replica_row=$(mysql_stat_row "${MYSQL_PAYMENT_REPLICA_CONTAINER}")
            pg_row=$(mysql_stat_row "${MYSQL_PG_CONTAINER}")
            epoch=$(date +%s)
            echo "${epoch} source ${source_row:-NULL	NULL	NULL	NULL	NULL}" >> "${MYSQL_STAT_LOG}"
            echo "${epoch} replica ${replica_row:-NULL	NULL	NULL	NULL	NULL}" >> "${MYSQL_STAT_LOG}"
            echo "${epoch} pg ${pg_row:-NULL	NULL	NULL	NULL	NULL}" >> "${MYSQL_STAT_LOG}"
            sleep "${RESOURCE_SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    MYSQL_STAT_SAMPLER_PID=$!
}

stop_mysql_stat_sampler() {
    if [[ -n "${MYSQL_STAT_SAMPLER_PID}" ]]; then
        kill -9 "${MYSQL_STAT_SAMPLER_PID}" 2>/dev/null || true
        MYSQL_STAT_SAMPLER_PID=""
    fi
}

# 로그(epoch role threads_running row_lock_current_waits pending_fsyncs)에서 role(source/
# replica)별 개수/최소/평균/최대를 뽑는다. 값이 NULL 인 행(접속 실패)은 건너뛴다.
mysql_stat_stats_json() {
    if [[ -z "${MYSQL_STAT_LOG}" || ! -s "${MYSQL_STAT_LOG}" ]]; then
        echo '{"source":{"samples":0},"replica":{"samples":0},"pg":{"samples":0}}'
        return
    fi
    awk '
        {
            role = $2; tr = $3; lw = $4; pf = $5; rlw = $6; rlt = $7
            if (tr !~ /^[0-9.]+$/ || lw !~ /^[0-9.]+$/ || pf !~ /^[0-9.]+$/) { next }
            n[role]++
            sum_tr[role] += tr; sum_lw[role] += lw; sum_pf[role] += pf
            if (n[role] == 1 || tr + 0 < min_tr[role]) { min_tr[role] = tr + 0 }
            if (n[role] == 1 || tr + 0 > max_tr[role]) { max_tr[role] = tr + 0 }
            if (n[role] == 1 || lw + 0 < min_lw[role]) { min_lw[role] = lw + 0 }
            if (n[role] == 1 || lw + 0 > max_lw[role]) { max_lw[role] = lw + 0 }
            if (n[role] == 1 || pf + 0 < min_pf[role]) { min_pf[role] = pf + 0 }
            if (n[role] == 1 || pf + 0 > max_pf[role]) { max_pf[role] = pf + 0 }
            # 누적 카운터 — 구간 처음·끝 차이로 이 사이클에서 실제로 발생한 잠금 대기 수를 낸다.
            if (rlw ~ /^[0-9.]+$/) {
                if (!(role in seen_rlw)) { first_rlw[role] = rlw + 0; seen_rlw[role] = 1 }
                last_rlw[role] = rlw + 0
            }
            if (rlt ~ /^[0-9.]+$/) {
                if (rlt + 0 > max_rlt[role]) { max_rlt[role] = rlt + 0 }
            }
        }
        END {
            roles["source"] = 1; roles["replica"] = 1; roles["pg"] = 1
            printf "{"
            first = 1
            for (r in roles) {
                if (!first) { printf "," }
                first = 0
                if (n[r] + 0 == 0) {
                    printf "\"%s\":{\"samples\":0}", r
                } else {
                    printf "\"%s\":{\"samples\":%d,\"threads_running\":{\"min\":%d,\"avg\":%.2f,\"max\":%d},\"row_lock_current_waits\":{\"min\":%d,\"avg\":%.2f,\"max\":%d},\"pending_fsyncs\":{\"min\":%d,\"avg\":%.2f,\"max\":%d},\"row_lock_waits_delta\":%d,\"row_lock_time_avg_max_ms\":%d}",
                        r, n[r],
                        min_tr[r], sum_tr[r] / n[r], max_tr[r],
                        min_lw[r], sum_lw[r] / n[r], max_lw[r],
                        min_pf[r], sum_pf[r] / n[r], max_pf[r],
                        last_rlw[r] - first_rlw[r], max_rlt[r]
                }
            }
            printf "}"
        }
    ' "${MYSQL_STAT_LOG}"
}

REDIS_STAT_LOG=""
REDIS_STAT_SAMPLER_PID=""

# 컨테이너 하나의 초당 명령 · 블록된 클라이언트 · PING 왕복 지연(1초 표본)을 한 행으로 낸다.
# 지연은 redis-cli 내장 --latency 모드(가능한 한 빠르게 PING 을 보내 min/max/avg/count 를 낸다)의
# avg 값을 쓴다 — 애플리케이션 명령 자체의 지연은 아니지만 큐잉 지연을 드러내는 대표값이다.
redis_node_probe() {
    local cid="$1" info ops blocked latency
    info=$(docker exec "${cid}" redis-cli info 2>/dev/null)
    ops=$(echo "${info}" | awk -F: '/^instantaneous_ops_per_sec:/ { print $2 }' | tr -d '\r')
    blocked=$(echo "${info}" | awk -F: '/^blocked_clients:/ { print $2 }' | tr -d '\r')
    latency=$(docker exec "${cid}" timeout 1.2 redis-cli --latency -i 1 2>/dev/null | tail -1 | awk '{ print $3 }')
    echo "${ops:-NULL} ${blocked:-NULL} ${latency:-NULL}"
}

# 클러스터(재고 캐시/멱등 저장소)를 구성하는 실행 중 노드 전부를 순회해 초당 명령·블록된
# 클라이언트는 합으로, PING 지연은 노드 간 최댓값으로 묶는다. 노드가 하나도 없으면(사이클이
# 아직 클러스터를 구성하기 전) NULL 행을 남긴다.
start_redis_stat_sampler() {
    REDIS_STAT_LOG="$(mktemp "${ROOT_DIR}/results/.redis-stat.${CASE_NAME}.XXXXXX")"
    (
        while true; do
            for pair in "redis-stock-cluster stock" "redis-idempotency-cluster idempotency"; do
                service="${pair%% *}"
                label="${pair##* }"
                cids=($(dc ps -q "${service}" 2>/dev/null))
                ops_sum=0
                blocked_sum=0
                latency_max=0
                node_count=0
                for cid in "${cids[@]}"; do
                    probe=$(redis_node_probe "${cid}")
                    p_ops=$(echo "${probe}" | awk '{print $1}')
                    p_blocked=$(echo "${probe}" | awk '{print $2}')
                    p_latency=$(echo "${probe}" | awk '{print $3}')
                    if [[ "${p_ops}" != "NULL" ]]; then
                        ops_sum=$(awk -v a="${ops_sum}" -v b="${p_ops}" 'BEGIN { printf "%.2f", a + b }')
                        node_count=$((node_count + 1))
                    fi
                    if [[ "${p_blocked}" != "NULL" ]]; then
                        blocked_sum=$((blocked_sum + p_blocked))
                    fi
                    if [[ "${p_latency}" != "NULL" ]]; then
                        greater=$(awk -v a="${p_latency}" -v b="${latency_max}" 'BEGIN { print (a > b) ? 1 : 0 }')
                        [[ "${greater}" == "1" ]] && latency_max="${p_latency}"
                    fi
                done
                if [[ "${node_count}" -eq 0 ]]; then
                    echo "$(date +%s) ${label} NULL NULL NULL" >> "${REDIS_STAT_LOG}"
                else
                    echo "$(date +%s) ${label} ${ops_sum} ${blocked_sum} ${latency_max}" >> "${REDIS_STAT_LOG}"
                fi
            done
            sleep "${RESOURCE_SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    REDIS_STAT_SAMPLER_PID=$!
}

stop_redis_stat_sampler() {
    if [[ -n "${REDIS_STAT_SAMPLER_PID}" ]]; then
        kill -9 "${REDIS_STAT_SAMPLER_PID}" 2>/dev/null || true
        REDIS_STAT_SAMPLER_PID=""
    fi
}

# 로그(epoch label ops_per_sec blocked_clients latency_ms)에서 label(stock/idempotency)별
# 개수/최소/평균/최대를 뽑는다.
redis_stat_stats_json() {
    if [[ -z "${REDIS_STAT_LOG}" || ! -s "${REDIS_STAT_LOG}" ]]; then
        echo '{"stock":{"samples":0},"idempotency":{"samples":0}}'
        return
    fi
    awk '
        {
            label = $2; ops = $3; blocked = $4; lat = $5
            if (ops !~ /^[0-9.]+$/ || blocked !~ /^[0-9.]+$/ || lat !~ /^[0-9.]+$/) { next }
            n[label]++
            sum_ops[label] += ops; sum_blocked[label] += blocked; sum_lat[label] += lat
            if (n[label] == 1 || ops + 0 < min_ops[label]) { min_ops[label] = ops + 0 }
            if (n[label] == 1 || ops + 0 > max_ops[label]) { max_ops[label] = ops + 0 }
            if (n[label] == 1 || blocked + 0 < min_blocked[label]) { min_blocked[label] = blocked + 0 }
            if (n[label] == 1 || blocked + 0 > max_blocked[label]) { max_blocked[label] = blocked + 0 }
            if (n[label] == 1 || lat + 0 < min_lat[label]) { min_lat[label] = lat + 0 }
            if (n[label] == 1 || lat + 0 > max_lat[label]) { max_lat[label] = lat + 0 }
        }
        END {
            labels["stock"] = 1; labels["idempotency"] = 1
            printf "{"
            first = 1
            for (l in labels) {
                if (!first) { printf "," }
                first = 0
                if (n[l] + 0 == 0) {
                    printf "\"%s\":{\"samples\":0}", l
                } else {
                    printf "\"%s\":{\"samples\":%d,\"ops_per_sec\":{\"min\":%.1f,\"avg\":%.2f,\"max\":%.1f},\"blocked_clients\":{\"min\":%d,\"avg\":%.2f,\"max\":%d},\"ping_latency_ms\":{\"min\":%.2f,\"avg\":%.3f,\"max\":%.2f}}",
                        l, n[l],
                        min_ops[l], sum_ops[l] / n[l], max_ops[l],
                        min_blocked[l], sum_blocked[l] / n[l], max_blocked[l],
                        min_lat[l], sum_lat[l] / n[l], max_lat[l]
                }
            }
            printf "}"
        }
    ' "${REDIS_STAT_LOG}"
}

KAFKA_STAT_LOG=""
KAFKA_STAT_SAMPLER_PID=""

# 확정 명령(payment.commands.confirm)·확정 결과(payment.events.confirmed) 발행이 전부 거쳐
# 가는 단일 브로커라 Task 21 재측정에서 표본 대상에 추가했다(이전까지 컨테이너 통계에도 브로커
# 지표에도 빠져 있었다). 애플리케이션 토픽에 합성 메시지를 섞으면 소비자가 역직렬화/도메인
# 검증에 실패해 재시도 루프에 빠지므로(Task 21 r100 재측정에서 실제로 관측한 패턴), 전용
# 비운영 토픽(KAFKA_PROBE_TOPIC, 파티션 1·짧은 retention)에만 쏜다 — ensure_kafka_probe_topic
# 이 사이클 시작 시 1회 멱등 생성한다.
ensure_kafka_probe_topic() {
    docker exec "${KAFKA_CONTAINER}" kafka-topics --bootstrap-server localhost:9092 \
        --create --if-not-exists --topic "${KAFKA_PROBE_TOPIC}" \
        --partitions 1 --replication-factor 1 \
        --config retention.ms=600000 --config segment.bytes=1048576 >/dev/null 2>&1
}

# kafka-producer-perf-test 로 레코드 1건을 acks=all 로 보내 왕복 지연을 잰다. 이 도구는 매
# 호출마다 새 JVM 을 띄우므로 절대값에 수십~백여 ms 의 JVM 기동 오버헤드가 섞인다(idle 상태에서
# 실측 137~143ms) — 절대값보다 "부하 구간에서 이 기저선 대비 얼마나 튀는지"가 신호다. redis
# PING 지연 표본과 같은 성격의 대표값이지 실제 애플리케이션 EOS 트랜잭션 커밋 지연 그 자체는 아니다.
kafka_produce_latency_probe() {
    local out latency
    out=$(docker exec "${KAFKA_CONTAINER}" timeout 5 kafka-producer-perf-test \
        --topic "${KAFKA_PROBE_TOPIC}" --num-records 1 --record-size 64 --throughput 1 \
        --producer-props "bootstrap.servers=localhost:9092" acks=all 2>/dev/null)
    latency=$(echo "${out}" | grep -oE '[0-9.]+ ms avg latency' | awk '{print $1}')
    echo "${latency:-NULL}"
}

start_kafka_stat_sampler() {
    ensure_kafka_probe_topic
    KAFKA_STAT_LOG="$(mktemp "${ROOT_DIR}/results/.kafka-stat.${CASE_NAME}.XXXXXX")"
    (
        while true; do
            latency=$(kafka_produce_latency_probe)
            echo "$(date +%s) ${latency}" >> "${KAFKA_STAT_LOG}"
            sleep "${RESOURCE_SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    KAFKA_STAT_SAMPLER_PID=$!
}

stop_kafka_stat_sampler() {
    if [[ -n "${KAFKA_STAT_SAMPLER_PID}" ]]; then
        kill -9 "${KAFKA_STAT_SAMPLER_PID}" 2>/dev/null || true
        KAFKA_STAT_SAMPLER_PID=""
    fi
}

# 로그(epoch latency_ms, latency_ms 는 NULL 가능)에서 개수/최소/평균/최대를 낸다.
kafka_stat_stats_json() {
    if [[ -z "${KAFKA_STAT_LOG}" || ! -s "${KAFKA_STAT_LOG}" ]]; then
        echo '{"samples":0,"produce_latency_ms":null}'
        return
    fi
    awk '
        {
            if ($2 !~ /^[0-9.]+$/) { next }
            n++
            sum += $2
            if (n == 1 || $2 + 0 < lo) { lo = $2 + 0 }
            if (n == 1 || $2 + 0 > hi) { hi = $2 + 0 }
        }
        END {
            if (n + 0 == 0) {
                print "{\"samples\":0,\"produce_latency_ms\":null}"
                exit
            }
            printf "{\"samples\":%d,\"produce_latency_ms\":{\"min\":%.2f,\"avg\":%.2f,\"max\":%.2f}}\n", n, lo, sum / n, hi
        }
    ' "${KAFKA_STAT_LOG}"
}

BACKLOG_STAT_LOG=""
BACKLOG_STAT_SAMPLER_PID=""

# 미종결(payment_event.status IN READY/IN_PROGRESS/RETRYING) 건수를 부하 구간 동안만 주기로
# 잰다 — 능력 판정의 근거. 경사로가 아니라 고정 도착률로 충분히(예: 3분) 유지한 채, 이 값이
# 부하 내내 단조 증가하면 그 도착률이 서비스 능력을 넘었다는 뜻이고, 일정 범위에서 오르내리기만
# 하면 능력 안이라는 뜻이다 — 지연 백분위보다 판정이 명확하다(부하 종료 시점 평균/최종값만
# 보면 피크 구간에서만 밀린 것도 "능력 부족"으로 잘못 읽을 수 있다).
start_backlog_stat_sampler() {
    BACKLOG_STAT_LOG="$(mktemp "${ROOT_DIR}/results/.backlog-stat.${CASE_NAME}.XXXXXX")"
    (
        while true; do
            count=$(docker exec "${MYSQL_PAYMENT_CONTAINER}" mysql -u root -p"${MYSQL_PAYMENT_ROOT_PASSWORD}" -N -B -e "
                SELECT COUNT(*) FROM \`payment-platform\`.payment_event WHERE status IN ('READY','IN_PROGRESS','RETRYING','AWAITING_RESULT');
            " 2>/dev/null)
            echo "$(date +%s) ${count:-NULL}" >> "${BACKLOG_STAT_LOG}"
            sleep "${BACKLOG_SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    BACKLOG_STAT_SAMPLER_PID=$!
}

# 부하가 끝나는 즉시 멈춘다(다른 표본화처럼 settle 대기까지 끌고 가지 않는다) — settle 대기는
# 회수·재시도가 의도적으로 적체를 줄이는 구간이라 "부하 중 증가하는가" 판정과는 다른 질문이다.
stop_backlog_stat_sampler() {
    if [[ -n "${BACKLOG_STAT_SAMPLER_PID}" ]]; then
        kill -9 "${BACKLOG_STAT_SAMPLER_PID}" 2>/dev/null || true
        BACKLOG_STAT_SAMPLER_PID=""
    fi
}

# 로그(epoch unsettled_count, NULL 가능)를 시간순 표본 배열 + 최초/최종/최댓값으로 낸다.
# 자동으로 "능력 초과" 여부를 판정하지 않는다 — 완만한 톱니와 진짜 단조 증가를 가르는 것은
# 사람이 표본을 눈으로 보고 판단하는 편이 안전하다(다른 정합 게이트도 같은 철학).
backlog_trend_json() {
    if [[ -z "${BACKLOG_STAT_LOG}" || ! -s "${BACKLOG_STAT_LOG}" ]]; then
        echo '{"samples":0,"series":[],"first":null,"last":null,"max":null}'
        return
    fi
    awk '
        {
            if ($2 !~ /^[0-9]+$/) { next }
            n++
            if (n == 1) { first = $2 + 0 }
            last = $2 + 0
            if (n == 1 || $2 + 0 > hi) { hi = $2 + 0 }
            series = series (n == 1 ? "" : ",") "{\"epoch\":" $1 ",\"unsettled\":" $2 "}"
        }
        END {
            if (n + 0 == 0) {
                print "{\"samples\":0,\"series\":[],\"first\":null,\"last\":null,\"max\":null}"
                exit
            }
            printf "{\"samples\":%d,\"series\":[%s],\"first\":%d,\"last\":%d,\"max\":%d}\n", n, series, first, last, hi
        }
    ' "${BACKLOG_STAT_LOG}"
}

CONTAINER_STAT_LOG=""
CONTAINER_STAT_SAMPLER_PID=""

# docker stats 가 내는 사람이 읽는 단위(예: "794.5MiB", "3.42GB")를 바이트 정수로 바꾼다.
# Docker CLI 표기 그대로 메모리는 이진 단위(KiB/MiB/GiB), 네트워크·디스크 IO 는 십진 단위
# (kB/MB/GB)를 쓴다.
bytes_from_human() {
    # 숫자부는 지수 표기(예: "1e+03MB")까지 포함해 떼어낸다 — Docker CLI 가 값이 999.5~1000
    # 근처일 때 다음 단위로 안 올리고 "1e+03MB" 처럼 지수로 낼 때가 있다(실측으로 발견). 이걸
    # 놓치면 단위 판별이 "e+03MB" 로 어긋나 배수를 못 찾고 1000배 축소된 값이 남는다.
    echo "$1" | awk '
        {
            v = $0
            match(v, /^[0-9.]+([eE][+-]?[0-9]+)?/)
            num = (RLENGTH > 0) ? substr(v, RSTART, RLENGTH) + 0 : 0
            u = substr(v, RSTART + RLENGTH)
            mult = 1
            if (u == "kB") { mult = 1000 }
            else if (u == "KiB") { mult = 1024 }
            else if (u == "MB") { mult = 1000 * 1000 }
            else if (u == "MiB") { mult = 1024 * 1024 }
            else if (u == "GB") { mult = 1000 * 1000 * 1000 }
            else if (u == "GiB") { mult = 1024 * 1024 * 1024 }
            else if (u == "TB") { mult = 1000 * 1000 * 1000 * 1000 }
            else if (u == "TiB") { mult = 1024 * 1024 * 1024 * 1024 }
            printf "%.0f", num * mult
        }'
}

# 이 사이클에 관여하는 서비스의 실행 중 컨테이너 이름 전부 — 대수가 조건 값에 따라 바뀌는
# payment-service/redis-*-cluster 도 dc ps 로 그때그때 다시 구한다. kafka 는 확정 명령/결과
# 발행·소비가 전부 거쳐 가는 단일 브로커라 Task 21 재측정에서 표본 대상에 추가했다(이전까지 누락).
container_stat_targets() {
    local svc names=() cids cid name
    for svc in payment-service pg-service product-service user-service gateway \
        mysql-payment mysql-payment-replica redis-stock-cluster redis-idempotency-cluster kafka; do
        cids=($(dc ps -q "${svc}" 2>/dev/null))
        for cid in "${cids[@]}"; do
            name=$(docker inspect --format '{{.Name}}' "${cid}" 2>/dev/null | sed 's#^/##')
            [[ -n "${name}" ]] && names+=("${name}")
        done
    done
    echo "${names[@]}"
}

start_container_stat_sampler() {
    CONTAINER_STAT_LOG="$(mktemp "${ROOT_DIR}/results/.container-stat.${CASE_NAME}.XXXXXX")"
    (
        while true; do
            targets=($(container_stat_targets))
            if [[ "${#targets[@]}" -gt 0 ]]; then
                epoch=$(date +%s)
                docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.NetIO}}|{{.BlockIO}}' \
                    "${targets[@]}" 2>/dev/null | while IFS='|' read -r name cpu mem net block; do
                    cpu_num="${cpu%\%}"
                    mem_used="${mem%% / *}"
                    net_rx="${net%% / *}"
                    net_tx="${net##* / }"
                    block_read="${block%% / *}"
                    block_write="${block##* / }"
                    mem_bytes=$(bytes_from_human "${mem_used}")
                    net_rx_bytes=$(bytes_from_human "${net_rx}")
                    net_tx_bytes=$(bytes_from_human "${net_tx}")
                    block_read_bytes=$(bytes_from_human "${block_read}")
                    block_write_bytes=$(bytes_from_human "${block_write}")
                    echo "${epoch} ${name} ${cpu_num} ${mem_bytes} ${net_rx_bytes} ${net_tx_bytes} ${block_read_bytes} ${block_write_bytes}" >> "${CONTAINER_STAT_LOG}"
                done
            fi
            sleep "${RESOURCE_SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    CONTAINER_STAT_SAMPLER_PID=$!
}

stop_container_stat_sampler() {
    if [[ -n "${CONTAINER_STAT_SAMPLER_PID}" ]]; then
        kill -9 "${CONTAINER_STAT_SAMPLER_PID}" 2>/dev/null || true
        CONTAINER_STAT_SAMPLER_PID=""
    fi
}

# 로그(epoch name cpu_percent mem_bytes net_rx net_tx block_read block_write)에서 컨테이너별
# CPU%·메모리는 최소/평균/최대로, 네트워크·디스크 IO는 표본 구간 처음·끝 누적치의 차이를
# 구간 길이로 나눈 평균 처리율(byte/s)로 낸다 — docker stats 의 NetIO/BlockIO는 컨테이너 시작
# 이후 누적값이라 순간값이 아니라 두 지점 차이로만 처리율을 알 수 있다.
container_stat_stats_json() {
    if [[ -z "${CONTAINER_STAT_LOG}" || ! -s "${CONTAINER_STAT_LOG}" ]]; then
        echo '{}'
        return
    fi
    awk '
        {
            name = $2; cpu = $3 + 0; mem = $4 + 0
            net_rx = $5 + 0; net_tx = $6 + 0; blk_r = $7 + 0; blk_w = $8 + 0
            epoch = $1 + 0
            if (!(name in n)) {
                first_epoch[name] = epoch
                first_net_rx[name] = net_rx; first_net_tx[name] = net_tx
                first_blk_r[name] = blk_r; first_blk_w[name] = blk_w
            }
            n[name]++
            sum_cpu[name] += cpu; sum_mem[name] += mem
            if (n[name] == 1 || cpu < min_cpu[name]) { min_cpu[name] = cpu }
            if (n[name] == 1 || cpu > max_cpu[name]) { max_cpu[name] = cpu }
            if (n[name] == 1 || mem < min_mem[name]) { min_mem[name] = mem }
            if (n[name] == 1 || mem > max_mem[name]) { max_mem[name] = mem }
            last_epoch[name] = epoch
            last_net_rx[name] = net_rx; last_net_tx[name] = net_tx
            last_blk_r[name] = blk_r; last_blk_w[name] = blk_w
        }
        END {
            printf "{"
            first = 1
            for (name in n) {
                if (!first) { printf "," }
                first = 0
                span = last_epoch[name] - first_epoch[name]
                if (span > 0) {
                    net_rx_rate = (last_net_rx[name] - first_net_rx[name]) / span
                    net_tx_rate = (last_net_tx[name] - first_net_tx[name]) / span
                    blk_r_rate = (last_blk_r[name] - first_blk_r[name]) / span
                    blk_w_rate = (last_blk_w[name] - first_blk_w[name]) / span
                } else {
                    net_rx_rate = 0; net_tx_rate = 0; blk_r_rate = 0; blk_w_rate = 0
                }
                printf "\"%s\":{\"samples\":%d,\"cpu_percent\":{\"min\":%.2f,\"avg\":%.2f,\"max\":%.2f},\"mem_bytes\":{\"min\":%d,\"avg\":%.0f,\"max\":%d},\"net_bytes_per_sec\":{\"rx\":%.1f,\"tx\":%.1f},\"block_bytes_per_sec\":{\"read\":%.1f,\"write\":%.1f}}",
                    name, n[name],
                    min_cpu[name], sum_cpu[name] / n[name], max_cpu[name],
                    min_mem[name], sum_mem[name] / n[name], max_mem[name],
                    net_rx_rate, net_tx_rate, blk_r_rate, blk_w_rate
            }
            printf "}"
        }
    ' "${CONTAINER_STAT_LOG}"
}

# ---------------------------------------------------------------------------
# CPU 스로틀 표본화 — cgroup v2 cpu.stat / cpu.max 를 컨테이너 안에서 직접 읽는다.
#
# docker stats 의 CPU% 는 "얼마나 썼나"만 알려주고 "상한에 막혔나"는 알려주지 않는다.
# 상한을 걸고 재는 측정에서는 이 구분이 판정을 가른다:
#   - payment 가 스로틀되고 공유 자원은 아니면 → 앱이 병목. 인스턴스를 늘리면 이득이 난다
#   - 아무것도 스로틀되지 않는데 처리량이 평평하면 → 진짜 공유 자원/직렬화 천장
#   - 공유 자원이 스로틀되면 → 그 자원이 천장(예산을 올려 재측정해야 한다)
#
# nr_throttled / throttled_usec 는 누적값이라 구간 처음·끝의 차이로만 뜻이 생긴다.
# throttled_ratio = 구간 스로틀 시간 / 구간 벽시계 시간 — 1 에 가까울수록 계속 막혀 있었다는 뜻.
# cpu.max 가 "max" 면 상한이 없는 것이므로 quota_cpus 를 0 으로 남긴다.
# ---------------------------------------------------------------------------

CPU_THROTTLE_LOG=""
CPU_THROTTLE_SAMPLER_PID=""

start_cpu_throttle_sampler() {
    CPU_THROTTLE_LOG="$(mktemp "${ROOT_DIR}/results/.cpu-throttle.${CASE_NAME}.XXXXXX")"
    (
        while true; do
            targets=($(container_stat_targets))
            epoch=$(date +%s)
            for name in "${targets[@]}"; do
                stat=$(docker exec "${name}" cat /sys/fs/cgroup/cpu.stat 2>/dev/null) || continue
                max=$(docker exec "${name}" cat /sys/fs/cgroup/cpu.max 2>/dev/null) || continue
                nr=$(echo "${stat}" | awk '$1=="nr_throttled"{print $2}')
                tu=$(echo "${stat}" | awk '$1=="throttled_usec"{print $2}')
                uu=$(echo "${stat}" | awk '$1=="usage_usec"{print $2}')
                quota=$(echo "${max}" | awk '{print $1}')
                period=$(echo "${max}" | awk '{print $2}')
                if [[ "${quota}" == "max" || -z "${period}" || "${period}" == "0" ]]; then
                    quota_cpus=0
                else
                    quota_cpus=$(awk -v q="${quota}" -v p="${period}" 'BEGIN{printf "%.3f", q/p}')
                fi
                [[ -z "${nr}" || -z "${tu}" || -z "${uu}" ]] && continue
                echo "${epoch} ${name} ${nr} ${tu} ${uu} ${quota_cpus}" >> "${CPU_THROTTLE_LOG}"
            done
            sleep "${RESOURCE_SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    CPU_THROTTLE_SAMPLER_PID=$!
}

stop_cpu_throttle_sampler() {
    if [[ -n "${CPU_THROTTLE_SAMPLER_PID}" ]]; then
        kill -9 "${CPU_THROTTLE_SAMPLER_PID}" 2>/dev/null || true
        CPU_THROTTLE_SAMPLER_PID=""
    fi
}

cpu_throttle_stats_json() {
    if [[ -z "${CPU_THROTTLE_LOG}" || ! -s "${CPU_THROTTLE_LOG}" ]]; then
        echo '{}'
        return
    fi
    awk '
        {
            name = $2; nr = $3 + 0; tu = $4 + 0; uu = $5 + 0; q = $6 + 0
            epoch = $1 + 0
            if (!(name in n)) {
                first_epoch[name] = epoch; first_nr[name] = nr
                first_tu[name] = tu; first_uu[name] = uu
            }
            n[name]++
            quota[name] = q
            last_epoch[name] = epoch; last_nr[name] = nr
            last_tu[name] = tu; last_uu[name] = uu
        }
        END {
            printf "{"
            first = 1
            for (name in n) {
                if (!first) { printf "," }
                first = 0
                span = last_epoch[name] - first_epoch[name]
                d_nr = last_nr[name] - first_nr[name]
                d_tu = last_tu[name] - first_tu[name]
                d_uu = last_uu[name] - first_uu[name]
                if (span > 0) {
                    ratio = d_tu / (span * 1000000.0)
                    used_cpus = d_uu / (span * 1000000.0)
                } else {
                    ratio = 0; used_cpus = 0
                }
                if (quota[name] > 0) { headroom = used_cpus / quota[name] } else { headroom = 0 }
                printf "\"%s\":{\"samples\":%d,\"quota_cpus\":%.3f,\"used_cpus\":%.3f,\"quota_utilization\":%.3f,\"throttled_periods\":%d,\"throttled_ratio\":%.4f}",
                    name, n[name], quota[name], used_cpus, headroom, d_nr, ratio
            }
            printf "}"
        }
    ' "${CPU_THROTTLE_LOG}"
}

# ---------------------------------------------------------------------------
# 돈 경로 소비 적체 표본화 — 결제가 어느 단계에서 기다리는지 가른다.
#
# 지금까지 표본화한 적체는 재고 확정(product-service-stock-commit) 하나뿐이라, 정작 돈이
# 흐르는 두 경로의 적체를 한 번도 재지 않았다. 부하 중 미종결이 수천~수만 건 쌓이는데 그것이
# 어느 단계에 있는지 모르면 무엇을 늘려야 처리량이 오르는지 고를 수 없다.
#
#   pg-service       — payment.commands.confirm 소비(확정 명령 → 벤더 호출)
#   payment-service  — payment.events.confirmed 소비(확정 결과 → 원장 종결)
#
# 읽는 법:
#   pg 적체가 크다        → 벤더 호출 단계가 병목. pg-service 를 늘리면 처리량이 는다
#   payment 적체가 크다   → 확정 결과 소비가 병목. 파티션·컨슈머 동시성 영역
#   둘 다 작은데 미종결이 크다 → 큐에 쌓인 게 아니라 전부 처리 중(in-flight). 자원이 아니라
#                          왕복 지연 × 동시성이 천장이라는 뜻이다
#
# 트랜잭션 커밋 표시가 파티션마다 오프셋을 차지해 적체는 0 까지 내려가지 않는다 — 절대값이 아니라
# 구성 간 크기 비교로 읽는다.
# ---------------------------------------------------------------------------

MONEYPATH_LAG_LOG=""
MONEYPATH_LAG_SAMPLER_PID=""
MONEYPATH_GROUPS="${MONEYPATH_GROUPS:-pg-service payment-service}"

# 그룹의 파티션별 lag 합과 파티션 수를 낸다. 조회 실패나 그룹 없음이면 "NULL 0".
kafka_group_lag() {
    local group="$1" out
    out=$(docker exec "${KAFKA_CONTAINER}" kafka-consumer-groups \
        --bootstrap-server localhost:9092 --describe --group "${group}" 2>/dev/null)
    if [[ -z "${out}" ]]; then
        echo "NULL 0"
        return
    fi
    echo "${out}" | awk '
        $1 == "GROUP" { next }
        NF >= 6 && $6 ~ /^[0-9]+$/ { sum += $6; partitions++ }
        END {
            if (!partitions) { print "NULL 0"; exit }
            print sum, partitions
        }
    '
}

start_moneypath_lag_sampler() {
    MONEYPATH_LAG_LOG="$(mktemp "${ROOT_DIR}/results/.moneypath-lag.${CASE_NAME}.XXXXXX")"
    (
        while true; do
            epoch=$(date +%s)
            for g in ${MONEYPATH_GROUPS}; do
                read -r lag parts <<< "$(kafka_group_lag "${g}")"
                [[ "${lag}" == "NULL" ]] && continue
                echo "${epoch} ${g} ${lag} ${parts}" >> "${MONEYPATH_LAG_LOG}"
            done
            sleep "${RESOURCE_SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    MONEYPATH_LAG_SAMPLER_PID=$!
}

stop_moneypath_lag_sampler() {
    if [[ -n "${MONEYPATH_LAG_SAMPLER_PID}" ]]; then
        kill -9 "${MONEYPATH_LAG_SAMPLER_PID}" 2>/dev/null || true
        MONEYPATH_LAG_SAMPLER_PID=""
    fi
}

moneypath_lag_stats_json() {
    if [[ -z "${MONEYPATH_LAG_LOG}" || ! -s "${MONEYPATH_LAG_LOG}" ]]; then
        echo '{}'
        return
    fi
    awk '
        {
            g = $2; lag = $3 + 0; parts = $4 + 0
            n[g]++
            sum[g] += lag
            partitions[g] = parts
            if (n[g] == 1 || lag < lo[g]) { lo[g] = lag }
            if (n[g] == 1 || lag > hi[g]) { hi[g] = lag }
            last[g] = lag
        }
        END {
            printf "{"
            first = 1
            for (g in n) {
                if (!first) { printf "," }
                first = 0
                printf "\"%s\":{\"samples\":%d,\"partitions\":%d,\"lag\":{\"min\":%d,\"avg\":%.1f,\"max\":%d,\"last\":%d}}",
                    g, n[g], partitions[g], lo[g], sum[g] / n[g], hi[g], last[g]
            }
            printf "}"
        }
    ' "${MONEYPATH_LAG_LOG}"
}

# ---------------------------------------------------------------------------
# 종료 신호에도 표본화를 정리한다.
#
# 표본화는 전부 `while true` 서브셸이라 이 스크립트가 죽어도 부모만 사라지고 자식은 PPID=1 로
# 살아남는다. 그 상태로 15초마다 docker exec · docker stats · kafka-consumer-groups(JVM 기동)를
# 계속 쏘면 이후 측정이 조용히 오염된다 — 실측으로 겪었다: 중단한 시리즈의 샘플러 6개가
# 1시간 35분 동안 살아 있었고, 그 위에서 잰 1대 처리량이 51.8 → 28.2/s 로 떨어졌으며
# Kafka 브로커가 로그 디렉토리 장애로 죽었다.
#
# 정상 종료 경로의 stop_*_sampler 호출은 그대로 두고(중복 호출은 무해하다), 여기에 EXIT/INT/TERM
# 안전망을 얹는다.
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# pg 큐 깊이 표본화 — 확정 명령이 pg 안에서 어느 단계에 머무는지 센다.
#
# Kafka 소비 적체는 "아직 안 꺼낸 메시지"만 보여준다. 꺼낸 뒤 pg_inbox 에서 벤더 응답을
# 기다리는 물량(IN_PROGRESS)이나 아직 착수 못 한 물량(PENDING)은 거기 안 잡힌다.
# 벤더 호출 단계에 압력이 몰리는지 보려면 이 값이 필요하다.
# pg_outbox 의 미발행분(processed_at IS NULL)은 확정 결과를 되돌리는 릴레이의 적체다.
# ---------------------------------------------------------------------------
PG_QUEUE_LOG=""
PG_QUEUE_SAMPLER_PID=""

start_pg_queue_sampler() {
    PG_QUEUE_LOG="$(mktemp "${ROOT_DIR}/results/.pg-queue.${CASE_NAME}.XXXXXX")"
    (
        while true; do
            row=$(docker exec "${MYSQL_PG_CONTAINER}" mysql -u root -p"${MYSQL_PAYMENT_ROOT_PASSWORD}" -N -B -e "
                SELECT
                  (SELECT COUNT(*) FROM pg.pg_inbox WHERE status='PENDING'),
                  (SELECT COUNT(*) FROM pg.pg_inbox WHERE status='IN_PROGRESS'),
                  (SELECT COUNT(*) FROM pg.pg_outbox WHERE processed_at IS NULL);
            " 2>/dev/null)
            [[ -n "${row}" ]] && echo "$(date +%s) ${row}" >> "${PG_QUEUE_LOG}"
            sleep "${RESOURCE_SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    PG_QUEUE_SAMPLER_PID=$!
}

stop_pg_queue_sampler() {
    if [[ -n "${PG_QUEUE_SAMPLER_PID}" ]]; then
        kill -9 "${PG_QUEUE_SAMPLER_PID}" 2>/dev/null || true
        PG_QUEUE_SAMPLER_PID=""
    fi
}

pg_queue_stats_json() {
    if [[ -z "${PG_QUEUE_LOG}" || ! -s "${PG_QUEUE_LOG}" ]]; then
        echo '{}'
        return
    fi
    awk '
        {
            n++
            p = $2 + 0; i = $3 + 0; o = $4 + 0
            sp += p; si += i; so += o
            if (n == 1 || p > mp) { mp = p }
            if (n == 1 || i > mi) { mi = i }
            if (n == 1 || o > mo) { mo = o }
        }
        END {
            if (n == 0) { print "{}"; exit }
            printf "{\"samples\":%d,\"inbox_pending\":{\"avg\":%.1f,\"max\":%d},\"inbox_in_progress\":{\"avg\":%.1f,\"max\":%d},\"outbox_unsent\":{\"avg\":%.1f,\"max\":%d}}",
                n, sp/n, mp, si/n, mi, so/n, mo
        }
    ' "${PG_QUEUE_LOG}"
}

cleanup_all_samplers() {
    stop_replica_lag_sampler 2>/dev/null || true
    stop_k6_cpu_sampler 2>/dev/null || true
    stop_mysql_stat_sampler 2>/dev/null || true
    stop_redis_stat_sampler 2>/dev/null || true
    stop_kafka_stat_sampler 2>/dev/null || true
    stop_backlog_stat_sampler 2>/dev/null || true
    stop_container_stat_sampler 2>/dev/null || true
    stop_cpu_throttle_sampler 2>/dev/null || true
    stop_moneypath_lag_sampler 2>/dev/null || true
    stop_pg_queue_sampler 2>/dev/null || true
}
trap cleanup_all_samplers EXIT INT TERM

check_docker

# ---------------------------------------------------------------------------
# 선결 확인 — k6 / jq
# ---------------------------------------------------------------------------

if ! command -v k6 >/dev/null 2>&1; then
    print_error "❌ k6 가 설치되어 있지 않다 — https://grafana.com/docs/k6/latest/get-started/installation/"
    exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
    print_error "❌ jq 가 설치되어 있지 않다"
    exit 1
fi

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ bench-scaleout-cycle — ${CASE_NAME}"
print_section "  instances=${INSTANCES} stock_masters=${STOCK_MASTERS} dedupe_masters=${DEDUPE_MASTERS}"
print_section "  polling_route=${POLLING_ROUTE} items_per_order=${ITEMS_PER_ORDER} vendor_latency=${VENDOR_LATENCY}(${FAKE_LATENCY_MIN}~${FAKE_LATENCY_MAX}ms)"
print_section "  confirmed_consumer_concurrency=${CONFIRMED_CONSUMER_CONCURRENCY} pg_inbox_workers=${PG_INBOX_WORKERS} pg_outbox_workers=${PG_OUTBOX_WORKERS}"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

mkdir -p "${RESULTS_DIR}"

# ---------------------------------------------------------------------------
# (1) 스택 기동 — 관측 스택 포함. 재고/멱등 캐시 클러스터는 대수 조정이 필요해
#     여기서 올리지 않고 (2)에서 scripts/bench-redis-cluster.sh 가 전담한다.
#     user-service 를 payment-service 보다 먼저 올린다(Eureka 스냅샷 레이스 회피).
# ---------------------------------------------------------------------------

print_section "▶ (1) 스택 기동"

if ! dc up -d eureka kafka redis-dedupe redis-stock \
    mysql-payment mysql-payment-replica mysql-pg mysql-product mysql-user \
    prometheus alertmanager grafana kafka-exporter tempo loki promtail >/dev/null 2>&1; then
    print_error "❌ (1) 인프라/관측 스택 기동 실패"
    exit 1
fi

if ! wait_healthy mysql-payment-replica 90; then exit 1; fi

if ! dc up -d user-service >/dev/null 2>&1; then
    print_error "❌ (1) user-service 기동 실패"
    exit 1
fi
if ! wait_healthy user-service 90 || ! wait_eureka_registered "USER-SERVICE" 60; then
    exit 1
fi

if ! dc up -d product-service >/dev/null 2>&1; then
    print_error "❌ (1) product-service 기동 실패"
    exit 1
fi
if ! wait_healthy product-service 90; then exit 1; fi

print_info "✅ (1) 인프라/관측/user-service/product-service 기동 완료"
echo ""

# 사이클 시작 시점 오염 점검 — 이전 사이클이 PASS 로 끝나지 않으면(INCONCLUSIVE/MISMATCH 로
# 재구성 없이 멈추면) 미종결 payment_event 와 미회수 stock_hold_record 가 남는다. 그 위에
# 새 부하를 얹으면 이번 사이클의 처리율·지연이 이전 사이클 잔여와 섞여 무효가 된다(Task 20
# 실측 — task20-r75 첫 실행이 task20-r100 의 INCONCLUSIVE 잔여 위에서 돌아 db_done=0 으로
# 나온 사고). 값을 결과 JSON 에 그대로 남겨 사후에 오염 여부를 기계적으로 가릴 수 있게 한다 —
# 이 스크립트가 자동으로 멈추지는 않는다(잔류 처리는 여전히 사람 판단 영역, bench-cycle-reset.sh
# 와 동일한 철학).
PRE_CYCLE_UNSETTLED=$(docker exec "${MYSQL_PAYMENT_CONTAINER}" mysql -u root -p"${MYSQL_PAYMENT_ROOT_PASSWORD}" -N -B -e "
    SELECT COUNT(*) FROM \`payment-platform\`.payment_event WHERE status IN ('READY','IN_PROGRESS','RETRYING','AWAITING_RESULT');
" 2>/dev/null || echo "-1")
PRE_CYCLE_NOISE=$(docker exec "${MYSQL_PAYMENT_CONTAINER}" mysql -u root -p"${MYSQL_PAYMENT_ROOT_PASSWORD}" -N -B -e "
    SELECT COUNT(*) FROM \`payment-platform\`.stock_hold_record WHERE status = 'NOISE';
" 2>/dev/null || echo "-1")
# 조회 실패(-1)와 실제 잔류를 구분한다 — 둘 다 "잔류 있음"으로 뭉쳐 경고하면, DB 가 아직 안 뜬
# 상태의 조회 실패를 이전 사이클 잔류로 오독한다(실측: MySQL 재생성 직후 -1 이 잔류 경고로 나왔다).
# 조회 실패는 잔류 여부를 모른다는 뜻이지 잔류가 있다는 뜻이 아니다.
if [[ "${PRE_CYCLE_UNSETTLED}" == "-1" || "${PRE_CYCLE_NOISE}" == "-1" ]]; then
    print_warning "⚠️  사이클 시작 시점 잔류를 확인하지 못했다 — DB 조회 실패(컨테이너 ${MYSQL_PAYMENT_CONTAINER} 기동 중이거나 인증 실패). 잔류가 있다는 뜻이 아니라 알 수 없다는 뜻이다"
elif [[ "${PRE_CYCLE_UNSETTLED}" != "0" || "${PRE_CYCLE_NOISE}" != "0" ]]; then
    print_warning "⚠️  사이클 시작 시점에 잔류가 있다 — 미종결=${PRE_CYCLE_UNSETTLED} 미회수 선차감 기록=${PRE_CYCLE_NOISE}. 이전 사이클이 PASS 로 끝나지 않았을 수 있다. 이번 사이클 결과는 오염됐을 수 있으니 결과 JSON 의 pre_cycle_state 를 확인하라"
fi

# ---------------------------------------------------------------------------
# (2) 클러스터 구성 — 재고 캐시(대수는 조건 값) + 멱등 저장소(고정 3), 이어서
#     payment-service/pg-service 를 조건 값 env 로 재기동한다.
# ---------------------------------------------------------------------------

print_section "▶ (2) 클러스터 구성 — 재고 캐시 ${STOCK_MASTERS}대 / 멱등 저장소 ${DEDUPE_MASTERS}대"

if ! bash "${ROOT_DIR}/scripts/bench-redis-cluster.sh" --store stock --masters "${STOCK_MASTERS}"; then
    print_error "❌ (2) 재고 캐시 클러스터 구성 실패"
    exit 1
fi
if ! bash "${ROOT_DIR}/scripts/bench-redis-cluster.sh" --store dedupe --masters "${DEDUPE_MASTERS}"; then
    print_error "❌ (2) 멱등 저장소 클러스터 구성 실패"
    exit 1
fi

STOCK_NODES_CSV=$(cluster_nodes_csv redis-stock-cluster)
DEDUPE_NODES_CSV=$(cluster_nodes_csv redis-idempotency-cluster)
if [[ -z "${STOCK_NODES_CSV}" || -z "${DEDUPE_NODES_CSV}" ]]; then
    print_error "❌ (2) 클러스터 노드 목록 계산 실패"
    exit 1
fi
print_info "  재고 캐시 노드: ${STOCK_NODES_CSV}"
print_info "  멱등 저장소 노드: ${DEDUPE_NODES_CSV}"

if [[ "${POLLING_ROUTE}" == "on" ]]; then
    REPLICA_ENABLED="true"
else
    REPLICA_ENABLED="false"
fi

print_section "  payment-service 재기동 — 인스턴스 ${INSTANCES}대, 폴링 라우팅=${POLLING_ROUTE}"
export REDIS_STOCK_CLUSTER_NODES="${STOCK_NODES_CSV}"
export SPRING_DATA_REDIS_CLUSTER_NODES="${DEDUPE_NODES_CSV}"
export PAYMENT_DATASOURCE_REPLICA_ENABLED="${REPLICA_ENABLED}"
export RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT}"
export RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS}"
export HIKARI_MAX_POOL="${HIKARI_MAX_POOL}"
export CONFIRMED_CONSUMER_CONCURRENCY="${CONFIRMED_CONSUMER_CONCURRENCY}"
if ! dc up -d --scale payment-service="${INSTANCES}" --force-recreate payment-service >/dev/null 2>&1; then
    print_error "❌ (2) payment-service 재기동 실패"
    exit 1
fi
if ! wait_all_healthy payment-service "${INSTANCES}" 150; then exit 1; fi
if ! wait_eureka_registered "PAYMENT-SERVICE" 60; then exit 1; fi
print_info "✅ payment-service ${INSTANCES}대 healthy + Eureka 등록 확인"

print_section "  pg-service 재기동 — ${PG_INSTANCES}대, 벤더 지연=${VENDOR_LATENCY}(${FAKE_LATENCY_MIN}~${FAKE_LATENCY_MAX}ms)"
export FAKE_LATENCY_MIN="${FAKE_LATENCY_MIN}"
export FAKE_LATENCY_MAX="${FAKE_LATENCY_MAX}"
export FAKE_FAIL_RATE="${FAKE_FAIL_RATE}"
export PG_INBOX_WORKERS="${PG_INBOX_WORKERS}"
export PG_OUTBOX_WORKERS="${PG_OUTBOX_WORKERS}"
if ! dc up -d --scale pg-service="${PG_INSTANCES}" --force-recreate pg-service >/dev/null 2>&1; then
    print_error "❌ (2) pg-service 재기동 실패"
    exit 1
fi
if ! wait_all_healthy pg-service "${PG_INSTANCES}" 150; then exit 1; fi
print_info "✅ pg-service healthy"

# --no-deps 필수 — gateway 는 payment-service 를 depends_on 으로 갖는데, docker compose 는
# --scale 값을 이 호출 하나에만 두고 영구화하지 않는다. --no-deps 없이 이 호출을 실행하면
# compose 가 payment-service 를 기본 대수(1)로 되돌리면서 방금 위에서 스케일한 2번째 이상
# 인스턴스를 즉시 삭제한다(정지가 아니라 삭제라 docker ps -a 에도 남지 않는다) — 이 사이클
# 러너를 실제로 돌려 "인스턴스 2대 사이클인데 payment-service 가 1대로 줄어 있다"는 증상을
# 실측으로 확인해 원인을 좁혔다. gateway 의 의존 서비스(eureka/payment-service/pg-service/
# product-service/user-service)는 이 지점에 전부 이미 healthy 로 떠 있으므로 의존 해석 없이
# gateway 하나만 올리면 된다.
if ! dc up -d --no-deps gateway >/dev/null 2>&1; then
    print_error "❌ (2) gateway 기동 실패"
    exit 1
fi
if ! wait_healthy gateway 90; then exit 1; fi
if ! wait_gateway_routes_payment_service 30; then exit 1; fi
print_info "✅ gateway healthy — payment-service 라우팅 확인"
echo ""

# ---------------------------------------------------------------------------
# (3) 시드
# ---------------------------------------------------------------------------

print_section "▶ (3) 재고 시드 — ${PRODUCT_COUNT}종, stock=${BENCH_STOCK}"

STOCK_CID=$(dc ps -q redis-stock-cluster 2>/dev/null | head -n1)
STOCK_CONTAINER_NAME=$(docker inspect --format '{{.Name}}' "${STOCK_CID}" 2>/dev/null | sed 's#^/##')
if [[ -z "${STOCK_CONTAINER_NAME}" ]]; then
    print_error "❌ (3) redis-stock-cluster 컨테이너를 찾지 못함"
    exit 1
fi

if ! PRODUCT_COUNT="${PRODUCT_COUNT}" PRODUCT_ID_BASE="${PRODUCT_ID_BASE}" BENCH_STOCK="${BENCH_STOCK}" \
    REDIS_STOCK_CONTAINER="${STOCK_CONTAINER_NAME}" \
    bash "${ROOT_DIR}/scripts/bench-seed-stock.sh"; then
    print_error "❌ (3) 재고 시드 실패"
    exit 1
fi
echo ""

# ---------------------------------------------------------------------------
# (4) 부하 — 복제 지연 표본화는 부하 시작부터 정합 검증이 끝날 때까지 계속한다.
# ---------------------------------------------------------------------------

print_section "▶ (4) 부하 실행 — CASE_NAME=${CASE_NAME}"

# 지연 표본이 만드는 추가 부하를 미리 알린다. 표본 VU 는 종결까지 폴링을 유지하므로, 최악의
# 경우 (VU 상한 ÷ 폴링 간격)만큼의 조회가 본 부하 위에 얹힌다. 이 값이 본 부하에 비해 크면
# 관측이 측정 대상을 느리게 만들고, 그 세금은 느린 구성에 더 무겁게 걸려 배수까지 왜곡한다.
if [[ "${LATENCY_SAMPLE_RATE_PER_SEC}" -gt 0 ]]; then
    _poll_interval_s=$(awk 'BEGIN{printf "%.3f", 0.5}')
    _worst_poll_rate=$(awk -v v="${LATENCY_SAMPLE_MAX_VUS}" -v i="${_poll_interval_s}" 'BEGIN{printf "%.0f", v/i}')
    print_info "  지연 표본: 도착률 ${LATENCY_SAMPLE_RATE_PER_SEC}/s · VU 상한 ${LATENCY_SAMPLE_MAX_VUS} · 타임아웃 $((LATENCY_POLL_TIMEOUT_MS/1000))s"
    print_warning "  ⚠️  표본 폴링이 최대 약 ${_worst_poll_rate}/s 의 추가 조회를 만든다(VU 가 전부 붙잡힐 때). 본 부하 대비 크면 LATENCY_SAMPLE_RATE_PER_SEC 를 낮추거나 0 으로 끄고 처리량만 재라"
fi

# PRE_LOAD_HOOK — 부하 직전, 표본화 시작 전에 실행할 명령. 인스턴스가 --scale 로 전부 뜬 뒤에만
# 할 수 있는 준비(예: scripts/bench-pin-cpus.sh 로 컨테이너를 물리 코어에 고정)를 끼우는 자리다.
# 실패하면 사이클을 진행하지 않는다 — 준비가 안 된 채로 잰 값은 조건이 다른 값이기 때문이다.
if [[ -n "${PRE_LOAD_HOOK:-}" ]]; then
    print_section "  PRE_LOAD_HOOK 실행 — ${PRE_LOAD_HOOK}"
    if ! bash -c "${PRE_LOAD_HOOK}"; then
        print_error "❌ (4) PRE_LOAD_HOOK 실패 — 부하를 시작하지 않는다"
        exit 1
    fi
fi

start_replica_lag_sampler
start_mysql_stat_sampler
start_redis_stat_sampler
start_container_stat_sampler
start_cpu_throttle_sampler
start_moneypath_lag_sampler
start_pg_queue_sampler
start_kafka_stat_sampler
start_backlog_stat_sampler

LOAD_START_EPOCH=$(date +%s)
set +e
(
    cd "${ROOT_DIR}"
    # shellcheck disable=SC2086 — K6_EXTRA_ARGS 는 "-e KEY=VALUE" 형태를 공백으로 여러 개
    # 이어붙이는 용도라 단어 분리가 의도된 동작이다.
    # exec 로 이 서브셸을 k6 프로세스로 완전히 치환한다 — 뒤에서 $! 로 잡는 PID 가 감싸는
    # 셸이 아니라 k6 자신이어야 CPU 표본화가 정확한 프로세스를 겨냥한다.
    exec k6 run \
        --tag "testid=${CASE_NAME}" \
        -e "BASE_URL=${BASE_URL}" \
        -e "CASE_NAME=${CASE_NAME}" \
        -e "ITEMS_PER_ORDER=${ITEMS_PER_ORDER}" \
        -e "PRODUCT_COUNT=${PRODUCT_COUNT}" \
        -e "PRODUCT_ID_BASE=${PRODUCT_ID_BASE}" \
        -e "SKIP_POLL=true" \
        -e "SAMPLE_RATE=${LATENCY_SAMPLE_RATE_PER_SEC}" \
        -e "SAMPLE_MAX_VUS=${LATENCY_SAMPLE_MAX_VUS}" \
        -e "SAMPLE_PRE_VUS=${LATENCY_SAMPLE_PRE_VUS}" \
        -e "POLL_TIMEOUT_MS=${LATENCY_POLL_TIMEOUT_MS}" \
        ${K6_EXTRA_ARGS} \
        "${SCRIPT_DIR}/k6/async-payment.js"
) &
K6_PID=$!
start_k6_cpu_sampler "${K6_PID}"
wait "${K6_PID}"
K6_EXIT=$?
stop_k6_cpu_sampler
set -e
LOAD_END_EPOCH=$(date +%s)
LOAD_DURATION_SEC=$((LOAD_END_EPOCH - LOAD_START_EPOCH))
stop_backlog_stat_sampler

if [[ "${K6_EXIT}" -ne 0 && "${K6_EXIT}" -ne 99 ]]; then
    print_error "❌ (4) k6 실행 오류 (exit ${K6_EXIT})"
    stop_replica_lag_sampler
    stop_mysql_stat_sampler
    stop_redis_stat_sampler
    stop_container_stat_sampler
    stop_cpu_throttle_sampler
    stop_moneypath_lag_sampler
    stop_pg_queue_sampler
    stop_kafka_stat_sampler
    exit 1
fi
if [[ "${K6_EXIT}" -eq 99 ]]; then
    print_warning "⚠ k6 threshold 위반(exit 99) — 결과는 생성됨, 계속 진행"
fi

K6_RESULT_JSON="${RESULTS_DIR}/${CASE_NAME}.json"
if [[ ! -f "${K6_RESULT_JSON}" ]]; then
    print_error "❌ (4) 결과 파일 없음: ${K6_RESULT_JSON}"
    stop_replica_lag_sampler
    stop_mysql_stat_sampler
    stop_redis_stat_sampler
    stop_container_stat_sampler
    stop_cpu_throttle_sampler
    stop_moneypath_lag_sampler
    stop_pg_queue_sampler
    stop_kafka_stat_sampler
    exit 1
fi
print_info "✅ (4) 부하 완료 (${LOAD_DURATION_SEC}초) — ${K6_RESULT_JSON}"
echo ""

# ---------------------------------------------------------------------------
# (5) 종결 대기 + 정합 검증 — verify-settlement.sh 종료 코드로 판정을 받는다.
#     INCONCLUSIVE(2) 는 유한 재시도, MISMATCH(3)/그 외는 즉시 중단.
# ---------------------------------------------------------------------------

print_section "▶ (5) 종결 대기 + 정합 검증 (scripts/k6/verify-settlement.sh)"

call_verify() {
    local settle_wait="$1"
    SETTLE_WAIT_SECONDS="${settle_wait}" \
    RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT}" \
    RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS}" \
    CASE_NAME="${CASE_NAME}" \
    RESULTS_DIR="${RESULTS_DIR}" \
    PRODUCT_COUNT="${PRODUCT_COUNT}" \
    PRODUCT_ID_BASE="${PRODUCT_ID_BASE}" \
    REDIS_STOCK_CONTAINER="${STOCK_CONTAINER_NAME}" \
    STOCK_COMMIT_PRODUCERS="${INSTANCES}" \
    bash "${ROOT_DIR}/scripts/k6/verify-settlement.sh"
    return $?
}

# call_verify 의 종료 코드(2/3/그 외)는 이 러너의 정상 제어 흐름이라, 전역 set -e(라인
# ~456) 아래서 그대로 실행하면 errexit 가 즉시 스크립트를 죽여 재시도 루프·복제 지연
# 표본화 정지·결과 기록·재구성이 전부 스킵된다(실측 중 발견 — INCONCLUSIVE 첫 판정에서
# 스크립트가 그 자리에서 죽는 것을 확인). `cmd || VERIFY_EXIT=$?` 형태로 좌변에 둬야
# errexit 예외 대상이 된다.
VERIFY_EXIT=0
RETRY_COUNT=0
call_verify "" || VERIFY_EXIT=$?

while [[ "${VERIFY_EXIT}" -eq 2 && "${RETRY_COUNT}" -lt "${INCONCLUSIVE_MAX_RETRIES}" ]]; do
    RETRY_COUNT=$((RETRY_COUNT + 1))
    print_warning "⚠️  판단 보류(INCONCLUSIVE) — ${INCONCLUSIVE_RETRY_WAIT_SECONDS}초 대기 후 재검증 (${RETRY_COUNT}/${INCONCLUSIVE_MAX_RETRIES})"
    sleep "${INCONCLUSIVE_RETRY_WAIT_SECONDS}"
    VERIFY_EXIT=0
    call_verify 0 || VERIFY_EXIT=$?
done

stop_replica_lag_sampler
stop_mysql_stat_sampler
stop_redis_stat_sampler
stop_container_stat_sampler
stop_cpu_throttle_sampler
stop_moneypath_lag_sampler
stop_pg_queue_sampler
stop_kafka_stat_sampler
SETTLE_END_EPOCH=$(date +%s)

VERDICT_JSON="${RESULTS_DIR}/${CASE_NAME}-verdict.json"
VERDICT="UNKNOWN"
VERDICT_REASON=""
if [[ -f "${VERDICT_JSON}" ]]; then
    VERDICT=$(jq -r '.verdict // "UNKNOWN"' "${VERDICT_JSON}")
    VERDICT_REASON=$(jq -r '.reason // ""' "${VERDICT_JSON}")
fi

CYCLE_EXIT=0
OUTCOME="FAILED"
RESET_STATUS="SKIPPED"

case "${VERIFY_EXIT}" in
    0)
        print_info "✅ (5) 정합 검증 통과(PASS) — ${VERDICT_REASON}"
        OUTCOME="SUCCESS"
        CYCLE_EXIT=0
        ;;
    2)
        print_error "❌ (5) 판단 보류 소진(INCONCLUSIVE, 재시도 ${RETRY_COUNT}/${INCONCLUSIVE_MAX_RETRIES}) — 캐시를 비우지 않고 사이클 실패"
        CYCLE_EXIT=2
        ;;
    3)
        print_error "❌ (5) 정합 불일치(MISMATCH) — 즉시 중단, 캐시를 비우지 않는다"
        CYCLE_EXIT=3
        ;;
    1)
        if [[ "${VERDICT}" == "NO_TRAFFIC" ]]; then
            print_error "❌ (5) 부하가 시스템에 닿지 않아 정합 판정이 성립하지 않는다 — ${VERDICT_REASON}"
            print_error "   이 사이클의 처리율·지연·정합 결과는 전부 무효다. 통과로 읽지 않는다"
        else
            print_error "❌ (5) 정합 검증이 접속·전제 실패(exit 1)로 끝남 — 통과로 읽지 않는다"
        fi
        CYCLE_EXIT=4
        ;;
    *)
        print_error "❌ (5) 정합 검증이 알 수 없는 코드(exit ${VERIFY_EXIT})로 끝남 — 통과로 읽지 않는다"
        CYCLE_EXIT=4
        ;;
esac
echo ""

# ---------------------------------------------------------------------------
# (6) 재구성 — 통과했을 때만. 실패로 멈춘 사이클의 잔류는 사람이 scripts/bench-cycle-reset.sh
#     로 직접 정리한다.
# ---------------------------------------------------------------------------

if [[ "${OUTCOME}" == "SUCCESS" ]]; then
    print_section "▶ (6) 재구성 (scripts/bench-cycle-reset.sh)"
    if RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT}" RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS}" \
        PRODUCT_COUNT="${PRODUCT_COUNT}" PRODUCT_ID_BASE="${PRODUCT_ID_BASE}" BENCH_STOCK="${BENCH_STOCK}" \
        REDIS_STOCK_CONTAINER="${STOCK_CONTAINER_NAME}" \
        STOCK_COMMIT_PRODUCERS="${INSTANCES}" bash "${ROOT_DIR}/scripts/bench-cycle-reset.sh"; then
        RESET_STATUS="DONE"
        print_info "✅ (6) 재구성 완료"
    else
        RESET_STATUS="FAILED"
        print_error "❌ (6) 재구성 실패 — 잔류 상태를 사람이 직접 확인해야 한다"
        CYCLE_EXIT=1
    fi
else
    print_section "▶ (6) 재구성 — 스킵 (정합 판정이 통과가 아니라 캐시를 비우지 않는다)"
    print_warning "  잔류를 치우려면 사람이 scripts/bench-cycle-reset.sh 를 직접 돌린다"
fi
echo ""

# ---------------------------------------------------------------------------
# (7) 결과 기록 — results/<CASE_NAME>-cycle.json
# ---------------------------------------------------------------------------

print_section "▶ (7) 결과 기록 — ${CYCLE_JSON}"

K6_RESULT_JSON="${RESULTS_DIR}/${CASE_NAME}.json"
CONFIRM_COUNT=0
DB_DONE_COUNT=0
DROPPED_ITERATIONS=0
ITERATIONS_COMPLETED=0
SAMPLE_RESOLVED_COUNT=0
if [[ -f "${K6_RESULT_JSON}" ]]; then
    CONFIRM_COUNT=$(jq -r '.metrics.confirm_requests_count // 0' "${K6_RESULT_JSON}")
    DROPPED_ITERATIONS=$(jq -r '.metrics.dropped_iterations_count // 0' "${K6_RESULT_JSON}")
    ITERATIONS_COMPLETED=$(jq -r '.metrics.iterations_count // 0' "${K6_RESULT_JSON}")
    SAMPLE_RESOLVED_COUNT=$(jq -r '.metrics.e2e_resolved_count // 0' "${K6_RESULT_JSON}")
fi
if [[ -f "${VERDICT_JSON}" ]]; then
    DB_DONE_COUNT=$(jq -r '.counts.db_done // 0' "${VERDICT_JSON}")
fi

TOTAL_WALL_SEC=$((SETTLE_END_EPOCH - LOAD_START_EPOCH))
CONFIRM_PER_SEC="0"
DB_DONE_PER_LOAD_SEC="0"
if [[ "${LOAD_DURATION_SEC}" -gt 0 ]]; then
    CONFIRM_PER_SEC=$(awk -v c="${CONFIRM_COUNT}" -v d="${LOAD_DURATION_SEC}" 'BEGIN { printf "%.3f", c / d }')
fi
# 처리율 = DB 종결 건수 ÷ 부하 구간(k6 가 실제로 관측했는지와 무관한 값). 정산 대기(settle
# wait)는 부하가 아니라 뒤늦은 종결을 기다리는 시간이라 분모에 넣지 않는다 — 넣으면 사이클마다
# 다른 회수 기준(RECONCILER_TIMEOUT)이 그대로 처리율에 섞여 들어간다.
# OUTCOME 과 무관하게 계산한다 — DB_DONE_COUNT 는 VERDICT_JSON 이 있으면(정합 검증이 한 번이라도
# 돌았으면) INCONCLUSIVE/MISMATCH 여도 그 시점까지의 실제 종결 건수를 담고 있다(Task 20 실측:
# db_done_count=8863 인데 OUTCOME=FAILED 라 이 값이 0 으로 찍혀, 처리율이 도착률을 못 따라가기
# 시작하는 지점을 표로 못 남길 뻔했다). 정합 판정 자체는 verdict/outcome 필드로 별도로 남으므로
# 처리율 수치를 0 으로 지우지 않아도 판정과 섞이지 않는다.
if [[ "${LOAD_DURATION_SEC}" -gt 0 ]]; then
    DB_DONE_PER_LOAD_SEC=$(awk -v c="${DB_DONE_COUNT}" -v d="${LOAD_DURATION_SEC}" 'BEGIN { printf "%.3f", c / d }')
fi

DROPPED_RATE="0"
TOTAL_PLANNED_ITERATIONS=$((DROPPED_ITERATIONS + ITERATIONS_COMPLETED))
if [[ "${TOTAL_PLANNED_ITERATIONS}" -gt 0 ]]; then
    DROPPED_RATE=$(awk -v d="${DROPPED_ITERATIONS}" -v t="${TOTAL_PLANNED_ITERATIONS}" 'BEGIN { printf "%.4f", d / t }')
fi

POLL_LATENCY_JSON=$(jq -c '.metrics.http_req_duration_poll // null' "${K6_RESULT_JSON}" 2>/dev/null || echo null)
E2E_LATENCY_JSON=$(jq -c '.metrics.e2e_completion_ms // null' "${K6_RESULT_JSON}" 2>/dev/null || echo null)
REPLICA_LAG_JSON=$(replica_lag_stats_json)
K6_CPU_JSON=$(k6_cpu_stats_json)
MYSQL_STAT_JSON=$(mysql_stat_stats_json)
REDIS_STAT_JSON=$(redis_stat_stats_json)
CONTAINER_STAT_JSON=$(container_stat_stats_json)
CPU_THROTTLE_JSON=$(cpu_throttle_stats_json)
MONEYPATH_LAG_JSON=$(moneypath_lag_stats_json)
PG_QUEUE_JSON=$(pg_queue_stats_json)
KAFKA_STAT_JSON=$(kafka_stat_stats_json)
BACKLOG_TREND_JSON=$(backlog_trend_json)

jq -n \
    --arg case_name "${CASE_NAME}" \
    --arg created_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson instances "${INSTANCES}" \
    --argjson pg_instances "${PG_INSTANCES}" \
    --argjson stock_masters "${STOCK_MASTERS}" \
    --argjson dedupe_masters "${DEDUPE_MASTERS}" \
    --arg polling_route "${POLLING_ROUTE}" \
    --argjson items_per_order "${ITEMS_PER_ORDER}" \
    --arg vendor_latency "${VENDOR_LATENCY}" \
    --argjson fake_latency_min "${FAKE_LATENCY_MIN}" \
    --argjson fake_latency_max "${FAKE_LATENCY_MAX}" \
    --argjson product_count "${PRODUCT_COUNT}" \
    --argjson reconciler_timeout_s "${RECONCILER_TIMEOUT}" \
    --argjson reconciler_scan_ms "${RECONCILER_SCAN_MS}" \
    --argjson hikari_max_pool "${HIKARI_MAX_POOL}" \
    --argjson confirmed_consumer_concurrency "${CONFIRMED_CONSUMER_CONCURRENCY}" \
    --argjson pg_inbox_workers "${PG_INBOX_WORKERS}" \
    --argjson pg_outbox_workers "${PG_OUTBOX_WORKERS}" \
    --argjson confirm_count "${CONFIRM_COUNT}" \
    --argjson db_done_count "${DB_DONE_COUNT}" \
    --argjson load_duration_sec "${LOAD_DURATION_SEC}" \
    --argjson total_wall_sec "${TOTAL_WALL_SEC}" \
    --arg confirm_per_sec "${CONFIRM_PER_SEC}" \
    --arg db_done_per_load_sec "${DB_DONE_PER_LOAD_SEC}" \
    --argjson dropped_iterations "${DROPPED_ITERATIONS}" \
    --argjson iterations_completed "${ITERATIONS_COMPLETED}" \
    --arg dropped_rate "${DROPPED_RATE}" \
    --argjson sample_resolved_count "${SAMPLE_RESOLVED_COUNT}" \
    --argjson poll_latency_ms "${POLL_LATENCY_JSON}" \
    --argjson e2e_latency_ms "${E2E_LATENCY_JSON}" \
    --argjson replica_lag_seconds "${REPLICA_LAG_JSON}" \
    --argjson k6_cpu_percent "${K6_CPU_JSON}" \
    --argjson mysql_stats "${MYSQL_STAT_JSON}" \
    --argjson redis_stats "${REDIS_STAT_JSON}" \
    --argjson container_stats "${CONTAINER_STAT_JSON}" \
    --argjson kafka_stats "${KAFKA_STAT_JSON}" \
    --argjson cpu_throttle "${CPU_THROTTLE_JSON}" \
    --argjson moneypath_lag "${MONEYPATH_LAG_JSON}" \
    --argjson pg_queue "${PG_QUEUE_JSON}" \
    --argjson backlog_trend "${BACKLOG_TREND_JSON}" \
    --argjson load_start_epoch "${LOAD_START_EPOCH}" \
    --argjson load_end_epoch "${LOAD_END_EPOCH}" \
    --arg verdict "${VERDICT}" \
    --argjson verify_exit_code "${VERIFY_EXIT}" \
    --arg verdict_reason "${VERDICT_REASON}" \
    --argjson inconclusive_retries "${RETRY_COUNT}" \
    --arg outcome "${OUTCOME}" \
    --arg reset_status "${RESET_STATUS}" \
    --argjson pre_cycle_unsettled "${PRE_CYCLE_UNSETTLED}" \
    --argjson pre_cycle_noise "${PRE_CYCLE_NOISE}" \
    '{
        case_name: $case_name,
        created_at: $created_at,
        pre_cycle_state: {
            unsettled: $pre_cycle_unsettled,
            noise: $pre_cycle_noise,
            clean: (($pre_cycle_unsettled == 0) and ($pre_cycle_noise == 0))
        },
        conditions: {
            instances: $instances,
            pg_instances: $pg_instances,
            stock_masters: $stock_masters,
            dedupe_masters: $dedupe_masters,
            polling_route: $polling_route,
            items_per_order: $items_per_order,
            vendor_latency: $vendor_latency,
            fake_latency_min_ms: $fake_latency_min,
            fake_latency_max_ms: $fake_latency_max,
            product_count: $product_count,
            reconciler_timeout_s: $reconciler_timeout_s,
            reconciler_scan_ms: $reconciler_scan_ms,
            hikari_max_pool: $hikari_max_pool,
            confirmed_consumer_concurrency: $confirmed_consumer_concurrency,
            pg_inbox_workers: $pg_inbox_workers,
            pg_outbox_workers: $pg_outbox_workers
        },
        throughput: {
            confirm_count: $confirm_count,
            db_done_count: $db_done_count,
            load_duration_sec: $load_duration_sec,
            total_wall_sec: $total_wall_sec,
            confirm_per_sec: ($confirm_per_sec | tonumber),
            db_done_per_load_sec: ($db_done_per_load_sec | tonumber)
        },
        load_integrity: {
            dropped_iterations: $dropped_iterations,
            iterations_completed: $iterations_completed,
            dropped_rate: ($dropped_rate | tonumber)
        },
        latency_sample: {
            resolved_count: $sample_resolved_count,
            poll_response_ms: $poll_latency_ms,
            e2e_completion_ms: $e2e_latency_ms
        },
        replica_lag_seconds: $replica_lag_seconds,
        k6_cpu_percent: $k6_cpu_percent,
        resource_usage: {
            mysql: $mysql_stats,
            redis: $redis_stats,
            containers: $container_stats,
            kafka: $kafka_stats,
            cpu_throttle: $cpu_throttle,
            moneypath_lag: $moneypath_lag,
            pg_queue: $pg_queue
        },
        backlog_trend: ($backlog_trend + {load_start_epoch: $load_start_epoch, load_end_epoch: $load_end_epoch}),
        # 부하 구간 동안 실제로 밀어낸 속도 — 능력 판정의 기준값.
        # db_done_per_load_sec 는 검증 시점의 누적 종결 수를 쓰기 때문에, 부하가 끝난 뒤 밀린
        # 물량이 전부 빠지면 도착률로 수렴해 능력을 못 잰다(실측: 전부 종결된 사이클 셋이
        # 구성이 다른데도 똑같이 152/s 로 찍혔다). 부하 끝 시점에 아직 남아 있던 적체를 빼면
        # 그 구간에서 실제로 종결시킨 양이 남는다.
        settled_during_load: (
            ($confirm_count - ($backlog_trend.last // 0)) as $done
            | {
                count: $done,
                per_sec: (if ($load_end_epoch - $load_start_epoch) > 0
                          then ($done / ($load_end_epoch - $load_start_epoch)) else null end)
              }
        ),
        settlement: {
            verdict: $verdict,
            verify_exit_code: $verify_exit_code,
            reason: $verdict_reason,
            inconclusive_retries: $inconclusive_retries
        },
        outcome: $outcome,
        reset_status: $reset_status
    }' > "${CYCLE_JSON}"

print_info "✅ (7) 결과 기록 완료 — ${CYCLE_JSON}"

if [[ -n "${REPLICA_LAG_LOG}" ]]; then
    rm -f "${REPLICA_LAG_LOG}"
fi
if [[ -n "${K6_CPU_LOG}" ]]; then
    rm -f "${K6_CPU_LOG}"
fi
if [[ -n "${MYSQL_STAT_LOG}" ]]; then
    rm -f "${MYSQL_STAT_LOG}"
fi
if [[ -n "${REDIS_STAT_LOG}" ]]; then
    rm -f "${REDIS_STAT_LOG}"
fi
if [[ -n "${CONTAINER_STAT_LOG}" ]]; then
    rm -f "${CONTAINER_STAT_LOG}"
fi
if [[ -n "${KAFKA_STAT_LOG}" ]]; then
    rm -f "${KAFKA_STAT_LOG}"
fi
if [[ -n "${BACKLOG_STAT_LOG}" ]]; then
    rm -f "${BACKLOG_STAT_LOG}"
fi

echo ""
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [[ "${CYCLE_EXIT}" -eq 0 ]]; then
    print_info "✅ bench-scaleout-cycle 완료 — ${CASE_NAME} (outcome=${OUTCOME})"
else
    print_error "❌ bench-scaleout-cycle 실패 — ${CASE_NAME} (exit ${CYCLE_EXIT}, outcome=${OUTCOME}, reset=${RESET_STATUS})"
fi
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

exit "${CYCLE_EXIT}"
