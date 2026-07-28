# 무대 세팅

실측을 시작할 수 있는 상태로 만들고, 시작 상태를 남긴다.

## 스택 기동

```bash
bash scripts/compose-up.sh --mode fake
```

한 번으로 인프라·앱·관측성이 모두 모의 벤더 백본으로 뜬다(빌드 포함 5~10분).
실 벤더 모드로 올린 뒤 PG 서비스만 따로 재기동할 필요 없다.

**깨끗한 시작 상태가 필요하면** `--reset-db` 를 붙인다. DB 볼륨을 지우고 마이그레이션이
처음부터 돌아 결제 0건에서 출발한다. 다만 **Prometheus 데이터는 지워지지 않으므로**
지표 캡처는 시간 범위를 좁혀 찍는다.

기동 직후 확인:

```bash
docker ps --format '{{.Names}}\t{{.Status}}' | sort
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8090/admin/payments/events
```

## 캡처 권한

스택을 새로 올리면 Grafana 익명 조회 override 가 빠진다. `references/capture.md` 의
Grafana 절을 보고 다시 적용한다. `curl -s -o /dev/null -w "%{http_code}" http://localhost:3000/explore`
가 200 이면 준비된 것이다.

## 시드

운영 프로파일이 초기 데이터를 제외하므로 사용자·상품·재고가 없다(둘 다 404).
직접 넣는다.

```bash
docker exec payment-mysql-user mysql -uroot -ppayment123 user \
  -e "INSERT IGNORE INTO \`user\`(id,email) VALUES(1,'smoke@test.com');"
docker exec payment-mysql-product mysql -uroot -ppayment123 product -e "
  INSERT IGNORE INTO product(id,name,price,description,seller_id) VALUES(1,'Smoke Product',1000.00,'smoke seed',1);
  INSERT IGNORE INTO stock(product_id,quantity) VALUES(1,100);"
bash scripts/seed-stock.sh
```

확인:

```bash
curl -s -o /dev/null -w "user=%{http_code} " http://localhost:8090/api/v1/users/1
curl -s -o /dev/null -w "product=%{http_code}\n" http://localhost:8090/api/v1/products/1
docker exec payment-redis-stock redis-cli GET "stock:1"
```

앱이 막 떠서 503 이 나오면 잠시 기다린다. **404 는 정상** — 시드 전이라는 뜻이다.

## 시작 상태 캡처

이후 장면의 변화가 실측으로 만든 것임을 보이려면 출발선이 필요하다.

| 화면 | 주소 |
|---|---|
| 포털 | `localhost:8090/` |
| 관리자 이벤트 목록 | `localhost:8090/admin/payments/events` |
| 결제 지표 대시보드 | `localhost:3000/d/payment-business-d001/business-dashboard?from=now-2m&to=now&kiosk` |
| 시스템 지표 대시보드 | `localhost:3000/d/payment-system-d001/system-dashboard?from=now-5m&to=now&kiosk` |
| 알람 규칙 | `localhost:9090/alerts` |
| 통지 라우팅 | `localhost:9093/#/alerts` |
| 서비스 레지스트리 | `localhost:8761/` |
| 통지 채널 | 사용자 브라우저로 |

**찍은 뒤 열어서 확인한다.** 지표가 0 이 아니거나 알람이 잠잠하지 않으면 시간 범위를
조정하거나, 이전 실측 흔적이 가라앉을 때까지 기다린다.

서비스 레지스트리 화면에는 인스턴스 수가 적을 때 뜨는 빨간 보호 모드 안내가 있다.
장애가 아니므로 리포트에 넣을 때 주석을 달아 오해를 막는다.

## 자주 쓰는 조회

```bash
# 결제 이벤트 상태
docker exec payment-mysql-payment mysql -uroot -ppayment123 --default-character-set=utf8mb4 -N \
  -e "SELECT id,order_id,status,IFNULL(status_reason,'-') FROM \`payment-platform\`.payment_event ORDER BY id DESC LIMIT 5;"

# PG 수신함 시도 횟수
docker exec payment-mysql-pg mysql -uroot -ppayment123 -N \
  -e "SELECT order_id,status,attempt FROM pg.pg_inbox ORDER BY id DESC LIMIT 5;"

# 재고 (캐시가 실시간, DB는 확정분)
docker exec payment-redis-stock redis-cli GET "stock:1"
docker exec payment-mysql-product mysql -uroot -ppayment123 -N \
  -e "SELECT quantity FROM product.stock WHERE product_id=1;"

# 알람 상태
curl -s 'http://localhost:9090/api/v1/alerts' | python3 -c "
import json,sys
for a in json.load(sys.stdin)['data']['alerts']:
    print(a['labels'].get('alertname'), a['state'], a['labels'].get('component',''))
"
```

한글이 깨져 보이면 mysql 에 `--default-character-set=utf8mb4` 를 붙인다.
