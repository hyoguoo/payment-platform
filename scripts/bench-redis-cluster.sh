#!/usr/bin/env bash
# bench-redis-cluster.sh — 재고 캐시 / 멱등 저장소 Redis 클러스터를 지정 대수로 구성.
#
# docker/docker-compose.scaleout.yml 의 redis-stock-cluster / redis-idempotency-cluster
# 서비스를 `--scale <service>=N` 으로 띄운 뒤, redis-cli --cluster create 대신 수동으로
# CLUSTER MEET + CLUSTER ADDSLOTSRANGE 를 밟아 슬롯 16384 개를 N 등분해 배정한다.
# (redis-cli --cluster create 는 마스터 3대 미만이면 자체 가드로 거부한다 — 이 축은
# 마스터 1대짜리 클러스터도 성립해야 해서 그 헬퍼를 쓰지 않는다.)
#
# 컨테이너는 named volume 없이(에페메럴) 뜨므로, 다시 돌리면 기존 컨테이너를 지우고
# 빈 nodes.conf 로 새로 시작해 클러스터를 처음부터 다시 만든다 — 멱등.
#
# 사용법:
#   ./scripts/bench-redis-cluster.sh --store stock --masters 4
#   ./scripts/bench-redis-cluster.sh --store dedupe --masters 3
#
# 선행 조건:
#   docker compose -f docker/docker-compose.infra.yml -f docker/docker-compose.apps.yml \
#     -f docker/docker-compose.scaleout.yml up -d 로 payment-infra-network 가 이미 존재
#
# 환경 변수:
#   COMPOSE_TIMEOUT_SECONDS — 클러스터 수렴 대기 상한(기본 60)
#
# 종료 코드:
#   0 — 대수 N 클러스터 구성 완료, state:ok + 슬롯 16384 전부 배정 확인
#   1 — 인자 오류, docker 미기동, 컨테이너 기동 실패, 클러스터 수렴 시간 초과

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=common.sh
source "${ROOT_DIR}/scripts/common.sh"

STORE=""
MASTERS=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --store) STORE="$2"; shift 2 ;;
        --masters) MASTERS="$2"; shift 2 ;;
        *)
            print_error "❌ 알 수 없는 인자: $1"
            exit 1
            ;;
    esac
done

if [[ "${STORE}" != "stock" && "${STORE}" != "dedupe" ]]; then
    print_error "❌ --store 는 stock 또는 dedupe 여야 한다 (입력: '${STORE}')"
    exit 1
fi
if ! [[ "${MASTERS}" =~ ^[0-9]+$ ]] || [[ "${MASTERS}" -lt 1 ]]; then
    print_error "❌ --masters 는 1 이상의 정수여야 한다 (입력: '${MASTERS}')"
    exit 1
fi

if [[ "${STORE}" == "stock" ]]; then
    SERVICE="redis-stock-cluster"
else
    SERVICE="redis-idempotency-cluster"
fi

CONVERGE_TIMEOUT="${COMPOSE_TIMEOUT_SECONDS:-60}"

COMPOSE_ARGS=(
    -f "${ROOT_DIR}/docker/docker-compose.infra.yml"
    -f "${ROOT_DIR}/docker/docker-compose.apps.yml"
    -f "${ROOT_DIR}/docker/docker-compose.scaleout.yml"
)

dc() {
    docker compose "${COMPOSE_ARGS[@]}" "$@"
}

check_docker

print_section "▶ bench-redis-cluster 시작 — store=${STORE} (${SERVICE}), masters=${MASTERS}"

# 1. 기존 컨테이너 제거 (에페메럴이라 nodes.conf 도 함께 사라진다 — 멱등의 핵심)
print_info "  기존 ${SERVICE} 컨테이너 제거"
dc rm -f -s -v "${SERVICE}" >/dev/null 2>&1 || true

# 2. 지정 대수로 새로 기동
if ! dc up -d --scale "${SERVICE}=${MASTERS}" "${SERVICE}" >/dev/null; then
    print_error "❌ ${SERVICE} 기동 실패"
    exit 1
fi

CONTAINER_IDS=($(dc ps -q "${SERVICE}"))
if [[ "${#CONTAINER_IDS[@]}" -ne "${MASTERS}" ]]; then
    print_error "❌ 기동된 컨테이너 수(${#CONTAINER_IDS[@]})가 요청한 대수(${MASTERS})와 다르다"
    exit 1
fi

