# 캡처 — 화면별 함정과 설정

캡처는 단순해 보이지만 화면마다 걸리는 게 다르다. 아래는 전부 실제로 부딪혀 확인한 것들이다.

## 왜 헤드리스 2배인가

같은 화면을 두 방식으로 찍어 비교한 결과다.

| | 헤드리스 2배 | 브라우저 조작 캡처 |
|---|---|---|
| 해상도 | 2880×1800 | 1387×886 |
| 형식 | PNG 무손실 | JPEG 손실 압축 |
| 기타 | — | 마우스 커서가 찍힘, 임시 폴더에 저장 |

픽셀이 4배 차이 난다. 그래프선이나 축 라벨처럼 얇은 요소가 많은 관측 화면일수록
격차가 벌어지므로 기본은 헤드리스다. 브라우저 조작 캡처는 **로그인 세션이 필요한 화면에만** 쓴다.

## 반드시 서버 주소로 찍는다

정적 HTML 을 `file://` 로 열면 `/style.css` 같은 절대 경로가 디스크 루트를 가리켜
CSS 와 테마 스크립트가 통째로 빠진 화면이 찍힌다. 렌더 엔진은 일반 Chrome 과 같으므로
서버로 띄운 주소만 쓰면 실제 화면 그대로 나온다.

## Grafana — 설정 없이는 로그인 화면만 찍힌다

Chrome 은 보안상 주소에 넣은 계정 정보(`http://admin:pw@...`)를 **무시한다**.
curl 로는 통과하기 때문에 "인증 되는데 왜 로그인 화면이지?" 하고 헤매기 쉽다.

익명 조회를 여는 override 파일을 만들어 grafana 만 재기동한다.

```yaml
# docker/docker-compose.live-drill.yml
services:
  grafana:
    environment:
      - GF_AUTH_ANONYMOUS_ENABLED=true
      - GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer
      # Explore(트레이스·로그 조회)는 Viewer 에게 기본 차단이라 이것도 필요하다
      - GF_USERS_VIEWERS_CAN_EDIT=true
```

```bash
docker compose -f docker/docker-compose.infra.yml \
  -f docker/docker-compose.observability.yml \
  -f docker/docker-compose.live-drill.yml up -d --no-deps grafana
```

실측이 끝나면 override 없이 같은 명령을 돌려 인증 상태로 되돌린다.
**스택을 다시 올리면 이 override 가 빠지므로 재적용해야 한다.**

### 대시보드는 패널 단독으로

`?viewPanel=<패널id>&kiosk` 를 붙이면 사이드바·헤더 없이 패널 하나만 전체화면으로 나온다.
전경이 필요하면 `?kiosk` 만 붙이고 창 높이를 3000 정도로 준다.

### 시간 범위를 장면에 맞춘다

DB 를 초기화해도 **Prometheus 데이터는 지워지지 않는다.** 기본 범위로 찍으면 이전 실측
흔적이 그래프에 섞여 "전부 0인 시작 상태"가 성립하지 않는다. `?from=now-2m&to=now` 처럼
그 장면에 맞는 범위를 매번 지정한다.

## Explore 로 트레이스·로그 조회 화면 찍기

주소에 조회 조건을 담아 그대로 캡처할 수 있다. `panes` 파라미터에 JSON 을 넣고
URL 인코딩한다. 데이터소스 uid 는 아래로 확인한다.

```bash
curl -s 'http://localhost:3000/api/datasources' -u admin:admin123 \
  | python3 -c "import json,sys;[print(d['name'],d['uid']) for d in json.load(sys.stdin)]"
```

```python
panes = {"a": {"datasource": "<uid>",
               "queries": [{"refId": "A",
                            "datasource": {"type": "tempo", "uid": "<uid>"},
                            "queryType": "traceql", "query": "<traceId>"}],
               "range": {"from": "now-1h", "to": "now"}}}
url = "http://localhost:3000/explore?schemaVersion=1&orgId=1&panes=" + urllib.parse.quote(json.dumps(panes, separators=(',', ':')))
```

로그 조회는 `type`/`uid` 를 Loki 것으로 바꾸고 `expr` 에 쿼리를 넣는다.

```
{"editorMode": "code", "queryType": "range", "expr": "{application=\"pg-service\"} |= `<orderId>`"}
```

트레이스 워터폴은 구간 이름이 길어 `CAPTURE_WIDTH=1800` 이상을 권한다.

## 통지 채널 — 유일하게 브라우저가 필요하다

로그인 세션이 있어야 해서 헤드리스로 못 찍는다. 사용자의 브라우저를 빌려 캡처한다.
알림이 도착한 사실을 보이는 화면이라 화질 요구가 낮아 손해가 적다.

광고나 안내 배너가 상단에 뜨면 리포트에 그대로 남는다. **닫기 전에 사용자에게 묻는다** —
사용자 계정 화면을 건드리는 일이다.

## 확인 창을 조심한다

관리자 화면의 위험한 조작(격리 종결, 재주입)은 제출 전에 확인 창을 띄운다.
이 창이 뜨면 **브라우저 조작이 전부 멈춘다** — 화면 캡처도, 클릭도 안 된다.

두 갈래로 대응한다.

- **화면에 남기고 싶으면** 사용자에게 직접 확인을 누르게 한다. 입력한 사유와 경고 문구가
  함께 담긴 화면은 오히려 좋은 증거가 된다.
- **조작만 하면 되면** 폼의 요청 경로를 찾아 API 로 직접 호출한다. 확인 창을 건너뛴다.

## 화면별 창 높이

아래 여백이 남지 않게 맞춘다. 실제로 써서 맞았던 값이다.

| 화면 | 높이 |
|---|---|
| 포털·관리자 목록 | 800~1200 |
| 관리자 상세 | 1400~1500 |
| 알람 규칙 목록 | 1200~1600 |
| 통지 라우팅 | 1000~1200 |
| 트레이스 워터폴 | 1300 (너비 1800) |
| 대시보드 전경 | 2500~3000 |

## 캡처 후 확인

찍은 파일을 그대로 믿지 말고 **열어서 본다.** 로그인 화면이 찍혔거나, 데이터가 없거나,
시간 범위가 어긋난 경우가 실제로 자주 나온다. 특히 첫 캡처는 반드시 확인한다.
