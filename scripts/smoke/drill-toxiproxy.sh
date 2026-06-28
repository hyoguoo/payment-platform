#!/usr/bin/env bash
# drill-toxiproxy.sh — Toxiproxy 장애 주입/해제 스크립트.
#
# 목적: Toxiproxy admin API 를 통해 Kafka PROXY 리스너에 latency toxic 을 주입/해제한다.
#       코디네이터 정체 알람(consumer lag 누적) 실증에 사용.
#
# ── 선행 조건 ──────────────────────────────────────────────────────────────
#   docker compose \
#     -f docker/docker-compose.infra.yml \
#     -f docker/docker-compose.apps.yml \
#     -f docker/docker-compose.drill.yml \
#     up -d
#   scripts/smoke/create-topics.sh (Kafka 토픽 생성)
#   (observability 스택 포함 시: -f docker/docker-compose.observability.yml)
#
# ── 사용법 ─────────────────────────────────────────────────────────────────
#   ./scripts/smoke/drill-toxiproxy.sh inject    # latency toxic 주입
#   ./scripts/smoke/drill-toxiproxy.sh remove    # latency toxic 해제
#   ./scripts/smoke/drill-toxiproxy.sh status    # 현재 상태 확인
#   ./scripts/smoke/drill-toxiproxy.sh verify    # 프록시 통과 사전 확인
#   ./scripts/smoke/drill-toxiproxy.sh reset     # 전체 toxic 제거
#
# ── 일반 드릴 흐름 ─────────────────────────────────────────────────────────
#   1. 프록시 사전 확인:  ./scripts/smoke/drill-toxiproxy.sh verify
#   2. toxic 주입:         ./scripts/smoke/drill-toxiproxy.sh inject
#   3. 트래픽 발생:        bash scripts/smoke/observability-load.sh  (별도 터미널)
#   4. 알람 폴링:          curl -s http://localhost:9090/api/v1/alerts | python3 -m json.tool
#   5. toxic 해제:         ./scripts/smoke/drill-toxiproxy.sh remove
#   6. lag 해소 확인:      상태 재조회 후 KafkaCoordinatorLagHigh resolved 확인
#
# ── 종료 코드 ──────────────────────────────────────────────────────────────
#   0 — 성공
#   1 — 실패 (Toxiproxy 미기동, 명령 오류 등)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../common.sh
source "${ROOT_DIR}/scripts/common.sh"

# ── 설정 (환경변수 오버라이드 가능) ────────────────────────────────────────
TOXI_HOST="${TOXI_HOST:-localhost}"
TOXI_ADMIN_PORT="${TOXI_ADMIN_PORT:-8474}"
TOXI_ADMIN_URL="http://${TOXI_HOST}:${TOXI_ADMIN_PORT}"

PROXY_NAME="${PROXY_NAME:-kafka-proxy}"
TOXIC_NAME="${TOXIC_NAME:-latency_toxic}"

# latency toxic 기본값 — consumer lag 누적 실증용
# 500ms 주입 시 payment.events.confirmed 소비 대기가 길어져 lag 누적.
# 임계(KafkaCoordinatorLagHigh for:1m) 발화까지 ~60~90초 소요.
LATENCY_MS="${LATENCY_MS:-500}"
LATENCY_JITTER_MS="${LATENCY_JITTER_MS:-50}"

# ── 헬퍼 함수 ──────────────────────────────────────────────────────────────

# Toxiproxy admin API 응답 여부 확인
check_toxiproxy() {
    print_section "▶ Toxiproxy 연결 확인 (${TOXI_ADMIN_URL})"
    if ! curl -sf -m 5 "${TOXI_ADMIN_URL}/proxies" > /dev/null; then
        print_error "Toxiproxy admin API 응답 없음"
        print_error "  드릴 프로파일 기동 확인:"
        print_error "    docker compose \\"
        print_error "      -f docker/docker-compose.infra.yml \\"
        print_error "      -f docker/docker-compose.apps.yml \\"
        print_error "      -f docker/docker-compose.drill.yml up -d"
        exit 1
    fi
    print_info "[OK] Toxiproxy admin API 응답"
}

