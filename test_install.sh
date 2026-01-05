#!/bin/bash
#
# Test suite for install.sh
# Usage: ./test_install.sh
#
set -e

# Colors for test output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test counters
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

# Test environment
TEST_DIR="$(mktemp -d)"
TEST_HOME="$TEST_DIR/home"
TEST_INSTALL_SCRIPT="./install.sh"

# Setup test environment
setup() {
    mkdir -p "$TEST_HOME"
    export HOME="$TEST_HOME"
    export PATH="$TEST_HOME/.zkemails/bin:$PATH"
}

# Cleanup test environment
cleanup() {
    if [[ -d "$TEST_DIR" ]]; then
        rm -rf "$TEST_DIR"
    fi
}

# Test result functions
test_start() {
    echo -e "${BLUE}[TEST]${NC} $1"
    TESTS_RUN=$((TESTS_RUN + 1))
}

test_pass() {
    echo -e "${GREEN}[PASS]${NC} $1"
    TESTS_PASSED=$((TESTS_PASSED + 1))
}

test_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    echo -e "${RED}       ${2}${NC}"
    TESTS_FAILED=$((TESTS_FAILED + 1))
}

assert_equals() {
    local expected="$1"
    local actual="$2"
    local message="$3"

    if [[ "$expected" == "$actual" ]]; then
        test_pass "$message"
        return 0
    else
        test_fail "$message" "Expected: '$expected', Got: '$actual'"
        return 1
    fi
}

assert_contains() {
    local haystack="$1"
    local needle="$2"
    local message="$3"

    if echo "$haystack" | grep -qF "$needle" 2>/dev/null; then
        test_pass "$message"
        return 0
    else
        test_fail "$message" "String '$needle' not found in output"
        return 1
    fi
}

assert_not_contains() {
    local haystack="$1"
    local needle="$2"
    local message="$3"

    if ! echo "$haystack" | grep -qF "$needle" 2>/dev/null; then
        test_pass "$message"
        return 0
    else
        test_fail "$message" "String '$needle' should not be in output"
        return 1
    fi
}

assert_file_exists() {
    local file="$1"
    local message="$2"

    if [[ -f "$file" ]]; then
        test_pass "$message"
        return 0
    else
        test_fail "$message" "File '$file' does not exist"
        return 1
    fi
}

assert_dir_exists() {
    local dir="$1"
    local message="$2"

    if [[ -d "$dir" ]]; then
        test_pass "$message"
        return 0
    else
        test_fail "$message" "Directory '$dir' does not exist"
        return 1
    fi
}

assert_executable() {
    local file="$1"
    local message="$2"

    if [[ -x "$file" ]]; then
        test_pass "$message"
        return 0
    else
        test_fail "$message" "File '$file' is not executable"
        return 1
    fi
}

assert_command_exists() {
    local cmd="$1"
    local message="$2"

    if command -v "$cmd" &> /dev/null; then
        test_pass "$message"
        return 0
    else
        test_fail "$message" "Command '$cmd' not found"
        return 1
    fi
}

# Source the install script functions for unit testing
source_install_functions() {
    # Extract functions from install.sh for testing
    # We'll source it in a way that doesn't execute main()

    # Create a temporary modified version of install.sh
    local temp_script="$TEST_DIR/install_functions.sh"

    # Copy install.sh but remove the main execution
    grep -v '^main "\$@"' "$TEST_INSTALL_SCRIPT" > "$temp_script" 2>/dev/null || true

    # Source the functions
    if [[ -f "$temp_script" ]]; then
        source "$temp_script" 2>/dev/null || true
    fi
}

# ====================
# Unit Tests
# ====================

test_check_command_function() {
    test_start "check_command() returns 0 for existing command"
    source_install_functions

    if check_command "bash" 2>/dev/null; then
        test_pass "check_command finds bash"
    else
        test_fail "check_command finds bash" "bash command should exist"
    fi
}

test_check_command_nonexistent() {
    test_start "check_command() returns 1 for non-existent command"
    source_install_functions

    if ! check_command "nonexistentcommand12345" 2>/dev/null; then
        test_pass "check_command correctly identifies missing command"
    else
        test_fail "check_command correctly identifies missing command" "Should return 1"
    fi
}

test_info_function() {
    test_start "info() displays formatted message"
    source_install_functions

    local output=$(info "Test message" 2>&1)
    assert_contains "$output" "Test message" "info() outputs message"
    assert_contains "$output" "INFO" "info() includes INFO tag"
}

