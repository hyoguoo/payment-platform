#!/usr/bin/env bash
# bench-clean-cycle.sh — 볼륨까지 내리고 완전히 새 상태에서 사이클 하나를 돌린다.
#
# 왜 필요한가:
#   bench-scaleout-cycle.sh 의 재구성(bench-cycle-reset.sh)은 payment 원장 여섯 테이블만 비운다.
#   pg 쪽 pg_inbox/pg_outbox, Kafka 로그·오프셋, MySQL 볼륨은 사이클마다 계속 쌓여, 뒤로 갈수록
#   측정 조건이 단조적으로 나빠진다 — 실측에서 pg_inbox 47만 → 57만 행 구간에 1대 처리량이
#   51.8 → 31.0/s 로 떨어졌고, 30여 사이클 뒤에는 Kafka 브로커가 로그 디렉토리 장애로 죽었다.
#   이 하락은 구성 차이로 오독되기 쉽다(순차로 돌린 사이클은 뒤가 무조건 불리하다).
#
#   이 스크립트는 매 사이클 앞에서 스택과 볼륨을 통째로 지워, 모든 사이클이 같은 출발점에서
#   시작하게 한다. 대신 사이클당 5~7분이 더 걸린다.
#
# 하는 일:
#   (1) 스택 + 볼륨 완전 제거 (docker compose down -v)
#   (2) 인프라 기동 → MySQL healthy 대기
#   (3) 앱 기동 → payment 스키마(Flyway) 생성 대기
#   (4) payment DB 복제 구성 (bench-replica-setup.sh — 사이클 러너가 부르지 않는다)
#   (5) bench-scaleout-cycle.sh 위임 — 인자로 받은 조건 값 그대로
#
# 사용:
#   INSTANCES=2 CASE_NAME=clean-i2 bash scripts/bench-clean-cycle.sh
#   전달되지 않은 조건 값은 bench-scaleout-cycle.sh 의 기본값을 그대로 쓴다.
#
# 주의: 볼륨을 지우므로 이전 사이클의 원장·결과 데이터는 남지 않는다. results/*.json 만 남는다.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

DC=(docker compose
  -f docker/docker-compose.infra.yml
  -f docker/docker-compose.apps.yml
  -f docker/docker-compose.observability.yml
  -f docker/docker-compose.benchmark.yml
  -f docker/docker-compose.scaleout.yml)

MYSQL_PAYMENT_ROOT_PASSWORD="${MYSQL_PAYMENT_ROOT_PASSWORD:-payment123}"

say() { echo "[clean-cycle] $*"; }

wait_healthy_named() {
    local name="$1" timeout="${2:-180}" waited=0
    until [[ "$(docker inspect --format '{{.State.Health.Status}}' "${name}" 2>/dev/null)" == "healthy" ]]; do
        sleep 3; waited=$((waited + 3))
        if [[ "${waited}" -ge "${timeout}" ]]; then
            say "❌ ${name} healthy 대기 시간 초과(${timeout}s)"
            return 1
        fi
    done
    return 0
}

say "(1) 스택 + 볼륨 제거"
"${DC[@]}" down -v --remove-orphans >/dev/null 2>&1
# 재고/멱등 캐시 클러스터는 compose 서비스 밖에서 관리돼 down 으로 안 지워지는 경우가 있다.
docker rm -f $(docker ps -aq --filter "name=redis-stock-cluster" --filter "name=redis-idempotency-cluster") >/dev/null 2>&1 || true

say "(2) 인프라 기동"
if ! "${DC[@]}" up -d eureka kafka redis-dedupe redis-stock \
    mysql-payment mysql-payment-replica mysql-pg mysql-product mysql-user \
    prometheus alertmanager grafana kafka-exporter tempo loki promtail >/dev/null 2>&1; then
    say "❌ 인프라 기동 실패"; exit 1
fi
for c in payment-mysql-payment payment-mysql-payment-replica payment-kafka; do
    wait_healthy_named "${c}" 240 || exit 1
done

say "(3) 앱 기동 + 스키마 생성 대기"
"${DC[@]}" up -d user-service >/dev/null 2>&1 || { say "❌ user-service 기동 실패"; exit 1; }
"${DC[@]}" up -d product-service pg-service payment-service >/dev/null 2>&1 || { say "❌ 앱 기동 실패"; exit 1; }

waited=0
until docker exec payment-mysql-payment mysql -u root -p"${MYSQL_PAYMENT_ROOT_PASSWORD}" -N -B \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='payment-platform' AND table_name='payment_event';" 2>/dev/null | grep -q '^1$'; do
    sleep 3; waited=$((waited + 3))
    if [[ "${waited}" -ge 240 ]]; then say "❌ payment 스키마 생성 대기 초과"; exit 1; fi
done

# 사용자 시드 확인 — application-docker.yml 의 flyway locations 에 db/seed 가 포함돼야 채워진다.
# 이미지가 그 수정 이전 빌드이면 여기서 0 이 나온다. 부하가 전량 실패하므로 즉시 멈춘다.
USERS=$(docker exec payment-mysql-user mysql -u root -p"${MYSQL_PAYMENT_ROOT_PASSWORD}" -N -B \
    -e "SELECT COUNT(*) FROM user.user;" 2>/dev/null || echo 0)
if [[ "${USERS}" -lt 1 ]]; then
    say "❌ 사용자 시드가 비어 있다 — user-service 이미지가 flyway locations 수정 이전 빌드다"
    say "   ./gradlew :user-service:bootJar && docker compose ... build user-service 후 다시 실행하라"
    exit 1
fi
say "    사용자 시드 확인 (${USERS}행)"

say "(4) payment DB 복제 구성"
if ! bash "${ROOT_DIR}/scripts/bench-replica-setup.sh" >/dev/null 2>&1; then
    say "❌ 복제 구성 실패"; exit 1
fi

say "(5) 사이클 실행 위임 → bench-scaleout-cycle.sh"
bash "${ROOT_DIR}/scripts/bench-scaleout-cycle.sh"
exit $?
