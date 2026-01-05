#!/bin/bash
#
# Test runner for zkemails install.sh
#
# This script runs all tests for install.sh including:
#   - Unit tests (fast, with mocks)
#   - Integration tests (slower, uses Docker)
#
# Usage:
#   ./test/run_tests.sh              # Run all tests
#   ./test/run_tests.sh unit         # Run only unit tests
#   ./test/run_tests.sh integration  # Run only integration tests
#   ./test/run_tests.sh --help       # Show help
#
# Prerequisites:
#   - bats-core: brew install bats-core (macOS) or apt install bats (Linux)
#   - Docker (for integration tests only)
#

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# =============================================================================
# HELPER FUNCTIONS
# =============================================================================

info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

header() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

usage() {
    cat << EOF
Usage: $0 [OPTIONS] [TEST_TYPE]

Test runner for zkemails install.sh

TEST_TYPE:
  unit          Run unit tests only (fast, mocked dependencies)
  integration   Run integration tests only (slower, uses Docker)
  all           Run all tests (default)

OPTIONS:
  -h, --help    Show this help message
  -v, --verbose Enable verbose output
  -t, --tap     Output in TAP format (for CI)
  --no-docker   Skip Docker-based integration tests
  --install     Install bats-core if not present

EXAMPLES:
  $0                    # Run all tests
  $0 unit               # Run only unit tests
  $0 integration        # Run only integration tests
  $0 --tap all          # Run all tests with TAP output
  $0 --no-docker        # Run all tests except Docker ones

EOF
    exit 0
}

# =============================================================================
# DEPENDENCY CHECKS
# =============================================================================

check_bats() {
    if ! command -v bats &> /dev/null; then
        error "bats-core is not installed"
        echo ""
        echo "Install with:"
        echo "  macOS:  brew install bats-core"
        echo "  Ubuntu: apt install bats"
        echo "  npm:    npm install -g bats"
        echo ""
        echo "Or run: $0 --install"
        exit 1
    fi
    info "bats-core found: $(bats --version)"
}

check_docker() {
    if ! command -v docker &> /dev/null; then
        warn "Docker is not installed - integration tests will be skipped"
        return 1
    fi

    if ! docker info &> /dev/null 2>&1; then
        warn "Docker daemon is not running - integration tests will be skipped"
        return 1
    fi

    info "Docker found: $(docker --version | head -1)"
    return 0
}

install_bats() {
    info "Attempting to install bats-core..."

    if command -v brew &> /dev/null; then
        brew install bats-core
    elif command -v apt-get &> /dev/null; then
        sudo apt-get update && sudo apt-get install -y bats
    elif command -v npm &> /dev/null; then
        npm install -g bats
    else
        error "Could not find a package manager to install bats"
        echo "Please install manually from: https://github.com/bats-core/bats-core"
        exit 1
    fi

    info "bats-core installed successfully"
}

# =============================================================================
# TEST RUNNERS
# =============================================================================

run_unit_tests() {
    header "Running Unit Tests"

    local bats_args=()
    [[ "$VERBOSE" == "true" ]] && bats_args+=("--verbose-run")
    [[ "$TAP_OUTPUT" == "true" ]] && bats_args+=("--tap")

    if bats "${bats_args[@]}" "$SCRIPT_DIR/install.bats"; then
        info "Unit tests passed!"
        return 0
    else
        error "Unit tests failed!"
        return 1
    fi
}

run_integration_tests() {
    header "Running Integration Tests"

    if [[ "$NO_DOCKER" == "true" ]]; then
        warn "Docker tests skipped (--no-docker flag)"
        return 0
    fi

    if ! check_docker; then
        warn "Skipping integration tests (Docker not available)"
        return 0
    fi

    local bats_args=()
    [[ "$VERBOSE" == "true" ]] && bats_args+=("--verbose-run")
    [[ "$TAP_OUTPUT" == "true" ]] && bats_args+=("--tap")

    if bats "${bats_args[@]}" "$SCRIPT_DIR/integration.bats"; then
        info "Integration tests passed!"
        return 0
    else
        error "Integration tests failed!"
        return 1
    fi
}

run_legacy_tests() {
    header "Running Legacy Tests"

    if [[ -f "$PROJECT_DIR/test_install.sh" ]]; then
        info "Running legacy test_install.sh..."
        if bash "$PROJECT_DIR/test_install.sh"; then
            info "Legacy tests passed!"
            return 0
        else
            error "Legacy tests failed!"
            return 1
        fi
    else
        info "No legacy tests found"
        return 0
    fi
}

run_syntax_check() {
    header "Running Syntax Check"

    if bash -n "$PROJECT_DIR/install.sh"; then
        info "Syntax check passed!"
        return 0
    else
        error "Syntax check failed!"
        return 1
    fi
}

# =============================================================================
# MAIN
# =============================================================================

main() {
    local test_type="all"
    VERBOSE="false"
    TAP_OUTPUT="false"
    NO_DOCKER="false"

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help)
                usage
                ;;
            -v|--verbose)
                VERBOSE="true"
                shift
                ;;
            -t|--tap)
                TAP_OUTPUT="true"
                shift
                ;;
            --no-docker)
                NO_DOCKER="true"
                shift
                ;;
            --install)
                install_bats
                exit 0
                ;;
            unit|integration|all|legacy)
                test_type="$1"
                shift
                ;;
            *)
                error "Unknown option: $1"
                usage
                ;;
        esac
    done

    # Header
    echo ""
    echo -e "${GREEN}zkemails install.sh Test Suite${NC}"
    echo ""

    # Check dependencies
    check_bats

    # Track results
    local failed=0

    # Always run syntax check first
    run_syntax_check || ((failed++))

    # Run requested tests
    case "$test_type" in
        unit)
            run_unit_tests || ((failed++))
            ;;
        integration)
            run_integration_tests || ((failed++))
            ;;
        legacy)
            run_legacy_tests || ((failed++))
            ;;
        all)
            run_unit_tests || ((failed++))
            run_integration_tests || ((failed++))
            ;;
    esac

    # Summary
    header "Test Summary"

    if [[ $failed -eq 0 ]]; then
        echo -e "${GREEN}All tests passed!${NC}"
        exit 0
    else
        echo -e "${RED}$failed test suite(s) failed${NC}"
        exit 1
    fi
}

main "$@"
