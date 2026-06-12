# Observability Load 가이드

> 관측성 대시보드를 "실수치처럼" 채우는 데모 부하 생성기.
> 스크립트: `scripts/smoke/observability-load.sh`
>
> 부하를 걸어놓고 **Grafana 어디서 무엇을 보나**는 [`observability-walkthrough.md`](observability-walkthrough.md) 참고.

## 목적

fake 모드 스택에 `checkout → confirm` 트래픽을 지속 발생시켜 Grafana 대시보드(비즈니스/시스템)·트레이스·서비스 그래프를 살아있는 수치로 채운다. **실 PG(Toss/NicePay) 호출은 0** — `FakePgGatewayStrategy` 가 우회한다.

## 선행 조건

fake 모드로 스택이 떠 있어야 한다.

```bash
bash scripts/compose-up.sh --mode fake     # 인프라 + 앱 + 관측성 + 재고 시드
```

Grafana: http://localhost:3000 (admin / admin123)

## 빠른 시작

```bash
bash scripts/smoke/observability-load.sh                       # constant 3rps 무한 (Ctrl-C 종료)
bash scripts/smoke/observability-load.sh --profile wave --rps-min 2 --rps-max 20 --period 90
```

## 커스터마이즈 4축

| 축 | 옵션 | 설명 |
|---|---|---|
| **부하 곡선** | `--profile constant\|ramp\|wave\|spike` + `--rps-min` `--rps-max` `--period` | 평탄선 대신 점증/물결/스파이크 곡선. constant 는 `--rps` 사용 |
| **요청 믹스** | `--qty-min` `--qty-max` `--users` `--products` `--gateways` | 수량 랜덤, userId/productId 풀(존재하는 값만), 벤더 비율(`TOSS,NICEPAY`) |
| **라이브 조정** | 컨트롤 파일 + 시그널 | 재시작 없이 실행 중 조정 (아래) |
| **이상 비율** | `--fail-rate X` | 기동 시 pg-service 를 `FAKE_FAIL_RATE=X` 로 자동 recreate → 일부 confirm 이 FAILED 경로로 |

### 프로파일별 곡선 (period 1주기 기준)

- `constant` — 고정 `--rps`
- `ramp` — `rps-min → rps-max` 톱니 점증 후 리셋 반복
- `wave` — `rps-min ↔ rps-max` 사인 곡선 (부드러운 출렁임)
- `spike` — 평소 `rps-min`, 주기 앞 12% 구간만 `rps-max` 폭증

## 라이브 조정 (실행 중, 재시작 없이)

```bash
# 1) 컨트롤 파일 — 매 루프 재읽기. 지원 키: PROFILE RPS RPS_MIN RPS_MAX PERIOD QTY_MIN QTY_MAX
echo 'RPS_MAX=35'    >  scripts/smoke/.obs-load.control
echo 'PROFILE=spike' >> scripts/smoke/.obs-load.control

# 2) 시그널 — 즉석 rps 배율 (PID 는 기동 로그에 출력됨)
kill -USR1 <pid>   # ×1.5
kill -USR2 <pid>   # ÷1.5
```

## 멈추기

```bash
kill -INT <pid>     # 또는 포그라운드면 Ctrl-C → 요약 출력
```

## 채워지는 지표 / 한계

- **채워짐**: funnel(발행/종결)·상태 분포·전이율·벤더 latency(success/failure)·outbox·HTTP·시스템(JVM/GC/CPU/Hikari/lag)·서비스 그래프·exemplar. `--fail-rate` 적용 시 FAILED 상태·전이도.
- **한계**: `QUARANTINED`·`DLQ` 패널은 단순 부하로 안 켜진다 — 격리는 금액 불일치/캐시 다운, DLQ 는 재시도 소진이라는 특정 조건이 필요하다. 해당 시나리오는 Phase-4 Toxiproxy 장애 주입의 몫.

## 동작 형태 (참고)

데몬·도커 서비스가 아니라 **단일 bash 프로세스**(while 루프 + curl)다. 띄운 셸 세션에 종속 — 세션이 끝나면 부하도 멈춘다(스택 컨테이너는 무관하게 유지). 재고는 `TOPUP_EVERY`(기본 40)건마다 자동 보충한다.

## 관련

- 대시보드 보는 법: [`observability-walkthrough.md`](observability-walkthrough.md)
- 스택 기동: `scripts/compose-up.sh` (`--mode fake`)
- fake 합성 latency/실패 주입: `FakePgGatewayStrategy` + `docker-compose.smoke.yml` 의 `FAKE_LATENCY_MIN/MAX`·`FAKE_FAIL_RATE`
- 대시보드 정의: `observability/grafana/dashboards/{business,system}-dashboard.json`