# 3. 각 컨테이너가 ping 에 응답할 때까지 대기 + 네트워크 IP 확보
NODE_IDS=()
NODE_IPS=()
for id in "${CONTAINER_IDS[@]}"; do
    attempt=0
    until docker exec "${id}" redis-cli ping >/dev/null 2>&1; do
        attempt=$((attempt + 1))
        if [[ "${attempt}" -ge 30 ]]; then
            print_error "❌ 컨테이너 ${id} 가 시간 내 ping 에 응답하지 않음"
            exit 1
        fi
        sleep 1
    done
    ip=$(docker inspect --format '{{(index .NetworkSettings.Networks "payment-infra-network").IPAddress}}' "${id}")
    if [[ -z "${ip}" ]]; then
        print_error "❌ 컨테이너 ${id} 의 payment-infra-network IP 를 확인 못함"
        exit 1
    fi
    NODE_IDS+=("${id}")
    NODE_IPS+=("${ip}")
done
print_info "  ${#NODE_IDS[@]}개 노드 ping 확인 완료 — ${NODE_IPS[*]}"

# 4. 노드별 config epoch 를 MEET 이전에 미리 확정 (redis-cli --cluster create 와 같은 순서)
for i in "${!NODE_IDS[@]}"; do
    docker exec "${NODE_IDS[$i]}" redis-cli cluster set-config-epoch "$((i + 1))" >/dev/null
done

# 5. 노드1 을 기준으로 나머지 전부와 MEET (전이적으로 풀 메시가 이뤄진다)
FIRST_ID="${NODE_IDS[0]}"
for i in "${!NODE_IPS[@]}"; do
    if [[ "${i}" -eq 0 ]]; then
        continue
    fi
    docker exec "${FIRST_ID}" redis-cli cluster meet "${NODE_IPS[$i]}" 6379 >/dev/null
done

# 6. 전체가 서로를 인지할 때까지 대기 (CLUSTER NODES 줄 수 == 대수)
attempt=0
while true; do
    seen=$(docker exec "${FIRST_ID}" redis-cli cluster nodes | wc -l | tr -d ' ')
    if [[ "${seen}" -eq "${MASTERS}" ]]; then
        break
    fi
    attempt=$((attempt + 1))
    if [[ "${attempt}" -ge "${CONVERGE_TIMEOUT}" ]]; then
        print_error "❌ 노드 핸드셰이크 시간 초과 — 인지된 노드 ${seen}/${MASTERS}"
        exit 1
    fi
    sleep 1
done
print_info "  ${MASTERS}개 노드 상호 인지 완료 (풀 메시)"

# 7. 슬롯 16384 개를 N 등분해 배정 (나머지는 마지막 노드가 흡수)
SLOTS_PER_NODE=$((16384 / MASTERS))
for i in "${!NODE_IDS[@]}"; do
    start=$((i * SLOTS_PER_NODE))
    if [[ "${i}" -eq $((MASTERS - 1)) ]]; then
        end=16383
    else
        end=$((start + SLOTS_PER_NODE - 1))
    fi
    if ! docker exec "${NODE_IDS[$i]}" redis-cli cluster addslotsrange "${start}" "${end}" >/dev/null 2>&1; then
        print_error "❌ 노드 ${i} 슬롯 배정 실패 (${start}-${end})"
        exit 1
    fi
done
print_info "  슬롯 16384개 배정 완료 (${MASTERS} 등분)"

# 8. state:ok + 슬롯 16384 전부 배정 수렴 대기
attempt=0
while true; do
    info=$(docker exec "${FIRST_ID}" redis-cli cluster info)
    state=$(echo "${info}" | grep -m1 "cluster_state:" | tr -d '\r' | cut -d: -f2)
    assigned=$(echo "${info}" | grep -m1 "cluster_slots_assigned:" | tr -d '\r' | cut -d: -f2)
    if [[ "${state}" == "ok" && "${assigned}" == "16384" ]]; then
        break
    fi
    attempt=$((attempt + 1))
    if [[ "${attempt}" -ge "${CONVERGE_TIMEOUT}" ]]; then
        print_error "❌ 클러스터 수렴 시간 초과 — state=${state:-<없음>}, assigned=${assigned:-0}"
        exit 1
    fi
    sleep 1
done

# 9. 슬롯 커버리지 요구가 꺼져 있는지 CONFIG GET 으로 확인 (CLUSTER COUNT-FAILURE-REPORTS 기준 아님)
coverage=$(docker exec "${FIRST_ID}" redis-cli config get cluster-require-full-coverage | tail -n1 | tr -d '\r')
if [[ "${coverage}" != "no" ]]; then
    print_error "❌ cluster-require-full-coverage 가 no 가 아니다 (현재: ${coverage})"
    exit 1
fi

print_info "✅ bench-redis-cluster 완료 — ${SERVICE} ${MASTERS}대, state=ok, 슬롯=16384, cluster-require-full-coverage=no"

# 10. 노드별 키 개수 참고 출력 (구성 직후에는 0개가 정상)
for i in "${!NODE_IDS[@]}"; do
    count=$(docker exec "${NODE_IDS[$i]}" redis-cli dbsize | tr -d '\r')
    echo "    노드 ${i} (${NODE_IPS[$i]}) — 키 ${count}개"
done
