#!/bin/bash

# 색상 정의
export RED='\033[0;31m'
export GREEN='\033[0;32m'
export YELLOW='\033[1;33m'
export BLUE='\033[0;34m'
export NC='\033[0m' # No Color

# 함수: 메시지 출력
print_info() {
    echo -e "${GREEN}$1${NC}"
}

print_warning() {
    echo -e "${YELLOW}$1${NC}"
}

print_error() {
    echo -e "${RED}$1${NC}"
}

print_section() {
    echo -e "${BLUE}$1${NC}"
}

# 함수: Docker 체크
check_docker() {
    if ! docker info > /dev/null 2>&1; then
        print_error "❌ Docker is not running!"
        echo "Please start Docker and try again."
        exit 1
    fi
    print_info "✅ Docker is running"
}

# 함수: 서비스 헬스체크
wait_for_service() {
    local service_name=$1
    local check_command=$2
    local max_attempts=${3:-30}
    
    print_warning "Waiting for $service_name to be ready..."
    for i in $(seq 1 $max_attempts); do
        if eval $check_command > /dev/null 2>&1; then
            print_info "✅ $service_name is ready!"
            return 0
        fi
        echo -n "."
        sleep 1
    done
    print_error "❌ $service_name failed to start"
    return 1
}

# 함수: Testcontainers 정리
cleanup_testcontainers() {
    print_warning "Cleaning up test containers..."
    docker ps -a --filter "label=org.testcontainers=true" -q | xargs -r docker rm -f 2>/dev/null || true
}

# 함수: 서비스 URL 출력
print_service_urls() {
    echo ""
    print_section "=== Service URLs ==="
    echo "Application:     http://localhost:8080"
    echo "API Documentation: http://localhost:8080/doc.html"
    
    # 추가 서비스가 있는 경우
    if docker ps | grep -q payment-elasticsearch; then
        echo "Elasticsearch:  http://localhost:9200"
    fi
    if docker ps | grep -q payment-kibana; then
        echo "Kibana:         http://localhost:5601"
    fi
    if docker ps | grep -q payment-grafana; then
        echo "Grafana:        http://localhost:3000 (admin/admin123)"
    fi
    if docker ps | grep -q payment-prometheus; then
        echo "Prometheus:     http://localhost:9090"
    fi
}

# 함수: 로그 명령어 출력
print_log_commands() {
    echo ""
    print_warning "Useful commands:"
    echo "  View logs:        docker-compose logs -f"
    echo "  Stop services:    docker-compose down"
    echo "  Clean everything: docker-compose down -v"
}
