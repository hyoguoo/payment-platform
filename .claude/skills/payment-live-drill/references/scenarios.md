# 장면별 구동

각 장면을 원하는 시점에 일으키는 방법과, 그때 무엇을 찍을지.

## 시나리오 장치 — 결제 키 접두어

실 벤더 없이 실패를 원하는 시점에 일으키려면 모의 벤더에 분기를 심어야 한다.
`FakePgGatewayStrategy.confirm()` 에 결제 키 접두어 분기를 넣는다.

| 접두어 | 벤더 응답 | 최종 상태 |
|---|---|---|
| `fake-` | 첫 호출 성공 | 완료 |
| `fake-fail-` | 확정 실패 | 실패 (+보상) |
| `fake-retry-` | 매 호출 재시도 가능 실패 | 시도 소진 → 격리 |
| `fake-flaky-` | 두 번 실패 후 세 번째 승인 | 재시도 자가 회복 |

**접두어 판정은 중복 승인 판정보다 먼저 둔다.** 뒤에 두면 첫 호출이 처리 기록에 남아
자기루프 재호출이 곧바로 중복으로 흡수되고, 시도 횟수가 소진되지 않아 격리까지 가지 못한다.

시나리오 실패도 응답 시간과 실패 횟수를 기록해야 벤더 패널이 실측대로 움직인다.

심은 뒤 PG 서비스만 다시 빌드해 올린다.

```bash
./gradlew :pg-service:bootJar --console=plain
set -a; source .env.secret; set +a
docker compose -f docker/docker-compose.infra.yml -f docker/docker-compose.apps.yml \
  -f docker/docker-compose.smoke.yml up -d --build --force-recreate --no-deps pg-service
```

이 수정은 실측 전용이므로 실측 브랜치에만 커밋한다.

## 결제 태우기

**추적 헤더를 주입하지 않는다.** 그냥 호출하면 승인 트레이스 하나에 메시지 왕복 전체가
담긴다(구간 9개). 주문 생성은 별개 트레이스(구간 3개)로 갈리지만 볼 게 없다.
헤더를 주입해 억지로 합치면 무관한 요청이 한 트레이스에 묶여 시간축만 늘어나고
실제 운영 모습과 달라진다.

```bash
CO=$(curl -s -X POST http://localhost:8090/api/v1/payments/checkout \
  -H "Content-Type: application/json" -H "Idempotency-Key: drill-$(date +%s)" \
  -d '{"userId":1,"gatewayType":"TOSS","orderedProductList":[{"productId":1,"quantity":1}]}')
ORDER=$(echo "$CO" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['orderId'])")

curl -s -X POST http://localhost:8090/api/v1/payments/confirm \
  -H "Content-Type: application/json" \
  -d "{\"userId\":1,\"orderId\":\"$ORDER\",\"amount\":1000.00,\"paymentKey\":\"fake-$ORDER\",\"gatewayType\":\"TOSS\"}"

curl -s "http://localhost:8090/api/v1/payments/$ORDER/status"
```

## 워터폴 대상 트레이스 찾기

승인 트레이스를 검색으로 고른다.

```bash
curl -s "http://localhost:3200/api/search?q=%7Bresource.service.name%3D%22gateway%22%20%26%26%20name%3D%22http%20post%22%7D&limit=10"
```

나온 것 중 구간 9개짜리가 승인, 3개짜리가 주문 생성이다. 구간 수는 이렇게 센다.

```bash
curl -s "http://localhost:3200/api/traces/<traceId>" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(sum(len(s.get('spans',[])) for b in d.get('batches',[]) for s in b.get('scopeSpans',[])))
"
```

## 장면 1 — 성공

`fake-<orderId>` 로 태운다. 몇 초 만에 완료된다.

찍을 것: 관리자 목록의 완료 배지, 상세의 이력 세 단계, 승인 트레이스 워터폴, 지표 변화.
상품 서비스 로그에 재고 확정 기록(`98 -> 97`)이 남으므로 그것도 화면으로 남긴다.

## 장면 2 — 실패와 보상

`fake-fail-` 로 태운다. 벤더가 거절하면 잡아둔 재고를 되돌린 뒤 실패로 끝난다.

찍을 것: 실패 배지와 사유, 주문 실패, 되돌림 로그 화면.