# 프록시 등록 여부 확인 (toxiproxy.json 마운트 시 기동과 함께 자동 등록됨)
verify_proxy_registered() {
    print_section "▶ 프록시 등록 확인 (${PROXY_NAME})"
    local proxy_resp
    proxy_resp=$(curl -sf -m 5 "${TOXI_ADMIN_URL}/proxies/${PROXY_NAME}" 2>/dev/null || echo "")
    if [ -z "${proxy_resp}" ]; then
        print_error "프록시 '${PROXY_NAME}' 미등록"
        print_warning "  toxiproxy.json 마운트 확인 또는 수동 등록:"
        print_warning "    curl -s -X POST ${TOXI_ADMIN_URL}/proxies \\"
        print_warning "      -H 'Content-Type: application/json' \\"
        print_warning "      -d '{\"name\":\"${PROXY_NAME}\",\"listen\":\"0.0.0.0:9094\",\"upstream\":\"kafka:9094\",\"enabled\":true}'"
        exit 1
    fi
    print_info "[OK] 프록시 '${PROXY_NAME}' 등록됨"
    echo "${proxy_resp}" | python3 -m json.tool 2>/dev/null | grep -E '"name"|"listen"|"upstream"|"enabled"' || true
}

# latency toxic 주입 (멱등 — 동명 toxic 존재 시 제거 후 재등록)
inject_latency() {
    print_section "▶ latency toxic 주입 (latency=${LATENCY_MS}ms, jitter=${LATENCY_JITTER_MS}ms)"

    # 기존 동명 toxic 제거 (멱등 처리)
    curl -sf -m 5 -X DELETE \
        "${TOXI_ADMIN_URL}/proxies/${PROXY_NAME}/toxics/${TOXIC_NAME}" > /dev/null 2>&1 || true

    local resp
    resp=$(curl -sf -m 5 -X POST \
        "${TOXI_ADMIN_URL}/proxies/${PROXY_NAME}/toxics" \
        -H "Content-Type: application/json" \
        -d "{
              \"name\": \"${TOXIC_NAME}\",
              \"type\": \"latency\",
              \"stream\": \"upstream\",
              \"toxicity\": 1.0,
              \"attributes\": {
                \"latency\": ${LATENCY_MS},
                \"jitter\": ${LATENCY_JITTER_MS}
              }
            }" 2>/dev/null || echo "")

    if [ -z "${resp}" ]; then
        print_error "latency toxic 주입 실패 — Toxiproxy API 응답 없음"
        exit 1
    fi

    print_info "[INJECTED] latency toxic 주입 완료"
    echo "${resp}" | python3 -m json.tool 2>/dev/null || true
    echo ""
    print_warning "  → payment-service consumer(payment.events.confirmed) lag 누적 시작"
    print_warning "  → KafkaCoordinatorLagHigh 알람 발화 예상(약 60~90초)"
    print_warning "  → 알람 폴링:"
    print_warning "       curl -s http://localhost:9090/api/v1/alerts | python3 -m json.tool"
}

# latency toxic 해제
remove_latency() {
    print_section "▶ latency toxic 해제 (${TOXIC_NAME})"
    local http_code
    http_code=$(curl -sf -m 5 -o /dev/null -w "%{http_code}" \
        -X DELETE "${TOXI_ADMIN_URL}/proxies/${PROXY_NAME}/toxics/${TOXIC_NAME}" 2>/dev/null || echo "000")

    if [ "${http_code}" = "204" ] || [ "${http_code}" = "200" ]; then
        print_info "[REMOVED] latency toxic 해제 완료"
        print_warning "  → consumer lag 해소 후 KafkaCoordinatorLagHigh resolved 확인 필요"
    else
        print_warning "[WARN] toxic 해제 응답: HTTP ${http_code} (이미 없거나 실패)"
    fi
}

# 현재 Toxiproxy 상태 출력
show_status() {
    print_section "▶ 현재 Toxiproxy 상태"

    print_section "  등록 프록시 목록:"
    curl -sf -m 5 "${TOXI_ADMIN_URL}/proxies" 2>/dev/null \
        | python3 -m json.tool 2>/dev/null || echo "  (응답 없음)"

    echo ""
    print_section "  활성 toxic (${PROXY_NAME}):"
    curl -sf -m 5 "${TOXI_ADMIN_URL}/proxies/${PROXY_NAME}/toxics" 2>/dev/null \
        | python3 -m json.tool 2>/dev/null || echo "  (toxic 없음 또는 프록시 미등록)"
}

# Toxiproxy 전체 reset (모든 toxic 제거, 프록시 정의는 유지)
reset_all() {
    print_section "▶ Toxiproxy 전체 reset (모든 toxic 제거, 프록시 정의 유지)"
    local http_code
    http_code=$(curl -sf -m 5 -o /dev/null -w "%{http_code}" \
        -X POST "${TOXI_ADMIN_URL}/reset" 2>/dev/null || echo "000")
    if [ "${http_code}" = "204" ] || [ "${http_code}" = "200" ]; then
        print_info "[RESET] Toxiproxy reset 완료 — 모든 toxic 제거됨"
    else
        print_error "reset 실패: HTTP ${http_code}"
        exit 1
    fi
}

