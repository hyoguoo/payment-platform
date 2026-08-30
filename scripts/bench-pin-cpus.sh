#!/usr/bin/env bash
# bench-pin-cpus.sh — 실행 중인 컨테이너를 물리 코어에 고정한다(cpuset).
#
# 왜 상한(cpus/quota)이 아니라 코어 고정인가:
#   상한은 100ms 주기 예산을 다 쓰면 남은 주기 동안 컨테이너를 통째로 얼린다. payment-service
#   처럼 짧게 여러 코어를 요구하는 버스트형 워크로드에서는 평균 가동률이 낮아도 스로틀이
#   심하게 걸린다 — 실측에서 상한 2.0 코어에 평균 사용 0.66(33%)인데 시간의 61.6%를 막혀
#   처리량이 무제한 대비 17% 떨어졌다. 코어 고정은 얼리지 않고 쓸 수 있는 코어 수만 줄이므로
#   대기열이 길어질 뿐 정지하지 않는다.
#
# 왜 필요한가:
#   고정이 없으면 payment 1대일 때도 2대일 때도 같은 10코어 풀에서 퍼온다 — 인스턴스를 늘려도
#   앱에 주어지는 CPU 총량이 늘지 않아서, 거기서 잰 "1대 → 2대 배수"는 스케일아웃이 아니라
#   같은 파이를 쪼갠 결과다. 고정하면 1대 2코어 / 2대 4코어로 앱 계층이 실제로 2배가 되고,
#   공유 계층은 두 구성에서 똑같이 6코어를 받는다.
#
# 배분 (호스트 10 vCPU):
#   payment-service 1번 → 0,1 · 2번 → 2,3 · 3번 → 4,5 · 4번 → 6,7
#   그 밖의 모든 컨테이너 → SHARED_CPUS (기본 4-9)
#   인스턴스가 3대 이상이면 앱 코어와 공유 코어가 겹치므로 SHARED_CPUS 를 직접 넘겨 조정한다.
#
# 한계 — 맥에서는 컨테이너가 리눅스 VM 안에서 돈다. 여기서 고정하는 것은 그 VM 의 vCPU 이고,
#   vCPU 를 실제 물리 코어(성능/효율 코어가 섞여 있다)에 배치하는 것은 macOS 가 정한다.
#   격리가 리눅스 서버만큼 단단하지 않다 — 배수 판정에는 충분하지만 절대 수치 노이즈는 크다.
#   호스트에서 도는 k6 는 VM 밖이라 고정 대상이 아니다.
#
# 사용:
#   ./scripts/bench-pin-cpus.sh                  # 기본 배분
#   SHARED_CPUS=6-9 ./scripts/bench-pin-cpus.sh  # 공유 계층 코어 직접 지정
#
# bench-scaleout-cycle.sh 의 PRE_LOAD_HOOK 으로 걸어 부하 직전에 실행하는 것을 전제로 한다 —
# --scale 로 만들어진 인스턴스가 전부 뜬 뒤에 고정해야 하기 때문이다.

set -uo pipefail

SHARED_CPUS="${SHARED_CPUS:-4-9}"
PAYMENT_CPUS_PER_INSTANCE="${PAYMENT_CPUS_PER_INSTANCE:-2}"

# payment 인스턴스 N번이 받을 코어 범위 — 1번 0,1 / 2번 2,3 / ...
payment_cpuset_for() {
    local idx="$1"
    local start=$(( (idx - 1) * PAYMENT_CPUS_PER_INSTANCE ))
    local end=$(( start + PAYMENT_CPUS_PER_INSTANCE - 1 ))
    echo "${start}-${end}"
}

pinned=0
skipped=0

while read -r name; do
    [[ -z "${name}" ]] && continue

    if [[ "${name}" =~ payment-service-([0-9]+)$ ]]; then
        idx="${BASH_REMATCH[1]}"
        target=$(payment_cpuset_for "${idx}")
    else
        target="${SHARED_CPUS}"
    fi

    if docker update --cpuset-cpus="${target}" "${name}" >/dev/null 2>&1; then
        effective=$(docker exec "${name}" cat /sys/fs/cgroup/cpuset.cpus.effective 2>/dev/null || echo "?")
        printf '  %-40s → %-6s (실제 %s)\n' "${name}" "${target}" "${effective}"
        pinned=$((pinned + 1))
    else
        printf '  %-40s → 고정 실패(건너뜀)\n' "${name}"
        skipped=$((skipped + 1))
    fi
done < <(docker ps --format '{{.Names}}')

echo "  코어 고정 완료 — ${pinned}개 (실패 ${skipped}개)"
[[ "${pinned}" -eq 0 ]] && exit 1
exit 0
