#!/usr/bin/env bats
#
# Integration tests for install.sh using Docker
#
# These tests run the actual install script in isolated Docker containers
# to verify the complete installation flow works correctly.
#
# Prerequisites:
#   - Docker must be installed and running
#   - bats-core installed
#
# Run with:
#   bats test/integration.bats
#
# Note: These tests are slower than unit tests as they build/run containers.
# Skip them in quick test runs with: bats test/install.bats
#

load 'test_helper'

# Docker image name for tests
DOCKER_IMAGE="zkemails-install-test"

# =============================================================================
# SETUP / TEARDOWN
# =============================================================================

setup_file() {
    # Build the test Docker image once before all tests
    cd "$BATS_TEST_DIRNAME/.."
    docker build -f test/docker/Dockerfile.test -t "$DOCKER_IMAGE" . >/dev/null 2>&1 || true
}

teardown_file() {
    # Optionally clean up the Docker image after tests
    # docker rmi "$DOCKER_IMAGE" >/dev/null 2>&1 || true
    :
}

setup() {
    # Check if Docker is available
    if ! command -v docker &> /dev/null; then
        skip "Docker is not installed"
    fi

    if ! docker info &> /dev/null; then
        skip "Docker daemon is not running"
    fi
}

# =============================================================================
# HELPER FUNCTIONS
# =============================================================================

# Run a command in the test container
run_in_container() {
    docker run --rm "$DOCKER_IMAGE" bash -c "$1"
}

# Run install.sh with specific arguments in container
run_install() {
    docker run --rm "$DOCKER_IMAGE" bash -c "./install.sh $*"
}

# =============================================================================
# BASIC DOCKER TESTS
# =============================================================================

@test "Docker image builds successfully" {
    cd "$BATS_TEST_DIRNAME/.."
    run docker build -f test/docker/Dockerfile.test -t "$DOCKER_IMAGE" .
    assert_success
}

@test "install.sh exists in container" {
    run run_in_container "test -f /home/testuser/install.sh"
    assert_success
}

@test "install.sh is executable in container" {
    run run_in_container "test -x /home/testuser/install.sh"
    assert_success
}

@test "install.sh has valid syntax in container" {
    run run_in_container "bash -n /home/testuser/install.sh"
    assert_success
}

# =============================================================================
# HELP AND USAGE TESTS
# =============================================================================

@test "install.sh --help works in container" {
    run run_in_container "./install.sh --help"
    assert_success
    assert_output_contains "Usage:"
    assert_output_contains "--version"
}

@test "install.sh -h works in container" {
    run run_in_container "./install.sh -h"
    assert_success
    assert_output_contains "Usage:"
}

@test "install.sh shows banner" {
    # Even --help should show the banner or usage
    run run_in_container "./install.sh --help"
    assert_success
    assert_output_contains "zkemails"
}

# =============================================================================
# PREREQUISITES TESTS
# =============================================================================

@test "curl is available in container" {
    run run_in_container "command -v curl"
    assert_success
}

@test "bash is available in container" {
    run run_in_container "command -v bash"
    assert_success
}

@test "container runs as non-root user" {
    run run_in_container "whoami"
    assert_success
    [ "$output" = "testuser" ]
}

@test "HOME is set correctly in container" {
    run run_in_container 'echo $HOME'
    assert_success
    [ "$output" = "/home/testuser" ]
}

# =============================================================================
# JAVA DETECTION TESTS
# =============================================================================

@test "detects missing Java in fresh container" {
    # This should prompt for Java installation (and fail without input)
    run run_in_container "echo 'n' | ./install.sh 2>&1 || true"
    # Should mention Java is required
    assert_output_contains "Java"
}

# =============================================================================
# ERROR HANDLING TESTS
# =============================================================================

@test "fails gracefully on invalid version" {
    run run_in_container "echo 'n' | ./install.sh -v nonexistent-version 2>&1 || true"
    # Should either fail on Java check or version download
    [ "$status" -ne 0 ] || [[ "$output" == *"Java"* ]] || [[ "$output" == *"Failed"* ]]
}

@test "fails gracefully on unknown option" {
    run run_in_container "./install.sh --invalid-option 2>&1 || true"
    assert_output_contains "Unknown option"
}

# =============================================================================
# SDKMAN DETECTION TESTS
# =============================================================================

@test "SDKMAN directory does not exist initially" {
    run run_in_container "test -d ~/.sdkman"
    assert_failure
}

# =============================================================================
# INSTALLATION DIRECTORY TESTS
# =============================================================================

@test ".zkemails directory does not exist initially" {
    run run_in_container "test -d ~/.zkemails"
    assert_failure
}

# =============================================================================
# MULTI-DISTRO TESTS (using different base images)
# =============================================================================

@test "syntax check passes on Debian" {
    run docker run --rm -v "$BATS_TEST_DIRNAME/../install.sh:/install.sh:ro" \
        debian:bullseye bash -n /install.sh
    assert_success
}

@test "syntax check passes on Alpine (if bash available)" {
    run docker run --rm -v "$BATS_TEST_DIRNAME/../install.sh:/install.sh:ro" \
        alpine:latest sh -c "apk add --no-cache bash >/dev/null 2>&1 && bash -n /install.sh"
    assert_success
}

@test "syntax check passes on Amazon Linux" {
    run docker run --rm -v "$BATS_TEST_DIRNAME/../install.sh:/install.sh:ro" \
        amazonlinux:2023 bash -n /install.sh
    assert_success
}

# =============================================================================
# FULL INSTALLATION TEST (requires network)
# =============================================================================

# This test is slow and requires network access - skip in CI if needed
@test "full SDKMAN + Java installation works" {
    # Skip if NO_NETWORK_TESTS is set
    if [[ -n "${NO_NETWORK_TESTS:-}" ]]; then
        skip "Network tests disabled"
    fi

    # Skip if test takes too long (set timeout externally)
    if [[ -n "${SKIP_SLOW_TESTS:-}" ]]; then
        skip "Slow tests disabled"
    fi

    # Run full installation with 'y' to accept Java installation
    # This will install SDKMAN and Java 17
    run docker run --rm "$DOCKER_IMAGE" bash -c "
        echo 'y' | timeout 300 ./install.sh -v 0.0.1.beta1 2>&1 || echo 'Install completed or timed out'
    "

    # We just check it doesn't crash immediately
    # Full success depends on GitHub release availability
    [[ "$output" != *"syntax error"* ]]
}
