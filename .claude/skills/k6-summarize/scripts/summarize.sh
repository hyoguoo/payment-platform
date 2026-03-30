#!/bin/bash
# k6 벤치마크 결과 요약 테이블 출력
# 사용법: ./summarize.sh [results 디렉토리] (기본값: ./results)

RESULTS_DIR="${1:-$(dirname "$0")/results}"

if [ ! -d "${RESULTS_DIR}" ]; then
  echo "결과 디렉토리를 찾을 수 없습니다: ${RESULTS_DIR}"
  exit 1
fi

FILES=$(ls "${RESULTS_DIR}"/*.json 2>/dev/null | sort)
if [ -z "${FILES}" ]; then
  echo "JSON 파일이 없습니다: ${RESULTS_DIR}"
  exit 1
fi

python3 - "${RESULTS_DIR}" <<'EOF'
import json
import os
import sys

results_dir = sys.argv[1]
files = sorted(f for f in os.listdir(results_dir) if f.endswith('.json'))

rows = []
for filename in files:
    path = os.path.join(results_dir, filename)
    with open(path) as f:
        data = json.load(f)

    m = data.get('metrics', {})
    case = filename.replace('.json', '')

    def val(metric, key, default=0):
        return m.get(metric, {}).get('values', {}).get(key, default)

    tps        = val('confirm_requests', 'rate')
    http_med   = val('http_req_duration', 'med')
    http_p95   = val('http_req_duration', 'p(95)')
    e2e_med    = val('e2e_completion_ms', 'med')
    e2e_p95    = val('e2e_completion_ms', 'p(95)')
    err_rate   = val('http_req_failed', 'rate') * 100
    dropped    = int(val('dropped_iterations', 'count'))

    rows.append((case, tps, http_med, http_p95, e2e_med, e2e_p95, err_rate, dropped))

header = f"{'케이스':<32} {'TPS':>7}  {'HTTP med':>9}  {'HTTP p95':>9}  {'E2E med':>8}  {'E2E p95':>8}  {'에러율':>7}  {'Dropped':>8}"
sep    = '-' * len(header)

print(sep)
print(header)
print(sep)

for case, tps, http_med, http_p95, e2e_med, e2e_p95, err_rate, dropped in rows:
    e2e_med_str = f"{e2e_med:,.0f}ms" if e2e_med else "  -"
    e2e_p95_str = f"{e2e_p95:,.0f}ms" if e2e_p95 else "  -"
    print(
        f"{case:<32} {tps:>6.1f}/s"
        f"  {http_med:>7,.0f}ms"
        f"  {http_p95:>7,.0f}ms"
        f"  {e2e_med_str:>8}"
        f"  {e2e_p95_str:>8}"
        f"  {err_rate:>6.2f}%"
        f"  {dropped:>8,}"
    )

print(sep)
EOF
