#!/usr/bin/env python3
"""usl-fit.py — Universal Scalability Law(USL) 파라미터 피팅.

USL:  X(N) = gamma * N / (1 + alpha*(N-1) + beta*N*(N-1))
  - gamma : 단일 단위 처리율 (N=1 기준선)
  - alpha : contention   (직렬화 비율, Amdahl 항)
  - beta  : coherency    (상호 일관성/crosstalk 비용)
  - Nmax  = sqrt((1-alpha)/beta)  (처리율이 최대가 되는 동시성, beta>0)

입력 CSV (헤더 포함):
    N,throughput
    1,450
    2,450
  N = 동시성/인스턴스 수, throughput = 해당 N의 처리율(흡수 한계 rate 등).
  '#' 로 시작하는 줄은 주석으로 무시한다.

피팅 전략:
  - 측정점 >= 4 : gamma=X(N=1) 고정 후 (alpha,beta) grid search 로 SSE 최소화.
  - 측정점 2~3 : USL 3파라미터에 비해 데이터가 부족(underdetermined)하다.
      gamma 만 확정하고, 남은 1식으로 alpha+beta 제약식과
      경계 가정(beta=0 / alpha=0)별 Nmax 범위를 제시한다.

의존성 없음(순수 표준 라이브러리). 재현:  python3 scripts/usl-fit.py <csv>
"""
import sys
import csv
import math
import argparse


def usl(n, alpha, beta, gamma):
    return gamma * n / (1.0 + alpha * (n - 1.0) + beta * n * (n - 1.0))


def load(path):
    rows = []
    with open(path, newline="") as handle:
        reader = csv.reader(handle)
        for raw in reader:
            if not raw or raw[0].strip().startswith("#"):
                continue
            if raw[0].strip().lower() in ("n", "instances"):
                continue
            rows.append((float(raw[0]), float(raw[1])))
    rows.sort(key=lambda item: item[0])
    return rows


def grid_search(data, gamma):
    best = None
    alpha = 0.0
    while alpha <= 2.0 + 1e-9:
        beta = 0.0
        while beta <= 0.5 + 1e-9:
            sse = sum((usl(n, alpha, beta, gamma) - x) ** 2 for n, x in data)
            if best is None or sse < best[0]:
                best = (sse, alpha, beta)
            beta += 0.002
        alpha += 0.005
    return best


def main():
    parser = argparse.ArgumentParser(description="USL 파라미터 피팅")
    parser.add_argument("csv", help="입력 CSV: N,throughput")
    args = parser.parse_args()

    data = load(args.csv)
    if not data:
        print("오류: 측정점이 없습니다.", file=sys.stderr)
        return 1

    print(f"입력 측정점({len(data)}개): " + ", ".join(f"N={int(n)}→{x:g}" for n, x in data))

    base = next((x for n, x in data if n == 1.0), None)
    if base is None:
        print("경고: N=1 측정점이 없어 gamma 를 최소 N 처리율로 근사합니다.")
        base = data[0][1]
    gamma = base
    print(f"gamma (N=1 기준 처리율) = {gamma:.3f}")

    npts = len(data)
    if npts >= 4:
        sse, alpha, beta = grid_search(data, gamma)
        print(f"alpha (contention) = {alpha:.4f}")
        print(f"beta  (coherency)  = {beta:.5f}")
        print(f"잔차 SSE           = {sse:.4f}")
        if beta > 1e-9:
            nmax = math.sqrt((1.0 - alpha) / beta) if alpha < 1.0 else float("nan")
            print(f"Nmax (처리율 최대 동시성) = {nmax:.2f}")
        else:
            print("Nmax = 무한 (beta≈0, coherency 비용 없음 — Amdahl 한계만)")
        return 0

    print(f"\n[underdetermined] 측정점 {npts}개 < 파라미터 3개 — USL 다점 회귀 불가.")
    if npts >= 2:
        n2, x2 = data[-1]
        denom = gamma * n2 / x2
        c = denom - 1.0
        coef_a = n2 - 1.0
        coef_b = n2 * (n2 - 1.0)
        print(f"제약식: alpha*{coef_a:g} + beta*{coef_b:g} = {c:.4f}  "
              f"(X({int(n2)})/X(1) = {x2 / gamma:.3f})")
        if coef_a > 0:
            alpha_b0 = c / coef_a
            print(f"  · beta=0 가정  → alpha={alpha_b0:.3f}  "
                  f"(순수 contention, Nmax=무한이나 plateau)")
        if coef_b > 0:
            beta_a0 = c / coef_b
            if beta_a0 > 0:
                nmax_a0 = math.sqrt(1.0 / beta_a0)
                print(f"  · alpha=0 가정 → beta={beta_a0:.4f}  (Nmax={nmax_a0:.2f})")
        print("  ⇒ alpha·beta 개별 분리 불가. 제약식이 동시성 증가의 한계 비용을 규정한다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
