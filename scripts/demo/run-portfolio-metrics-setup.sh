#!/bin/bash

# ============================================================================
# Portfolio Metrics Setup Script
# 포트폴리오 스크린샷용 메트릭 데이터 자동 설정
#
# 이 스크립트는:
# 1. Docker 환경이 실행 중인지 확인
# 2. DB에 메트릭 데이터 삽입
# 3. 메트릭 수집 대기 (20초)
# 4. Prometheus 메트릭 검증
# 5. Grafana 대시보드 URL 출력
# ============================================================================

set -e  # 에러 발생 시 즉시 종료

# 색상 정의
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 스크립트 디렉토리
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"

echo -e "${BLUE}=====================================================================${NC}"
echo -e "${BLUE}   Portfolio Metrics Setup${NC}"
echo -e "${BLUE}   포트폴리오용 메트릭 데이터 자동 설정${NC}"
echo -e "${BLUE}=====================================================================${NC}"
echo ""

# ============================================================================
# 1. 환경 확인
# ============================================================================
echo -e "${YELLOW}[1/6] Checking Docker environment...${NC}"

# MySQL 컨테이너 확인
if ! docker ps | grep -q "mysql"; then
    echo -e "${RED}❌ MySQL container is not running!${NC}"
    echo -e "${YELLOW}Please start the environment first:${NC}"
    echo -e "  cd $PROJECT_ROOT && ./scripts/run.sh"
    exit 1
fi

# Application 컨테이너 확인
if ! docker ps | grep -q "payment-app"; then
    echo -e "${RED}❌ Application container is not running!${NC}"
    echo -e "${YELLOW}Please start the environment first:${NC}"
    echo -e "  cd $PROJECT_ROOT && ./scripts/run.sh"
    exit 1
fi

echo -e "${GREEN}✅ Docker environment is ready${NC}"
echo ""

# ============================================================================
# 2. 기존 데이터 확인 (선택사항)
# ============================================================================
echo -e "${YELLOW}[2/6] Checking existing data...${NC}"

EXISTING_COUNT=$(docker exec payment-mysql mysql -uroot -ppayment123! payment-platform -sN -e "SELECT COUNT(*) FROM payment_event;")
echo -e "  Current payment_event count: ${EXISTING_COUNT}"

if [ "$EXISTING_COUNT" -gt 0 ]; then
    echo -e "${YELLOW}⚠️  Existing data found. This script will ADD new data.${NC}"
    echo -e "${YELLOW}   To start fresh, consider clearing the database first.${NC}"
    echo -e ""
    read -p "Continue? (y/n) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${RED}Aborted by user.${NC}"
        exit 1
    fi
fi

echo ""

# ============================================================================
# 3. 메트릭 데이터 삽입
# ============================================================================
echo -e "${YELLOW}[3/6] Inserting portfolio metrics data...${NC}"
echo -e "  This will insert:"
echo -e "    - 30 DONE payments"
echo -e "    - 5 READY payments (various ages)"
echo -e "    - 10 IN_PROGRESS payments"
echo -e "    - 3 FAILED payments"
echo -e "    - 2 EXPIRED payments"
echo -e "    - 1 UNKNOWN payment"
echo -e "    - Health alert triggers (stuck, max retry, near expiration)"
echo -e "    - Payment history records for transitions"
echo ""

# SQL 파일 실행
if [ -f "$SCRIPT_DIR/insert-demo-metrics-data.sql" ]; then
    docker exec -i payment-mysql mysql -uroot -ppayment123! payment-platform < "$SCRIPT_DIR/insert-demo-metrics-data.sql"
    echo -e "${GREEN}✅ Data inserted successfully${NC}"
else
    echo -e "${RED}❌ SQL file not found: $SCRIPT_DIR/insert-demo-metrics-data.sql${NC}"
    exit 1
fi

echo ""

# ============================================================================
# 4. 데이터 검증
# ============================================================================
echo -e "${YELLOW}[4/6] Verifying inserted data...${NC}"

# 상태별 개수 확인
echo -e "\n${BLUE}Payment Status Distribution:${NC}"
docker exec payment-mysql mysql -uroot -ppayment123! payment-platform -e "
SELECT status, COUNT(*) as count
FROM payment_event
GROUP BY status
ORDER BY
    CASE status
        WHEN 'READY' THEN 1
        WHEN 'IN_PROGRESS' THEN 2
        WHEN 'DONE' THEN 3
        WHEN 'FAILED' THEN 4
        WHEN 'EXPIRED' THEN 5
        WHEN 'UNKNOWN' THEN 6
    END;
"

# Health 조건 확인
echo -e "\n${BLUE}Health Metrics Activation:${NC}"
docker exec payment-mysql mysql -uroot -ppayment123! payment-platform -e "
SELECT
    'Stuck in Progress (>5min)' as metric,
    COUNT(*) as count
