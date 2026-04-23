#!/usr/bin/env bash
# compose-up.sh — 인프라 + 앱 + 관측성 컨테이너 일괄 기동
#
# 기본 동작: **완전 재빌드** (bootJar + docker image + 컨테이너 강제 재생성)
#   - gradle bootJar로 6개 모듈 jar 재생성
#   - docker compose up --build --force-recreate 로 이미지 재빌드 + 컨테이너 재생성
#   - 설정/코드 변경이 항상 반영됨 (캐시에 의존하지 않음)
#
# 절차:
#   1. Docker 데몬 확인
#   2. (옵션 --reset-db) MySQL 볼륨 제거 → Flyway 최초 상태로 초기화
#   3. (옵션 --skip-build) bootJar 6개 빌드
#   4. 인프라(docker-compose.infra.yml) 기동 + healthy 대기
#   5. Kafka 토픽 생성 (create-topics.sh 위임)
#   6. 앱(docker-compose.apps.yml) 이미지 재빌드 + 강제 재생성 + healthy 대기
#   7. 관측성(docker-compose.observability.yml) 기동 + healthy 대기
#   8. Eureka 등록 확인 + 접속 URL 안내
#
# 사용법:
#   bash scripts/compose-up.sh                # 완전 재빌드 후 기동
#   bash scripts/compose-up.sh --skip-build   # bootJar 재빌드만 생략 (Docker image는 여전히 재빌드)
#   bash scripts/compose-up.sh --skip-obs     # 관측성 스택 생략
#   bash scripts/compose-up.sh --reset-db     # MySQL 볼륨 제거 후 완전 재기동 (Flyway 재실행)
#   bash scripts/compose-up.sh --down         # 전체 종료 (볼륨 유지)
#   bash scripts/compose-up.sh --clean        # 전체 종료 + 모든 볼륨 제거
#
# 환경변수:
#   TOSS_SECRET_KEY        — payment-service Toss 샌드박스 키
#   NICEPAY_CLIENT_KEY     — pg-service NicePay 키
#   NICEPAY_SECRET_KEY     — pg-service NicePay 시크릿
#   GRAFANA_USER           — Grafana admin 계정 (기본 admin)
#   GRAFANA_PASSWORD       — Grafana admin 비밀번호 (기본 admin123)

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${PROJECT_ROOT}"

# 공용 색상/헬퍼
# shellcheck disable=SC1091
source "${PROJECT_ROOT}/scripts/common.sh"

# .env.secret 자동 로드
# docker compose 는 .env 만 자동 로드하고 .env.secret 은 읽지 않으므로,
# compose 변수 치환(${TOSS_TEST_SECRET_KEY} 등) 전에 셸에 export 해둔다.
# 파일 미존재 시 경고만 출력하고 계속 진행 (placeholder 값으로 기동).
if [[ -f "${PROJECT_ROOT}/.env.secret" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "${PROJECT_ROOT}/.env.secret"
  set +a
else
  echo "[compose-up] 주의: .env.secret 없음 — PG 샌드박스 키가 placeholder 로 주입됨"
fi

INFRA_COMPOSE="docker-compose.infra.yml"
APPS_COMPOSE="docker-compose.apps.yml"
OBS_COMPOSE="docker-compose.observability.yml"

COMPOSE_ARGS_APPS="-f ${INFRA_COMPOSE} -f ${APPS_COMPOSE}"
COMPOSE_ARGS_ALL="-f ${INFRA_COMPOSE} -f ${APPS_COMPOSE} -f ${OBS_COMPOSE}"

# docker compose 프로젝트명 (volume 접두어) — 상위 디렉토리 이름 기반
COMPOSE_PROJECT_NAME="$(basename "${PROJECT_ROOT}")"

MYSQL_CONTAINERS=(payment-mysql-payment payment-mysql-pg payment-mysql-product payment-mysql-user)
MYSQL_VOLUMES=(
  "${COMPOSE_PROJECT_NAME}_mysql-payment-data"
  "${COMPOSE_PROJECT_NAME}_mysql-pg-data"
  "${COMPOSE_PROJECT_NAME}_mysql-product-data"
  "${COMPOSE_PROJECT_NAME}_mysql-user-data"
)

SKIP_BUILD=false
SKIP_OBS=false
RESET_DB=false
DO_DOWN=false
DO_CLEAN=false

for arg in "$@"; do
  case "${arg}" in
    --skip-build) SKIP_BUILD=true ;;
    --skip-obs) SKIP_OBS=true ;;
    --reset-db) RESET_DB=true ;;
    --down) DO_DOWN=true ;;
    --clean) DO_CLEAN=true ;;
    -h|--help)
      sed -n '2,32p' "$0"
      exit 0
      ;;
    *)
      print_error "알 수 없는 옵션: ${arg}"
      exit 1
      ;;
  esac