되돌림은 캐시 안에서 일어나므로 상품 API(DB 기준)로는 확인되지 않는다.
`STOCK_COMPENSATION_DONE` 로그를 조회해 화면으로 남긴다.

## 장면 3 — 재시도 소진과 격리

`fake-retry-` 로 태운다. 시도 간격이 벌어지며 1~2분 뒤 격리된다.

**결제와 추적을 한 명령으로 시작한다.** 따로 하면 첫 시도 한두 번이 순식간에 지나가
기록에 안 남는다.

```bash
# confirm 호출 직후 같은 스크립트에서 바로 폴링 시작
for i in $(seq 1 60); do
  echo "$(date '+%H:%M:%S')  $(docker exec payment-mysql-pg mysql -uroot -ppayment123 -N \
    -e "SELECT CONCAT(status,' attempt=',attempt) FROM pg.pg_inbox WHERE order_id='$ORDER';" 2>/dev/null | grep -v Warning)"
  sleep 2
done
```

찍을 것: 승인중에 머무는 상세, 격리 확정 상세와 사유, 시도 기록, 지표(격리 건수·DLQ),
알람 발화 → 통지 라우팅 → 채널 도착, 그리고 운영자 복구.

**재시도 흔적은 로그로도 남는다.** PG 서비스 로그를 그 주문으로 조회하면 실패마다
다음 시도 번호와 예약 시각이 찍혀 있고 소진·격리까지 이어진다. 관리자 화면에
시도 횟수가 없으므로 이 조회 화면이 근거가 된다.

### 운영자 복구

격리 상세에 안전 종결과 재주입 수단이 있다. 종결은 사유 입력이 필수이고 확인 창이 뜬다.
화면에 남기려면 사용자에게 확인을 누르게 하고, 조작만 필요하면 API 로 호출한다.

```
POST /admin/payments/events/{eventId}/resolve-quarantine   (orderId, reason)
```

종결 뒤 이력은 네 단계가 되고 잡아둔 재고도 함께 풀린다.

## 장면 4 — 재시도 자가 회복

`fake-flaky-` 로 태운다. 두 번 실패 후 세 번째에 승인되어 격리 없이 완료된다.

**여기서 트레이스가 갈린다.** 첫 승인 요청 트레이스는 벤더 실패 지점에서 끊기고,
재시도는 아웃박스 폴링 워커를 뿌리로 하는 새 트레이스로 남는다. 재시도를 주도하는 주체가
원래 요청이 아니라 주기적으로 깨어나는 워커이기 때문이다. 두 장을 나란히 찍어
"끊긴 첫 요청"과 "워커가 이어받아 끝낸 재시도"를 보여준다.

## 장면 5 — 인프라 장애와 알람

컨테이너를 내렸다 올리며 알람이 발화 → 통지 → 해소되는 것을 확인한다.

| 유발 | 알람 | 발화까지 |
|---|---|---|
| 격리 여러 건 동시 | DLQ 적체 2종 | 약 1분 |
| `docker stop payment-redis-stock` | 의존성 다운 | 즉시 |
| `docker stop docker-user-service-1` | 서비스 다운 | 약 1분 |
| `docker stop payment-kafka` | 브로커 가용성 | 약 2분 |
| `docker stop payment-mysql-payment` | 의존성 다운 + 폴러 지연 | 25초 / 1분 |

각 장애마다 **발화 확인 → 캡처 → 복구 → 해소 확인** 순으로 간다. 알람 상태는
`/api/v1/alerts` 로 폴링한다(`pending` 을 거쳐 `firing` 이 된다).

재고 캐시를 내린 채 승인하면 요청자에게는 일시적 장애로 거절되지만 **내부에서는
격리로 남는다.** 재고를 확인할 수 없으니 진행하지 않되 흔적은 보존하는 구조다.

캐시를 복구한 뒤에는 재고를 다시 맞춘다(`bash scripts/seed-stock.sh`).

마지막으로 모든 장애를 복구한 뒤 결제를 한 건 더 태워 **시스템이 되살아난 것**을 남긴다.

## 라이브로 못 만드는 알람

브로커가 하나뿐인 환경에서는 코디네이터 관련 규칙이 발화하지 않는다.
억지로 만들려 하지 말고, 리포트에 "왜 라이브로 재현되지 않는지 + 어떻게 검증했는지"를
정직하게 적는다.
