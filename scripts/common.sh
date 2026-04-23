#!/bin/bash

# compose-up.sh 공용 색상/헬퍼. 단일 consumer.

export RED='\033[0;31m'
export GREEN='\033[0;32m'
export YELLOW='\033[1;33m'
export BLUE='\033[0;34m'
export NC='\033[0m'

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

check_docker() {
    if ! docker info > /dev/null 2>&1; then
        print_error "❌ Docker is not running!"
        echo "Please start Docker and try again."
        exit 1
    fi
    print_info "✅ Docker is running"
}
