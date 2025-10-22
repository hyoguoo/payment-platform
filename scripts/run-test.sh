#!/bin/bash

# 공통 함수 로드
source "$(dirname "$0")/common.sh"

print_info "🧪 Running Tests with Testcontainers"
echo "================================================"

# .env.secret 파일 로드 (루트 디렉토리)
ENV_SECRET_FILE="$(dirname "$0")/../.env.secret"
if [ -f "$ENV_SECRET_FILE" ]; then
    print_info "Loading test environment variables from .env.secret"
    set -a
    source "$ENV_SECRET_FILE"
    set +a
else
    print_warning "⚠️  .env.secret 파일이 없습니다."
    print_warning "테스트 키를 환경변수로 설정하거나 .env.secret 파일을 생성하세요."
    echo ""
fi

# Docker 체크
check_docker

# Testcontainers 정리
cleanup_testcontainers

# 테스트 실행
print_warning "Starting test execution..."
echo ""

if ./gradlew clean test; then
    echo ""
    print_info "✅ All tests passed successfully!"
    
    # 테스트 커버리지 생성
    echo ""
    print_warning "Generating test coverage report..."
    ./gradlew jacocoTestReport
    
    echo ""
    print_section "📊 Reports generated:"
    echo "  Test Report:     build/reports/tests/test/index.html"
    echo "  Coverage Report: build/reports/jacoco/test/html/index.html"
else
    echo ""
    print_error "❌ Some tests failed!"
    echo "Check the test report for details:"
    echo "  build/reports/tests/test/index.html"
    exit 1
fi

# Testcontainers 정리
echo ""
cleanup_testcontainers

print_info "✅ Test execution completed"
