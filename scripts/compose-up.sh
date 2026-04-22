#!/usr/bin/env bash
# compose-up.sh — 전체 인프라 + 앱 컨테이너 일괄 기동
#
# 절차:
#   1. Docker 데몬 확인
#   2. (옵션) bootJar 6개 빌드
#   3. 인프라(docker-compose.infra.yml) 기동 + healthy 대기
#   4. Kafka 토픽 생성 (create-topics.sh 위임)
#   5. 앱(docker-compose.apps.yml) 기동 + healthy 대기
#   6. Eureka 등록 확인 + 접속 URL 안내
#
# 사용법:
#   bash scripts/compose-up.sh             # 빌드 포함 전체 기동
#   bash scripts/compose-up.sh --skip-build  # 이미 bootJar가 있으면 빌드 생략
#   bash scripts/compose-up.sh --down        # 전체 종료 (볼륨 유지)
#   bash scripts/compose-up.sh --clean       # 전체 종료 + 볼륨 제거
#
# 환경변수:
#   TOSS_SECRET_KEY        — payment-service Toss 샌드박스 키 (미설정 시 placeholder)
#   NICEPAY_CLIENT_KEY     — pg-service NicePay 키 (옵션)
#   NICEPAY_SECRET_KEY     — pg-service NicePay 시크릿 (옵션)

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${PROJECT_ROOT}"

# 공용 색상/헬퍼
# shellcheck disable=SC1091
source "${PROJECT_ROOT}/scripts/common.sh"

INFRA_COMPOSE="docker-compose.infra.yml"
APPS_COMPOSE="docker-compose.apps.yml"
COMPOSE_ARGS="-f ${INFRA_COMPOSE} -f ${APPS_COMPOSE}"

SKIP_BUILD=false
DO_DOWN=false
DO_CLEAN=false

for arg in "$@"; do
  case "${arg}" in
    --skip-build) SKIP_BUILD=true ;;
    --down) DO_DOWN=true ;;
    --clean) DO_CLEAN=true ;;
    -h|--help)
      sed -n '2,22p' "$0"
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
  print_warning "전체 컨테이너 + 볼륨 제거"
  docker compose ${COMPOSE_ARGS} down -v
  print_info "완료. 다음 기동은 처음부터 Flyway 마이그레이션이 다시 돈다."
  exit 0
fi

if [[ "${DO_DOWN}" == "true" ]]; then
  print_warning "전체 컨테이너 종료(볼륨 유지)"
  docker compose ${COMPOSE_ARGS} down
  exit 0
fi

# ───────────────────────────────────────────
# 1. Docker 데몬 확인
# ───────────────────────────────────────────
check_docker

# ───────────────────────────────────────────
# 2. bootJar 빌드
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
# 3. 인프라 기동
# ───────────────────────────────────────────
print_section "▶ 인프라 컨테이너 기동 (${INFRA_COMPOSE})"
docker compose -f "${INFRA_COMPOSE}" up -d

print_warning "인프라 healthy 대기(최대 120초)..."
deadline=$(( $(date +%s) + 120 ))
infra_services=(payment-kafka payment-redis-dedupe payment-redis-stock payment-eureka payment-mysql-payment payment-mysql-pg payment-mysql-product payment-mysql-user)
while :; do
  all_healthy=true
  for c in "${infra_services[@]}"; do
    status=$(docker inspect -f '{{.State.Health.Status}}' "${c}" 2>/dev/null || echo "absent")
    if [[ "${status}" != "healthy" ]]; then
      all_healthy=false
      break
    fi
  done
  if [[ "${all_healthy}" == "true" ]]; then
    print_info "✅ 인프라 8개 컨테이너 모두 healthy"
    break
  fi
  if (( $(date +%s) > deadline )); then
    print_error "❌ 120초 내에 인프라 healthy 실패. 상태:"
    docker compose -f "${INFRA_COMPOSE}" ps
    exit 1
  fi
  sleep 3
  echo -n "."
done
echo

# ───────────────────────────────────────────
# 4. Kafka 토픽 생성 (멱등)
# ───────────────────────────────────────────
print_section "▶ Kafka 토픽 생성"
bash "${PROJECT_ROOT}/scripts/phase-gate/create-topics.sh"

# ───────────────────────────────────────────
# 5. 앱 기동
# ───────────────────────────────────────────
print_section "▶ 앱 컨테이너 기동/재빌드 (${APPS_COMPOSE})"
docker compose ${COMPOSE_ARGS} up -d --build \
  payment-service pg-service product-service user-service gateway

print_warning "앱 컨테이너 healthy 대기(최대 180초)..."
deadline=$(( $(date +%s) + 180 ))
app_services=(payment-service pg-service product-service user-service gateway)
while :; do
  all_healthy=true
  for c in "${app_services[@]}"; do
    status=$(docker inspect -f '{{.State.Health.Status}}' "${c}" 2>/dev/null || echo "absent")
    if [[ "${status}" != "healthy" ]]; then
      all_healthy=false
      break
    fi
  done
  if [[ "${all_healthy}" == "true" ]]; then
    print_info "✅ 앱 5개 컨테이너 모두 healthy"
    break
  fi
  if (( $(date +%s) > deadline )); then
    print_error "❌ 180초 내에 앱 healthy 실패. 상태:"
    docker compose ${COMPOSE_ARGS} ps
    print_warning "로그 확인: docker compose ${COMPOSE_ARGS} logs -f <service>"
    exit 1
  fi
  sleep 5
  echo -n "."
done
echo

# ───────────────────────────────────────────
# 6. Eureka 등록 확인 + 안내
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
echo
print_warning "스모크 시나리오: docs/INTEGRATION-SMOKE.md §3~§6 참고"
print_warning "로그 팔로우:     docker compose ${COMPOSE_ARGS} logs -f <service>"
print_warning "종료:            bash scripts/compose-up.sh --down  (또는 --clean 으로 볼륨 포함 제거)"
