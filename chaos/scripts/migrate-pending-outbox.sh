#!/usr/bin/env bash
# migrate-pending-outbox.sh
#
# 목적: Strangler Fig 전환 시 모놀리스 DB에 잔존하는 PENDING outbox 레코드를
#       payment-service DB로 이관한다.
#       이관 후 모놀리스 측 confirm 경로가 비활성화(payment.monolith.confirm.enabled=false)된
#       상태에서도 payment-service OutboxWorker가 이어서 처리할 수 있다.
#
# 사전 조건:
#   - mysql CLI가 PATH에 존재해야 한다.
#   - 아래 환경 변수가 모두 설정돼 있어야 한다.
#
# 환경 변수:
#   MONOLITH_DB_HOST    — 모놀리스 DB 호스트 (default: 127.0.0.1)
#   MONOLITH_DB_PORT    — 모놀리스 DB 포트 (default: 3306)
#   MONOLITH_DB_NAME    — 모놀리스 DB 이름 (default: payment_platform)
#   MONOLITH_DB_USER    — 모놀리스 DB 사용자
#   MONOLITH_DB_PASS    — 모놀리스 DB 비밀번호
#   PAYMENT_DB_HOST     — payment-service DB 호스트 (default: 127.0.0.1)
#   PAYMENT_DB_PORT     — payment-service DB 포트 (default: 3307)
#   PAYMENT_DB_NAME     — payment-service DB 이름 (default: payment_service)
#   PAYMENT_DB_USER     — payment-service DB 사용자
#   PAYMENT_DB_PASS     — payment-service DB 비밀번호
#
# 옵션:
#   --dry-run           — 실제 INSERT 없이 이관 대상 레코드만 출력한다.

set -euo pipefail

# ---------------------------------------------------------------------------
# 기본값 설정
# ---------------------------------------------------------------------------
MONOLITH_DB_HOST="${MONOLITH_DB_HOST:-127.0.0.1}"
MONOLITH_DB_PORT="${MONOLITH_DB_PORT:-3306}"
MONOLITH_DB_NAME="${MONOLITH_DB_NAME:-payment_platform}"
MONOLITH_DB_USER="${MONOLITH_DB_USER:-}"
MONOLITH_DB_PASS="${MONOLITH_DB_PASS:-}"

PAYMENT_DB_HOST="${PAYMENT_DB_HOST:-127.0.0.1}"
PAYMENT_DB_PORT="${PAYMENT_DB_PORT:-3307}"
PAYMENT_DB_NAME="${PAYMENT_DB_NAME:-payment_service}"
PAYMENT_DB_USER="${PAYMENT_DB_USER:-}"
PAYMENT_DB_PASS="${PAYMENT_DB_PASS:-}"

DRY_RUN=false

# ---------------------------------------------------------------------------
# 인수 파싱
# ---------------------------------------------------------------------------
for arg in "$@"; do
    case "$arg" in
        --dry-run)
            DRY_RUN=true
            ;;
        *)
            echo "[ERROR] 알 수 없는 옵션: $arg" >&2
            exit 1
            ;;
    esac
done

# ---------------------------------------------------------------------------
# 필수 환경 변수 검증
# ---------------------------------------------------------------------------
missing_vars=()
[[ -z "$MONOLITH_DB_USER" ]] && missing_vars+=("MONOLITH_DB_USER")
[[ -z "$MONOLITH_DB_PASS" ]] && missing_vars+=("MONOLITH_DB_PASS")
[[ -z "$PAYMENT_DB_USER" ]]  && missing_vars+=("PAYMENT_DB_USER")
[[ -z "$PAYMENT_DB_PASS" ]]  && missing_vars+=("PAYMENT_DB_PASS")

