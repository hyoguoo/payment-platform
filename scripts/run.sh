#!/bin/bash

# 공통 함수 로드
source "$(dirname "$0")/common.sh"

print_info "🚀 Starting Payment Platform"
echo "================================================"

# Docker 체크
check_docker

cd docker/compose

# .env.secret 파일 확인
if [ ! -f .env.secret ]; then
    print_error "❌ .env.secret 파일이 없습니다!"
    echo ""
    echo "다음 명령어를 실행하여 .env.secret 파일을 생성하세요:"
    echo "  cd docker/compose"
    echo "  cp .env.secret.example .env.secret"
    echo "  # .env.secret 파일을 열어 실제 TOSS_SECRET_KEY를 입력하세요"
    exit 1
fi

print_warning "Cleaning up existing containers..."
docker-compose down -v 2>/dev/null

print_warning "Building application..."
docker-compose build app

print_warning "Starting all services..."
docker-compose up -d

print_section "Waiting for services (30 seconds)..."
sleep 30

# 서비스 상태 확인
print_section "Service Status:"
services=("mysql" "elasticsearch" "kibana" "logstash" "app" "prometheus" "grafana")
for service in "${services[@]}"; do
    if docker ps | grep -q "payment-$service"; then
        print_info "✅ $service is running"
    else
        print_error "❌ $service failed"
    fi
done

# URL 및 명령어 출력
print_service_urls
print_log_commands

cd ../..