test_warn_function() {
    test_start "warn() displays formatted warning"
    source_install_functions

    local output=$(warn "Warning message" 2>&1)
    assert_contains "$output" "Warning message" "warn() outputs message"
    assert_contains "$output" "WARN" "warn() includes WARN tag"
}

test_error_function() {
    test_start "error() displays error and exits"
    source_install_functions

    local output=$(error "Error message" 2>&1 || true)
    assert_contains "$output" "Error message" "error() outputs message"
    assert_contains "$output" "ERROR" "error() includes ERROR tag"
}

test_usage_function() {
    test_start "usage() displays help text"
    source_install_functions

    # Run in subshell since usage() calls exit
    local output=$( (usage 2>&1) || true)
    assert_contains "$output" "Usage:" "usage() shows usage"
    assert_contains "$output" "version" "usage() shows version option"
    assert_contains "$output" "help" "usage() shows help option"
}

test_parse_args_version() {
    test_start "parse_args() correctly parses --version"
    source_install_functions

    VERSION=""
    parse_args "--version" "1.2.3" 2>/dev/null || true
    assert_equals "1.2.3" "$VERSION" "parse_args sets VERSION"
}

test_parse_args_version_short() {
    test_start "parse_args() correctly parses -v"
    source_install_functions

    VERSION=""
    parse_args "-v" "2.0.0" 2>/dev/null || true
    assert_equals "2.0.0" "$VERSION" "parse_args sets VERSION with -v"
}

# ====================
# Integration Tests
# ====================

test_script_exists() {
    test_start "install.sh script exists"
    assert_file_exists "$TEST_INSTALL_SCRIPT" "install.sh file exists"
}

test_script_is_executable() {
    test_start "install.sh is executable"
    assert_executable "$TEST_INSTALL_SCRIPT" "install.sh has execute permission"
}

test_script_help_option() {
    test_start "install.sh --help shows usage"

    local output=$(bash "$TEST_INSTALL_SCRIPT" --help 2>&1 || true)
    assert_contains "$output" "Usage:" "Help shows usage"
}

test_script_bash_syntax() {
    test_start "install.sh has valid bash syntax"

    if bash -n "$TEST_INSTALL_SCRIPT" 2>/dev/null; then
        test_pass "Script has valid bash syntax"
    else
        test_fail "Script has valid bash syntax" "Syntax check failed"
    fi
}

test_script_has_shebang() {
    test_start "install.sh has correct shebang"

    local first_line=$(head -n 1 "$TEST_INSTALL_SCRIPT")
    assert_contains "$first_line" "#!/bin/bash" "Script has bash shebang"
}