if [[ ${#missing_vars[@]} -gt 0 ]]; then
    echo "[ERROR] 다음 환경 변수가 설정되지 않았습니다: ${missing_vars[*]}" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# mysql CLI 존재 여부 확인
# ---------------------------------------------------------------------------
if ! command -v mysql &>/dev/null; then
    echo "[ERROR] mysql CLI를 찾을 수 없습니다. PATH를 확인하세요." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 헬퍼 함수
# ---------------------------------------------------------------------------
monolith_mysql() {
    mysql \
        -h "$MONOLITH_DB_HOST" \
        -P "$MONOLITH_DB_PORT" \
        -u "$MONOLITH_DB_USER" \
        -p"$MONOLITH_DB_PASS" \
        --batch \
        --skip-column-names \
        "$MONOLITH_DB_NAME" \
        "$@"
}

payment_mysql() {
    mysql \
        -h "$PAYMENT_DB_HOST" \
        -P "$PAYMENT_DB_PORT" \
        -u "$PAYMENT_DB_USER" \
        -p"$PAYMENT_DB_PASS" \
        --batch \
        "$PAYMENT_DB_NAME" \
        "$@"
}

# ---------------------------------------------------------------------------
# PENDING outbox 레코드 조회
# ---------------------------------------------------------------------------
echo "[INFO] 모놀리스 DB의 PENDING outbox 레코드를 조회합니다..."
echo "[INFO]   source: ${MONOLITH_DB_HOST}:${MONOLITH_DB_PORT}/${MONOLITH_DB_NAME}"
echo "[INFO]   target: ${PAYMENT_DB_HOST}:${PAYMENT_DB_PORT}/${PAYMENT_DB_NAME}"

PENDING_COUNT=$(monolith_mysql -e \
    "SELECT COUNT(*) FROM payment_outbox WHERE processed_at IS NULL;")

echo "[INFO] 이관 대상 PENDING 레코드 수: ${PENDING_COUNT}"

if [[ "$PENDING_COUNT" -eq 0 ]]; then
    echo "[INFO] 이관할 PENDING 레코드가 없습니다. 종료합니다."
    exit 0
fi

# ---------------------------------------------------------------------------
# dry-run 모드: 대상 레코드만 출력
# ---------------------------------------------------------------------------
if [[ "$DRY_RUN" == "true" ]]; then
    echo "[DRY-RUN] 이관 대상 레코드 (실제 INSERT 없음):"
    monolith_mysql --column-names -e \
        "SELECT id, order_id, payment_key, status, created_at
         FROM payment_outbox
         WHERE processed_at IS NULL
         ORDER BY created_at;"
    echo "[DRY-RUN] dry-run 완료. 실제 이관은 --dry-run 옵션 없이 실행하세요."
    exit 0
fi

# ---------------------------------------------------------------------------
# 실제 이관: 모놀리스 → payment-service
# ---------------------------------------------------------------------------
echo "[INFO] PENDING outbox 레코드를 payment-service DB로 이관합니다..."

# 모놀리스 PENDING 레코드를 TSV로 추출
TMPFILE=$(mktemp /tmp/pending_outbox_XXXXXX.tsv)
trap 'rm -f "$TMPFILE"' EXIT

monolith_mysql -e \
    "SELECT id, order_id, payment_key, status, retry_count, created_at, updated_at
     FROM payment_outbox
     WHERE processed_at IS NULL
     ORDER BY created_at;" > "$TMPFILE"

ROW_COUNT=$(wc -l < "$TMPFILE")
echo "[INFO] 추출된 레코드 수: ${ROW_COUNT}"

if [[ "$ROW_COUNT" -eq 0 ]]; then
    echo "[INFO] 추출된 레코드가 없습니다. 종료합니다."
    exit 0
fi

# payment-service DB에 INSERT (중복 키 무시: 이미 이관된 레코드 스킵)
payment_mysql -e \
    "LOAD DATA LOCAL INFILE '${TMPFILE}'
     IGNORE
     INTO TABLE payment_outbox
     FIELDS TERMINATED BY '\t'
     LINES TERMINATED BY '\n'
     (id, order_id, payment_key, status, retry_count, created_at, updated_at);"

echo "[INFO] 이관 완료: ${ROW_COUNT}건"
echo "[INFO] payment-service OutboxWorker가 PENDING 레코드를 이어서 처리합니다."
