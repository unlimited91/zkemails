#!/usr/bin/env bats
#
# Unit tests for install.sh
#
# These tests verify individual functions work correctly using mocked
# external dependencies (curl, java, sdk).
#
# Run with:
#   bats test/install.bats
#
# Prerequisites:
#   brew install bats-core   # macOS
#   apt install bats         # Ubuntu/Debian
#

load 'test_helper'

# =============================================================================
# SETUP / TEARDOWN
# =============================================================================

setup() {
    setup_test_env
    source_install_functions
}

teardown() {
    teardown_test_env
}

# =============================================================================
# SCRIPT STRUCTURE TESTS
# =============================================================================

@test "install.sh exists and is executable" {
    assert_file_exists "$INSTALL_SCRIPT"
    assert_file_executable "$INSTALL_SCRIPT"
}

@test "install.sh has valid bash syntax" {
    run bash -n "$INSTALL_SCRIPT"
    assert_success
}

@test "install.sh has correct shebang" {
    run head -n 1 "$INSTALL_SCRIPT"
    [ "$output" = "#!/bin/bash" ]
}

@test "install.sh uses set -e for error handling" {
    run grep "^set -e" "$INSTALL_SCRIPT"
    assert_success
}

# =============================================================================
# LOGGING FUNCTION TESTS
# =============================================================================

@test "info() outputs message with INFO tag" {
    run info "Test message"
    assert_success
    assert_output_contains "INFO"
    assert_output_contains "Test message"
}

@test "warn() outputs message with WARN tag" {
    run warn "Warning message"
    assert_success
    assert_output_contains "WARN"
    assert_output_contains "Warning message"
}

@test "error() outputs message with ERROR tag and exits 1" {
    run error "Error message"
    assert_failure
    assert_output_contains "ERROR"
    assert_output_contains "Error message"
}

# =============================================================================
# UTILITY FUNCTION TESTS
# =============================================================================

@test "check_command returns 0 for existing command" {
    run check_command "bash"
    assert_success
}

@test "check_command returns 1 for non-existent command" {
    run check_command "nonexistent_command_xyz"
    assert_failure
}

@test "get_java_major_version extracts version 17" {
    mock_java_version "17"
    run get_java_major_version
    assert_success
    [ "$output" = "17" ]
}

@test "get_java_major_version extracts version 21" {
    mock_java_version "21"
    run get_java_major_version
    assert_success
    [ "$output" = "21" ]
}

@test "get_java_major_version extracts version 11" {
    mock_java_version "11"
    run get_java_major_version
    assert_success
    [ "$output" = "11" ]
}

# =============================================================================
# ARGUMENT PARSING TESTS
# =============================================================================

@test "usage() displays help information" {
    run usage
    assert_success
    assert_output_contains "Usage:"
    assert_output_contains "--version"
    assert_output_contains "--help"
}

@test "parse_args sets version with --version" {
    ZKEMAILS_VERSION=""
    parse_args "--version" "1.2.3"
    [ "$ZKEMAILS_VERSION" = "1.2.3" ]
}

@test "parse_args sets version with -v" {
    ZKEMAILS_VERSION=""
    parse_args "-v" "2.0.0"
    [ "$ZKEMAILS_VERSION" = "2.0.0" ]
}

@test "parse_args fails on unknown option" {
    run parse_args "--unknown-flag"
    assert_failure
    assert_output_contains "Unknown option"
}

@test "install.sh --help displays usage" {
    run bash "$INSTALL_SCRIPT" --help
    assert_success
    assert_output_contains "Usage:"
}

# =============================================================================
# SDKMAN FUNCTION TESTS
# =============================================================================

@test "init_sdkman sets SDKMAN_DIR environment variable" {
    unset SDKMAN_DIR
    init_sdkman
    [ "$SDKMAN_DIR" = "$HOME/.sdkman" ]
}

@test "is_sdkman_installed returns 1 when SDKMAN not present" {
    run is_sdkman_installed
    assert_failure
}

@test "is_sdkman_installed returns 0 when sdk command exists" {
    mock_sdk
    run is_sdkman_installed
    assert_success
}

@test "show_java_install_prompt shows message for missing Java" {
    run show_java_install_prompt ""
    assert_output_contains "Java is not installed"
    assert_output_contains "SDKMAN"
}

@test "show_java_install_prompt shows message for outdated Java" {
    run show_java_install_prompt "11"
    assert_output_contains "Java 11 is installed"
    assert_output_contains "17+ is required"
}

# =============================================================================
# DOWNLOAD FUNCTION TESTS
# =============================================================================

@test "download_jar creates bin directory" {
    mock_curl "200" "mock-jar-content"
    ZKEMAILS_VERSION="1.0.0"

    run download_jar
    assert_success
    assert_dir_exists "$BIN_DIR"
}

@test "download_jar saves JAR file" {
    mock_curl "200" "mock-jar-content"
    ZKEMAILS_VERSION="1.0.0"

    download_jar
    assert_file_exists "$BIN_DIR/zkemails.jar"
}

@test "download_jar fails on HTTP error" {
    mock_curl "404" ""
    ZKEMAILS_VERSION="nonexistent"

    run download_jar
    assert_failure
    assert_output_contains "Failed to download"
}

# =============================================================================
# WRAPPER SCRIPT TESTS
# =============================================================================

