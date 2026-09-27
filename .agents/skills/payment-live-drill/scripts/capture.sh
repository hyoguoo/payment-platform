#!/usr/bin/env bash
# 라이브 실측 화면 캡처 — 2배 해상도 PNG 로 남긴다.
#
#   capture.sh <파일이름> <URL> [창높이]
#
#   capture.sh 01-portal-landing http://localhost:8090/
#   capture.sh 19-grafana-business "http://localhost:3000/d/xxx?kiosk" 2400
#
# 창 너비 1440 · 높이 기본 900 (CSS 기준). 저장되는 PNG 는 그 2배 픽셀.
# 세로로 긴 대시보드는 높이를 키워 스크롤 없이 한 장에 담는다.
#
# 환경변수:
#   CAPTURE_OUT_DIR 저장 폴더 (기본: 현재 작업 디렉토리의 live-drill/originals)
#   CAPTURE_WIDTH   창 너비 (기본 1440)
#   CAPTURE_DELAY   페이지 렌더 대기 ms (기본 5000). 그래프가 늦게 그려지면 늘린다.
set -euo pipefail

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
# 저장 위치는 스크립트 위치가 아니라 호출한 곳 기준이다 — 스크립트가 스킬 폴더에 있어도
# 캡처는 작업 중인 저장소에 쌓여야 하기 때문이다.
OUT_DIR="${CAPTURE_OUT_DIR:-$PWD/live-drill/originals}"
PROFILE="/tmp/live-drill-chrome-profile"
WIDTH="${CAPTURE_WIDTH:-1440}"
DELAY="${CAPTURE_DELAY:-5000}"

if [ $# -lt 2 ]; then
    echo "usage: $0 <파일이름> <URL> [창높이]" >&2
    exit 1
fi

name="$1"
url="$2"
height="${3:-900}"
out="$OUT_DIR/${name}.png"

if [ ! -x "$CHROME" ]; then
    echo "Chrome 을 찾을 수 없다: $CHROME" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
rm -f "$out"

# 이전 실행이 강제 종료되며 남긴 프로필 락 제거 (남아 있으면 새 실행이 무한 대기)
rm -f "$PROFILE/SingletonLock" "$PROFILE/SingletonSocket" "$PROFILE/SingletonCookie" 2>/dev/null || true

"$CHROME" --headless=new --disable-gpu --hide-scrollbars \
    --user-data-dir="$PROFILE" \
    --window-size="${WIDTH},${height}" \
    --force-device-scale-factor=2 \
    --virtual-time-budget="$DELAY" \
    --screenshot="$out" "$url" >/dev/null 2>&1 &
chrome_pid=$!

# Chrome 은 캡처를 마친 뒤에도 종료되지 않는 경우가 있어, 파일 크기가 멈추면 직접 내린다.
prev_size=-1
settled=0
for _ in $(seq 1 120); do
    if [ -s "$out" ]; then
        size=$(wc -c <"$out")
        if [ "$size" -eq "$prev_size" ]; then
            settled=1
            break
        fi
        prev_size=$size
    fi
    sleep 0.5
done

kill "$chrome_pid" 2>/dev/null || true
wait "$chrome_pid" 2>/dev/null || true

if [ "$settled" -ne 1 ] || [ ! -s "$out" ]; then
    echo "캡처 실패: $name ($url)" >&2
    exit 1
fi

dims=$(sips -g pixelWidth -g pixelHeight "$out" 2>/dev/null |
    awk '/pixelWidth/{w=$2} /pixelHeight/{h=$2} END{print w"x"h}')
printf '%s  %s  %s\n' "$name" "$dims" "$(du -h "$out" | cut -f1)"