---
name: k6-benchmark
description: payment-platform 전체 벤치마크 워크플로우를 실행한다. 서버 기동 → k6 부하 테스트 → 결과 요약을 순서대로 처리한다. "벤치마크 돌려줘", "부하 테스트 실행", "성능 측정", "k6 전체 실행", "run benchmark" 등 벤치마크 전체 흐름을 요청할 때 반드시 사용한다. 단계별로 실행해도 된다.
---

## 목적

세 단계를 순서대로 실행해 벤치마크 결과까지 한 번에 얻는다.

1. **서버 기동** — Docker 스택 전체를 올린다
2. **벤치마크 실행** — k6로 전략별 부하 테스트를 수행한다
3. **결과 요약** — 케이스별 TPS·레이턴시·에러율 표를 출력한다

사용자가 일부 단계만 원하면 해당 단계만 실행해도 된다 (예: "서버는 이미 떠 있으니 벤치마크만 돌려줘").

---

## 실행 전 확인

```bash
# 프로젝트 루트 확인
git rev-parse --show-toplevel

# Docker 실행 여부 확인
docker info > /dev/null 2>&1 && echo "Docker OK" || echo "Docker 미실행"
```

이후 모든 스크립트는 `<PROJECT_ROOT>` 절대 경로로 호출한다. `cd` 없이 항상 절대 경로를 사용해야 `run.sh` 내부의 `cd docker/compose`가 올바른 위치에서 동작한다.

---

## 단계 1: 서버 기동

```bash
bash <PROJECT_ROOT>/scripts/run.sh
```

`run.sh`는 내부적으로 `cd docker/compose`를 수행하므로 **반드시 절대 경로로 호출**해야 한다. 스크립트가 완료되면 약 30초 후 모든 서비스가 기동된다. 서비스 상태(mysql, app)가 출력으로 확인된다. 관측성 스택(Prometheus/Grafana)은 이 compose에서 제외됐으므로, 대시보드가 필요하면 MSA 관측성 compose(`docker-compose.observability.yml`)를 별도로 기동한다.

사전 조건:
- `docker/compose/.env.secret` 파일이 존재해야 한다 (없으면 스크립트가 안내 메시지를 출력하고 종료)
- Docker Desktop이 실행 중이어야 한다

---

## 단계 2: 벤치마크 실행

```bash
bash <PROJECT_ROOT>/scripts/k6/run-benchmark.sh
```

각 케이스마다 전략 전환 → DB 초기화 → k6 실행 순서로 진행된다. 전체 소요 시간은 상당히 길다.

---

## 단계 3: 결과 요약

```bash
bash "${PROJECT_ROOT}/.claude/skills/k6-summarize/scripts/summarize.sh" "${PROJECT_ROOT}/scripts/k6/results"
```

출력 컬럼:

| 컬럼 | 설명 |
|------|------|
| TPS | confirm_requests rate — 순수 결제 확인 처리량 |
| HTTP med / p95 | HTTP 응답 시간 중앙값 / 95퍼센타일 |
| E2E med / p95 | 요청 시작~완료까지 (outbox는 폴링 완료 포함) |
| 에러율 | HTTP 실패 비율 |
| Dropped | 처리 못 한 이터레이션 수 |

요약 출력 후 케이스 간 주목할 차이(TPS 증감, E2E 레이턴시 변화, 에러율 이상 등)를 간략히 해석해준다.