done

# ───────────────────────────────────────────
# 종료 경로 (--down / --clean)
# ───────────────────────────────────────────
if [[ "${DO_CLEAN}" == "true" ]]; then
  print_warning "전체 컨테이너 + 볼륨 제거 (infra + apps + observability)"
  docker compose ${COMPOSE_ARGS_ALL} down -v
  print_info "완료. 다음 기동은 처음부터 Flyway 마이그레이션이 다시 돈다."
  exit 0
fi

if [[ "${DO_DOWN}" == "true" ]]; then
  print_warning "전체 컨테이너 종료(볼륨 유지, infra + apps + observability)"
  docker compose ${COMPOSE_ARGS_ALL} down
  exit 0
fi

# ───────────────────────────────────────────
# 1. Docker 데몬 확인
# ───────────────────────────────────────────
check_docker

# ───────────────────────────────────────────
# 2. (옵션) MySQL 초기화
# ───────────────────────────────────────────
if [[ "${RESET_DB}" == "true" ]]; then
  print_section "▶ DB 초기화 (--reset-db): MySQL 컨테이너 + 볼륨 제거"
  # MySQL 컨테이너만 멈추고 제거 (kafka/redis/eureka 등 다른 인프라는 보존)
  for c in "${MYSQL_CONTAINERS[@]}"; do
    if docker ps -a --format '{{.Names}}' | grep -qx "${c}"; then
      docker rm -f "${c}" >/dev/null 2>&1 || true
      print_info "  - ${c} 제거"
    fi
  done
  for v in "${MYSQL_VOLUMES[@]}"; do
    if docker volume ls --format '{{.Name}}' | grep -qx "${v}"; then
      docker volume rm "${v}" >/dev/null
      print_info "  - volume ${v} 제거"
    else
      print_warning "  - volume ${v} 없음 (건너뜀)"
    fi
  done
  print_info "✅ MySQL 초기화 완료. 인프라 기동 시 Flyway가 모든 마이그레이션을 처음부터 적용한다."
fi

# ───────────────────────────────────────────
# 3. bootJar 빌드
# ───────────────────────────────────────────
if [[ "${SKIP_BUILD}" == "false" ]]; then
  print_section "▶ bootJar 빌드 (6 모듈)"
  ./gradlew \
    :eureka-server:bootJar \
    :payment-service:bootJar \
    :pg-service:bootJar \
    :product-service:bootJar \
    :user-service:bootJar \
    :gateway:bootJar \
    --console=plain
  print_info "✅ bootJar 빌드 완료"
