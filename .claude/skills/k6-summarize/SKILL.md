---
name: k6-summarize
description: k6 부하 테스트 결과를 요약 테이블로 출력한다. "결과 요약", "벤치마크 결과 보여줘", "k6 결과", "TPS 비교", "부하 테스트 결과" 등 부하 테스트 결과 확인이 필요할 때 반드시 사용한다.
---

## 목적

스킬 디렉토리 내 `summarize.sh`를 실행해 `scripts/k6/results/`의 JSON 결과 파일들을 파싱하고, 케이스별 TPS·HTTP 레이턴시·E2E 레이턴시·에러율·드롭 이터레이션을 한 표로 출력한다.

## 실행 방법

`summarize.sh`는 스킬 디렉토리(`.claude/skills/k6-summarize/`)에 위치한다. 스크립트 내부에서 `$(dirname "$0")/results`를 기본 경로로 사용하므로, **results 경로는 반드시 명시적으로 전달**해야 한다.

```bash
# PROJECT_ROOT 확인
PROJECT_ROOT=$(git rev-parse --show-toplevel)

# results 경로를 명시적으로 지정해 실행
bash "${PROJECT_ROOT}/.claude/skills/k6-summarize/scripts/summarize.sh" "${PROJECT_ROOT}/scripts/k6/results"
```

## 출력 해석

| 컬럼 | 설명 |
|------|------|
| 케이스 | JSON 파일명 (전략 + 부하 조건) |
| TPS | `confirm_requests` rate — 순수 결제 확인 처리량 |
| HTTP med / p95 | HTTP 응답 시간 중앙값 / 95퍼센타일 |
| E2E med / p95 | 요청 시작~완료까지 전체 시간 (outbox는 폴링 완료 포함) |
| 에러율 | HTTP 실패 비율 |
| Dropped | 처리 못 한 이터레이션 수 |

## 흐름

1. `git rev-parse --show-toplevel`로 프로젝트 루트를 확인한다.
2. `bash "${PROJECT_ROOT}/.claude/skills/k6-summarize/scripts/summarize.sh" "${PROJECT_ROOT}/scripts/k6/results"`를 실행한다.
3. 출력된 표를 그대로 사용자에게 보여준다.
4. 필요하면 케이스 간 차이(TPS 증감, E2E 레이턴시 변화 등)를 간략히 해석해준다.