# 프록시 통과 사전 확인 — toxic 없이 Kafka 연결 가능 여부 확인
# 프록시를 실제로 통과하는지 입증: Docker 내부에서 toxiproxy:9094 → kafka:9094 경유 메타데이터 조회.
verify_proxy_passthrough() {
    print_section "▶ 프록시 통과 사전 확인 (toxic 미주입 상태)"

    verify_proxy_registered

    # 1. 호스트 TCP 연결 확인 (Toxiproxy 호스트 포트 9094)
    print_section "  [1] 호스트 TCP 연결 확인 (${TOXI_HOST}:9094)"
    if command -v nc > /dev/null 2>&1; then
        if nc -z -w 3 "${TOXI_HOST}" 9094 2>/dev/null; then
            print_info "  [OK] ${TOXI_HOST}:9094 TCP 연결 가능"
        else
            print_warning "  [WARN] ${TOXI_HOST}:9094 TCP 연결 불가 — 호스트 포트 노출 확인"
        fi
    else
        print_warning "  [SKIP] nc 없음 — 아래 Docker exec 방식으로 확인"
    fi

    # 2. Docker 내부에서 toxiproxy:9094 경유 Kafka API 버전 조회
    #    PROXY 리스너 + Toxiproxy upstream 이 모두 정상이면 성공.
    print_section "  [2] Docker 내부 경로 확인 (kafka container exec → toxiproxy:9094)"
    if docker inspect payment-kafka > /dev/null 2>&1; then
        if docker exec payment-kafka kafka-broker-api-versions \
            --bootstrap-server toxiproxy:9094 > /dev/null 2>&1; then
            print_info "  [OK] kafka-broker-api-versions via toxiproxy:9094 성공"
            print_info "       → PROXY 리스너 + Toxiproxy upstream 정상 — 프록시 통과 확인"
        else
            print_error "  [FAIL] kafka-broker-api-versions via toxiproxy:9094 실패"
            print_warning "  확인 사항:"
            print_warning "    - Kafka PROXY 리스너(9094) 기동 여부: docker logs payment-kafka | grep PROXY"
            print_warning "    - Toxiproxy upstream 설정: ${TOXI_ADMIN_URL}/proxies/${PROXY_NAME}"
            print_warning "    - Toxiproxy와 Kafka 가 동일 네트워크(payment-infra-network)에 있는지"
        fi
    else
        print_warning "  [SKIP] payment-kafka 컨테이너 없음 — 인프라 기동 여부 확인"
    fi
}

# ── 사용법 출력 ──────────────────────────────────────────────────────────────
usage() {
    echo "사용법: $0 <command>"
    echo ""
    echo "명령:"
    echo "  inject   — Kafka PROXY 리스너에 latency toxic 주입"
    echo "             (latency=${LATENCY_MS}ms, jitter=${LATENCY_JITTER_MS}ms)"
    echo "  remove   — latency toxic 해제"
    echo "  status   — 현재 Toxiproxy 프록시/toxic 상태 출력"
    echo "  verify   — 프록시 통과 사전 확인 (TCP + Docker exec 경유)"
    echo "  reset    — 전체 toxic 제거 (Toxiproxy /reset)"
    echo ""
    echo "환경변수 오버라이드 (선택):"
    echo "  TOXI_HOST         — Toxiproxy 호스트 (기본: localhost)"
    echo "  TOXI_ADMIN_PORT   — admin API 포트 (기본: 8474)"
    echo "  PROXY_NAME        — 프록시 이름 (기본: kafka-proxy)"
    echo "  LATENCY_MS        — 주입 지연 ms (기본: 500)"
    echo "  LATENCY_JITTER_MS — 지터 ms (기본: 50)"
    echo "  TOXIC_NAME        — toxic 이름 (기본: latency_toxic)"
    exit 0
}

# ── 메인 ─────────────────────────────────────────────────────────────────────
COMMAND="${1:-}"

case "${COMMAND}" in
    inject)
        check_toxiproxy
        verify_proxy_registered
        inject_latency
        ;;
    remove)
        check_toxiproxy
        remove_latency
        ;;
    status)
        check_toxiproxy
        show_status
        ;;
    verify)
        check_toxiproxy
        verify_proxy_passthrough
        ;;
    reset)
        check_toxiproxy
        reset_all
        ;;
    --help|-h|help)
        usage
        ;;
    "")
        print_error "명령을 지정하세요"
        echo ""
        usage
        ;;
    *)
        print_error "알 수 없는 명령: ${COMMAND}"
        echo ""
        usage
        ;;
esac