FROM payment_event
WHERE status = 'IN_PROGRESS'
  AND executed_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)

UNION ALL

SELECT
    'Unknown Status' as metric,
    COUNT(*) as count
FROM payment_event
WHERE status = 'UNKNOWN'

UNION ALL

SELECT
    'Max Retry Reached (>=5)' as metric,
    COUNT(*) as count
FROM payment_event
WHERE retry_count >= 5

UNION ALL

SELECT
    'Near Expiration (25-30min)' as metric,
    COUNT(*) as count
FROM payment_event
WHERE status IN ('READY', 'IN_PROGRESS')
  AND created_at BETWEEN DATE_SUB(NOW(), INTERVAL 30 MINUTE)
                     AND DATE_SUB(NOW(), INTERVAL 25 MINUTE);
"

echo -e "${GREEN}✅ Data verification complete${NC}"
echo ""

# ============================================================================
# 5. 메트릭 수집 대기
# ============================================================================
echo -e "${YELLOW}[5/6] Waiting for metrics collection...${NC}"
echo -e "  State metrics polling interval: 10 seconds"
echo -e "  Health metrics polling interval: 10 seconds"
echo -e "  Waiting 20 seconds for next collection cycle..."
echo ""

for i in {20..1}; do
    echo -ne "  ${YELLOW}⏳ $i seconds remaining...${NC}\r"
    sleep 1
done
echo -e "${GREEN}✅ Metrics should be collected now                     ${NC}"
echo ""

# ============================================================================
# 6. Prometheus 메트릭 확인
# ============================================================================
echo -e "${YELLOW}[6/6] Verifying Prometheus metrics...${NC}"

# State metrics 확인
echo -e "\n${BLUE}Checking payment_state_current:${NC}"
STATE_METRIC=$(curl -s "http://localhost:9090/api/v1/query?query=payment_state_current" | grep -o '"result":\[' | wc -l)
if [ "$STATE_METRIC" -gt 0 ]; then
    echo -e "${GREEN}✅ payment_state_current is active${NC}"
    curl -s "http://localhost:9090/api/v1/query?query=payment_state_current" | \
        grep -o '"status":"[^"]*","value":\[[^]]*\]' | head -5
else
    echo -e "${YELLOW}⚠️  payment_state_current not yet visible (may need more time)${NC}"
fi

# Health metrics 확인
echo -e "\n${BLUE}Checking payment_health_* metrics:${NC}"
HEALTH_METRICS=$(curl -s "http://localhost:9090/api/v1/query?query=payment_health_stuck_in_progress" | grep -o '"result":\[' | wc -l)
if [ "$HEALTH_METRICS" -gt 0 ]; then
    echo -e "${GREEN}✅ payment_health_stuck_in_progress is active${NC}"
else
    echo -e "${YELLOW}⚠️  Health metrics not yet visible (polling may not have occurred)${NC}"
fi

echo ""

# ============================================================================
# 완료 및 다음 단계 안내
# ============================================================================
echo -e "${GREEN}=====================================================================${NC}"
echo -e "${GREEN}   ✅ Portfolio Metrics Setup Complete!${NC}"
echo -e "${GREEN}=====================================================================${NC}"
echo ""
echo -e "${BLUE}📊 Next Steps for Screenshot Capture:${NC}"
echo ""
echo -e "1. Open Grafana Dashboard (Kiosk Mode):"
echo -e "   ${BLUE}http://localhost:3000/d/payment-operations?kiosk=tv&from=now-1h&to=now&refresh=10s${NC}"
echo ""
echo -e "2. Verify all metrics are visible:"
echo -e "   - Current Payment State (all status shown)"
echo -e "   - Health Alerts (all 4 cards should show values > 0)"
echo -e "   - Toss API Monitoring (will populate with real API calls)"
echo -e "   - Transition Trends (history data visible)"
echo ""
echo -e "3. Capture screenshots using:"
echo -e "   - Browser: Cmd+Shift+4 (Mac) or Win+Shift+S (Windows)"
echo -e "   - Or automated: node scripts/capture-screenshots.js"
echo ""
echo -e "4. If some metrics are still 0:"
echo -e "   - Wait another 10-20 seconds for next polling cycle"
echo -e "   - Refresh Grafana dashboard (F5)"
echo -e "   - Check Prometheus: http://localhost:9090"
echo ""
echo -e "${BLUE}📁 Screenshots should be saved to:${NC}"
echo -e "   $PROJECT_ROOT/screenshots/"
echo ""
echo -e "${YELLOW}💡 Tip: For Transition metrics to be more active,${NC}"
echo -e "${YELLOW}   consider making a few real API calls:${NC}"
echo -e "   curl -X POST http://localhost:8080/checkout -H 'Content-Type: application/json' -d '{...}'"
echo ""
echo -e "${GREEN}Happy screenshotting! 📸${NC}"
echo ""