@test "create_wrapper creates executable script" {
    mkdir -p "$BIN_DIR"
    create_wrapper

    assert_file_exists "$BIN_DIR/zkemails"
    assert_file_executable "$BIN_DIR/zkemails"
}

@test "create_wrapper script contains java -jar" {
    mkdir -p "$BIN_DIR"
    create_wrapper

    assert_file_contains "$BIN_DIR/zkemails" "java -jar"
}

@test "create_wrapper script uses exec for signal handling" {
    mkdir -p "$BIN_DIR"
    create_wrapper

    assert_file_contains "$BIN_DIR/zkemails" "exec java"
}

@test "create_wrapper script references zkemails.jar" {
    mkdir -p "$BIN_DIR"
    create_wrapper

    assert_file_contains "$BIN_DIR/zkemails" "zkemails.jar"
}

# =============================================================================
# PATH CONFIGURATION TESTS
# =============================================================================

@test "add_to_path_file returns 1 for non-existent file" {
    run add_to_path_file "$HOME/.nonexistent"
    assert_failure
}

@test "add_to_path_file adds PATH entry to file" {
    touch "$HOME/.bashrc"
    run add_to_path_file "$HOME/.bashrc"
    assert_success

    assert_file_contains "$HOME/.bashrc" ".zkemails/bin"
    assert_file_contains "$HOME/.bashrc" "export PATH"
}

@test "add_to_path_file adds zkemails comment" {
    touch "$HOME/.bashrc"
    add_to_path_file "$HOME/.bashrc"

    assert_file_contains "$HOME/.bashrc" "# zkemails"
}

@test "add_to_path_file does not duplicate entry" {
    echo 'export PATH="$HOME/.zkemails/bin:$PATH"' > "$HOME/.bashrc"

    run add_to_path_file "$HOME/.bashrc"
    assert_failure
    assert_output_contains "already configured"
}

@test "add_to_path_file works with .zshrc" {
    touch "$HOME/.zshrc"
    run add_to_path_file "$HOME/.zshrc"
    assert_success

    assert_file_contains "$HOME/.zshrc" ".zkemails/bin"
}

# =============================================================================
# VERSION FETCHING TESTS
# =============================================================================

@test "get_latest_version fetches from GitHub API" {
    mock_github_release "v2.0.0"

    run get_latest_version
    assert_success
    assert_output_contains "2.0.0"
}

@test "get_latest_version strips v prefix" {
    mock_github_release "v1.5.0"

    get_latest_version
    [ "$ZKEMAILS_VERSION" = "1.5.0" ]
}

@test "get_latest_version fails on network error" {
    create_mock "curl" 1 ""

    run get_latest_version
    assert_failure
    assert_output_contains "Failed to fetch"
}

# =============================================================================
# PREREQUISITES TESTS
# =============================================================================

@test "check_prerequisites succeeds with Java 17+" {
    mock_java_version "17"

    run check_prerequisites
    assert_success
    assert_output_contains "Prerequisites satisfied"
}

@test "check_prerequisites succeeds with Java 21" {
    mock_java_version "21"

    run check_prerequisites
    assert_success
    assert_output_contains "Prerequisites satisfied"
}

@test "check_prerequisites fails without curl" {
    # Remove curl from PATH by using empty mock dir only
    export PATH="$MOCK_DIR"

    run check_prerequisites
    assert_failure
    assert_output_contains "curl is required"
}

# =============================================================================
# OUTPUT MESSAGE TESTS
# =============================================================================

@test "print_banner displays installer name" {
    run print_banner
    assert_success
    assert_output_contains "zkemails installer"
}

@test "print_success shows completion message" {
    ZKEMAILS_VERSION="1.0.0"
    run print_success
    assert_success
    assert_output_contains "Installation complete"
}

@test "print_success shows next steps" {
    ZKEMAILS_VERSION="1.0.0"
    run print_success
    assert_output_contains "zkemails --help"
    assert_output_contains "zkemails init"
}

# =============================================================================
# INTEGRATION TESTS (with mocks)
# =============================================================================

@test "full install flow with mocked dependencies" {
    # Mock all external dependencies
    mock_java_version "17"
    mock_github_release "1.0.0"
    touch "$HOME/.bashrc"

    # Run main (but skip the interactive prompts by having Java available)
    ZKEMAILS_VERSION="1.0.0"

    # Test individual steps
    run check_prerequisites
    assert_success

    run download_jar
    assert_success

    run create_wrapper
    assert_success

    # Verify artifacts
    assert_file_exists "$BIN_DIR/zkemails.jar"
    assert_file_exists "$BIN_DIR/zkemails"
    assert_file_executable "$BIN_DIR/zkemails"
}

@test "all required functions are defined" {
    local functions=(
        "info" "warn" "error" "usage" "parse_args"
        "check_command" "get_java_major_version" "prompt_yes_no"
        "init_sdkman" "is_sdkman_installed" "install_sdkman"
        "install_java_with_sdkman" "show_java_install_prompt"
        "install_java_via_sdkman" "check_prerequisites"
        "get_latest_version" "download_jar" "create_wrapper"
        "add_to_path_file" "setup_path" "print_success"
        "print_banner" "main"
    )

    for func in "${functions[@]}"; do
        run type -t "$func"
        assert_success
        [ "$output" = "function" ]
    done
}