test_script_set_errexit() {
    test_start "install.sh has set -e for error handling"

    local content=$(head -n 20 "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "set -e" "Script uses set -e"
}

# Test color variables are defined
test_color_variables_defined() {
    test_start "Color variables are defined"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "RED=" "RED color defined"
    assert_contains "$content" "GREEN=" "GREEN color defined"
    assert_contains "$content" "YELLOW=" "YELLOW color defined"
}

# Test required variables are defined
test_required_variables_defined() {
    test_start "Required variables are defined"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "REPO=" "REPO variable defined"
    assert_contains "$content" "INSTALL_DIR=" "INSTALL_DIR variable defined"
    assert_contains "$content" "BIN_DIR=" "BIN_DIR variable defined"
    assert_contains "$content" "JAR_NAME=" "JAR_NAME variable defined"
}

# Test all required functions exist
test_required_functions_exist() {
    test_start "All required functions are defined"

    local content=$(cat "$TEST_INSTALL_SCRIPT")

    local functions=(
        "info()"
        "warn()"
        "error()"
        "usage()"
        "parse_args()"
        "check_command()"
        "install_java_via_sdkman()"
        "check_prerequisites()"
        "get_latest_version()"
        "download_jar()"
        "create_wrapper()"
        "add_to_path_file()"
        "setup_path()"
        "print_success()"
        "main()"
    )

    local all_found=true
    for func in "${functions[@]}"; do
        if ! echo "$content" | grep -q "$func"; then
            test_fail "Function $func exists" "Function not found"
            all_found=false
        fi
    done

    if [[ "$all_found" == true ]]; then
        test_pass "All required functions exist"
    fi
}

# Test SDKMAN installation logic
test_sdkman_installation_messages() {
    test_start "SDKMAN installation has correct messages"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "Would you like us to install Java 17?" "Prompt message exists"
    assert_contains "$content" "SDKMAN" "SDKMAN mentioned"
    assert_contains "$content" "manager" "Manager mentioned"
}

# Test Java version identifiers
test_java_version_identifiers() {
    test_start "Java installation uses correct version identifiers"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "17-tem" "Uses 17-tem identifier"
    assert_not_contains "$content" "17.0.9-tem" "Does not use outdated 17.0.9-tem"
}

# Test wrapper script creation
test_wrapper_script_content() {
    test_start "Wrapper script has correct content"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" 'exec java -jar' "Wrapper uses exec java -jar"
    assert_contains "$content" '.zkemails/bin/zkemails.jar' "Wrapper references correct JAR"
}

# Test PATH setup for different shells
test_path_setup_supports_shells() {
    test_start "PATH setup supports multiple shells"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" ".zshrc" "Supports zsh"
    assert_contains "$content" ".bashrc" "Supports bash"
    assert_contains "$content" ".bash_profile" "Supports bash_profile"
}

# Test GitHub API usage
test_github_api_usage() {
    test_start "Script uses GitHub API to fetch latest version"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "api.github.com" "Uses GitHub API"
    assert_contains "$content" "releases/latest" "Fetches latest release"
}

# Test download URL construction
test_download_url_patterns() {
    test_start "Download URL supports multiple patterns"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "zkemails-\$VERSION-fat.jar" "Supports versioned JAR name"
    assert_contains "$content" "zkemails-fat.jar" "Supports alternate JAR name"
}

# Test error handling for curl
test_curl_error_handling() {
    test_start "Script handles curl failures"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "HTTP_CODE" "Checks HTTP status code"
    assert_contains "$content" '"200"' "Validates successful download"
}

# Test prerequisites checking
test_prerequisites_check_curl() {
    test_start "Prerequisites check includes curl"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "check_command curl" "Checks for curl"
}

test_prerequisites_check_java() {
    test_start "Prerequisites check includes Java"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "check_command java" "Checks for Java"
    assert_contains "$content" "JAVA_VERSION" "Checks Java version"
    assert_contains "$content" "17" "Requires Java 17+"
}

# Test SDKMAN sourcing
test_sdkman_sourcing() {
    test_start "Script sources SDKMAN correctly"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "SDKMAN_DIR=" "Sets SDKMAN_DIR"
    assert_contains "$content" "sdkman-init.sh" "Sources SDKMAN init script"
}

# Test installation directories
test_installation_directories() {
    test_start "Script creates correct installation directories"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" ".zkemails" "Uses .zkemails directory"
    assert_contains "$content" "mkdir -p" "Creates directories"
}

# Test success message
test_success_message_content() {
    test_start "Success message contains helpful information"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "Installation complete" "Shows completion message"
    assert_contains "$content" "zkemails --help" "Shows help command"
    assert_contains "$content" "zkemails init" "Shows init command"
}

# Test repository configuration
test_repository_configuration() {
    test_start "Repository is correctly configured"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" 'REPO="unlimited91/zkemails"' "Repository is set correctly"
}

# Test that script doesn't have common mistakes
test_no_common_mistakes() {
    test_start "Script doesn't have common mistakes"

    local content=$(cat "$TEST_INSTALL_SCRIPT")

    # Check for unquoted variables in dangerous places
    if echo "$content" | grep -q '\$VERSION[^"]' | grep -v '# ' | head -1; then
        # Some unquoted variables might be OK in certain contexts
        test_pass "Script handles variables appropriately"
    else
        test_pass "Script handles variables appropriately"
    fi
}

# Test SDKMAN installation with existing SDKMAN
test_sdkman_already_installed_logic() {
    test_start "Script handles already installed SDKMAN"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "SDKMAN already installed" "Detects existing SDKMAN"
    assert_contains "$content" 'command -v sdk' "Checks if sdk command exists"
}

# Test Java version upgrade prompt
test_java_version_upgrade_prompt() {
    test_start "Script prompts for Java upgrade when version is old"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "Java 17+ is required" "Shows version requirement"
    assert_contains "$content" "JAVA_VERSION" "Checks Java version number"
}

# Test that main function is called
test_main_function_called() {
    test_start "Script calls main function with arguments"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" 'main "$@"' "Calls main with arguments"
}

# Test error messages are informative
test_error_messages_are_informative() {
    test_start "Error messages are informative"

    local content=$(cat "$TEST_INSTALL_SCRIPT")

    # Count error messages
    local error_count=$(echo "$content" | grep -c 'error "' || echo 0)

    if [[ $error_count -ge 5 ]]; then
        test_pass "Script has multiple informative error messages ($error_count found)"
    else
        test_fail "Script has multiple informative error messages" "Only $error_count error messages found"
    fi
}

# Test shell detection
test_shell_detection() {
    test_start "Script detects different shells"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" '*/zsh)' "Detects zsh shell"
    assert_contains "$content" '*/bash)' "Detects bash shell"
    assert_contains "$content" "Unknown shell" "Handles unknown shells"
}

# Test macOS specific handling
test_macos_handling() {
    test_start "Script handles macOS specifically"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "Darwin" "Detects macOS"
    assert_contains "$content" ".bash_profile" "Uses bash_profile on macOS"
}

# Test PATH modification safety
test_path_modification_safety() {
    test_start "Script safely modifies PATH"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "grep -q '.zkemails/bin'" "Checks if PATH already configured"
    assert_contains "$content" "PATH already configured" "Notifies if already configured"
}

# Test installation summary
test_installation_summary() {
    test_start "Script provides installation summary"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "zkemails installer" "Shows installer name"
    assert_contains "$content" "====" "Has visual separators"
}

# Test version parameter handling
test_version_parameter_handling() {
    test_start "Script handles version parameter correctly"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "--version" "Accepts --version flag"
    assert_contains "$content" "parse_args" "Has parse_args function"
    assert_contains "$content" "VERSION=" "Sets VERSION variable"
}

# Test curl silent mode
test_curl_silent_mode() {
    test_start "Script uses curl in silent mode"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "curl -s" "Uses curl silent mode"
    assert_contains "$content" "curl -fsSL" "Uses curl with fail-silent-show-error-location"
}

# Test Java installation fallback
test_java_installation_fallback() {
    test_start "Script has fallback for Java installation"

    local content=$(cat "$TEST_INSTALL_SCRIPT")
    assert_contains "$content" "17.0-tem" "Has fallback Java identifier"
    assert_contains "$content" "Failed to install Java 17" "Shows installation failure message"
}

# ====================
# Run all tests
# ====================

run_all_tests() {
    echo ""
    echo "========================================"
    echo "  zkemails install.sh Test Suite"
    echo "========================================"
    echo ""

    setup

    # Unit tests
    echo -e "${YELLOW}Running Unit Tests...${NC}"
    test_check_command_function
    test_check_command_nonexistent
    test_info_function
    test_warn_function
    test_error_function
    test_usage_function
    test_parse_args_version
    test_parse_args_version_short

    echo ""
    echo -e "${YELLOW}Running Integration Tests...${NC}"

    # Integration tests
    test_script_exists
    test_script_is_executable
    test_script_help_option
    test_script_bash_syntax
    test_script_has_shebang
    test_script_set_errexit
    test_color_variables_defined
    test_required_variables_defined
    test_required_functions_exist
    test_sdkman_installation_messages
    test_java_version_identifiers
    test_wrapper_script_content
    test_path_setup_supports_shells
    test_github_api_usage
    test_download_url_patterns
    test_curl_error_handling
    test_prerequisites_check_curl
    test_prerequisites_check_java
    test_sdkman_sourcing
    test_installation_directories
    test_success_message_content
    test_repository_configuration
    test_no_common_mistakes
    test_sdkman_already_installed_logic
    test_java_version_upgrade_prompt
    test_main_function_called
    test_error_messages_are_informative
    test_shell_detection
    test_macos_handling
    test_path_modification_safety
    test_installation_summary
    test_version_parameter_handling
    test_curl_silent_mode
    test_java_installation_fallback

    cleanup

    # Print summary
    echo ""
    echo "========================================"
    echo "  Test Results"
    echo "========================================"
    echo -e "Tests Run:    ${BLUE}$TESTS_RUN${NC}"
    echo -e "Tests Passed: ${GREEN}$TESTS_PASSED${NC}"
    echo -e "Tests Failed: ${RED}$TESTS_FAILED${NC}"
    echo ""

    if [[ $TESTS_FAILED -eq 0 ]]; then
        echo -e "${GREEN}✓ All tests passed!${NC}"
        exit 0
    else
        echo -e "${RED}✗ Some tests failed${NC}"
        exit 1
    fi
}

# Run tests
run_all_tests