else
  print_warning "▶ bootJar 빌드 건너뜀(--skip-build). build/libs/*.jar 존재 확인:"
  for module in eureka-server payment-service pg-service product-service user-service gateway; do
    if ! ls "${module}"/build/libs/*.jar >/dev/null 2>&1; then
      print_error "❌ ${module}/build/libs/*.jar 없음. --skip-build 제거 후 재실행 필요."
      exit 1
    fi
  done
  print_info "✅ 모든 모듈 jar 확인"
fi

# ───────────────────────────────────────────
# healthy 대기 헬퍼
# ───────────────────────────────────────────
# $1: 단계 이름, $2: 타임아웃(초), $3..: 컨테이너 이름
wait_healthy() {
  local stage="$1"; shift
  local timeout="$1"; shift
  local containers=("$@")
  local deadline=$(( $(date +%s) + timeout ))

  print_warning "${stage} healthy 대기(최대 ${timeout}초)..."
  while :; do
    local all_healthy=true
    for c in "${containers[@]}"; do
      local status
      status=$(docker inspect -f '{{.State.Health.Status}}' "${c}" 2>/dev/null || echo "absent")
      if [[ "${status}" != "healthy" ]]; then
        all_healthy=false
        break
      fi
    done
    if [[ "${all_healthy}" == "true" ]]; then
      print_info "✅ ${stage} ${#containers[@]}개 컨테이너 모두 healthy"
      return 0
    fi
    if (( $(date +%s) > deadline )); then
      print_error "❌ ${timeout}초 내에 ${stage} healthy 실패."
      for c in "${containers[@]}"; do
        local status
        status=$(docker inspect -f '{{.State.Health.Status}}' "${c}" 2>/dev/null || echo "absent")
        echo "  - ${c}: ${status}"
      done
      return 1
    fi
    sleep 3
    echo -n "."
  done
}

# ───────────────────────────────────────────
# 4. 인프라 기동
# ───────────────────────────────────────────
print_section "▶ 인프라 컨테이너 기동 (${INFRA_COMPOSE})"
docker compose -f "${INFRA_COMPOSE}" up -d

if ! wait_healthy "인프라" 120 \
  payment-kafka payment-redis-dedupe payment-redis-stock payment-eureka \
  payment-mysql-payment payment-mysql-pg payment-mysql-product payment-mysql-user; then
  docker compose -f "${INFRA_COMPOSE}" ps
  exit 1
fi
echo

# ───────────────────────────────────────────
# 5. Kafka 토픽 생성 (멱등)
# ───────────────────────────────────────────
print_section "▶ Kafka 토픽 생성"
bash "${PROJECT_ROOT}/scripts/phase-gate/create-topics.sh"

# ───────────────────────────────────────────
# 6. 앱 기동 (완전 재빌드: image rebuild + 컨테이너 강제 재생성)
# ───────────────────────────────────────────
print_section "▶ 앱 컨테이너 완전 재빌드 (${APPS_COMPOSE})"
docker compose ${COMPOSE_ARGS_APPS} up -d --build --force-recreate \
  payment-service pg-service product-service user-service gateway

if ! wait_healthy "앱" 180 \
  payment-service pg-service product-service user-service gateway; then
  docker compose ${COMPOSE_ARGS_APPS} ps
  print_warning "로그 확인: docker compose ${COMPOSE_ARGS_APPS} logs -f <service>"
  exit 1
fi
echo

# ───────────────────────────────────────────
# 7. 관측성 기동 (Prometheus / Grafana / Loki / Tempo / Promtail / kafka-exporter)
# ───────────────────────────────────────────
if [[ "${SKIP_OBS}" == "true" ]]; then
  print_warning "▶ 관측성 스택 기동 건너뜀(--skip-obs)"
else
  print_section "▶ 관측성 컨테이너 기동 (${OBS_COMPOSE})"
  docker compose ${COMPOSE_ARGS_ALL} up -d \
    prometheus grafana loki tempo promtail kafka-exporter

  # promtail / kafka-exporter는 healthcheck 없음 → healthcheck 있는 4개만 대기
  if ! wait_healthy "관측성" 120 \
    payment-prometheus payment-grafana payment-loki payment-tempo; then
    docker compose ${COMPOSE_ARGS_ALL} ps prometheus grafana loki tempo promtail kafka-exporter
    print_warning "로그 확인: docker compose ${COMPOSE_ARGS_ALL} logs -f <service>"
    exit 1
  fi
  echo
fi

# ───────────────────────────────────────────
# 8. Eureka 등록 확인 + 안내
# ───────────────────────────────────────────
print_section "▶ Eureka 등록 확인"
sleep 5  # Eureka 인스턴스 리프레시 텀
registered=$(curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps \
  | grep -oE '<name>[A-Z-]+</name>' | sort -u || true)
if [[ -z "${registered}" ]]; then
  print_warning "⚠ 아직 Eureka 등록 목록이 비어있음. 30초 더 기다렸다가 http://localhost:8761 수동 확인 권장."
else
  echo "등록된 서비스:"
  echo "${registered}" | sed 's/<name>/  - /;s/<\/name>//'
fi

echo
print_section "=== 접속 URL ==="
echo "Gateway:           http://localhost:8090   (외부 진입점)"
echo "Eureka Dashboard:  http://localhost:8761"
echo "payment-service:   컨테이너 내부 only (Gateway /api/v1/payments 경유)"
echo "pg-service:        컨테이너 내부 only"
echo "product-service:   컨테이너 내부 only (Gateway /api/v1/products 경유)"
echo "user-service:      컨테이너 내부 only (Gateway /api/v1/users 경유)"

if [[ "${SKIP_OBS}" != "true" ]]; then
  echo
  print_section "=== 관측성 URL ==="
  echo "Grafana:           http://localhost:3000   (${GRAFANA_USER:-admin} / ${GRAFANA_PASSWORD:-admin123})"
  echo "Prometheus:        http://localhost:9090"
  echo "Loki API:          http://localhost:3100   (Grafana Explore → Loki 데이터소스로 조회)"
  echo "Tempo API:         http://localhost:3200   (Grafana Explore → Tempo로 trace 조회)"
  echo "kafka-exporter:    http://localhost:9308/metrics"
fi

echo
print_warning "스모크 시나리오: docs/INTEGRATION-SMOKE.md §3~§6 참고"
print_warning "로그 팔로우:     docker compose ${COMPOSE_ARGS_ALL} logs -f <service>"
print_warning "종료:            bash scripts/compose-up.sh --down  (또는 --clean 으로 볼륨 포함 제거)"
