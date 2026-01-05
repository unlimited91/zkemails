# test/test_helper.bash
#
# Shared test utilities and setup for Bats tests
#
# This file is sourced by Bats test files to provide:
#   - Isolated test environment setup/teardown
#   - Mocking utilities for external commands
#   - Custom assertion helpers
#

# Path to the install script
export INSTALL_SCRIPT="${BATS_TEST_DIRNAME}/../install.sh"

# Temporary directory for test artifacts
export TEST_TMP=""

# Original environment for restoration
export ORIGINAL_HOME="$HOME"
export ORIGINAL_PATH="$PATH"

# =============================================================================
# SETUP AND TEARDOWN
# =============================================================================

# Create an isolated test environment
# Sets up a temporary HOME directory to prevent side effects
setup_test_env() {
    TEST_TMP="$(mktemp -d)"
    export HOME="$TEST_TMP/home"
    export MOCK_DIR="$TEST_TMP/mocks"
    mkdir -p "$HOME"
    mkdir -p "$MOCK_DIR"

    # Prepend mock directory to PATH
    export PATH="$MOCK_DIR:$ORIGINAL_PATH"

    # Set variables that install.sh uses
    export INSTALL_DIR="$HOME/.zkemails"
    export BIN_DIR="$INSTALL_DIR/bin"
}

# Clean up the test environment
teardown_test_env() {
    export HOME="$ORIGINAL_HOME"
    export PATH="$ORIGINAL_PATH"
    if [[ -n "$TEST_TMP" && -d "$TEST_TMP" ]]; then
        rm -rf "$TEST_TMP"
    fi
}

# =============================================================================
# SOURCING HELPERS
# =============================================================================

# Source install.sh functions without executing main()
# This allows testing individual functions in isolation
source_install_functions() {
    local temp_script="$TEST_TMP/install_functions.sh"

    # Copy install.sh but comment out the main invocation
    sed 's/^main "\$@"$/# main "$@"/' "$INSTALL_SCRIPT" > "$temp_script"

    # Source the modified script
    # shellcheck source=/dev/null
    source "$temp_script"
}

# =============================================================================
# MOCK CREATORS
# =============================================================================

# Create a mock command
# Arguments:
#   $1 - command name
#   $2 - exit code (default: 0)
#   $3 - stdout output (optional)
#   $4 - stderr output (optional)
create_mock() {
    local cmd="$1"
    local exit_code="${2:-0}"
    local stdout="${3:-}"
    local stderr="${4:-}"

    cat > "$MOCK_DIR/$cmd" << EOF
#!/bin/bash
${stdout:+echo "$stdout"}
${stderr:+echo "$stderr" >&2}
exit $exit_code
EOF
    chmod +x "$MOCK_DIR/$cmd"
}

# Create a mock that logs its invocations
# Arguments:
#   $1 - command name
#   $2 - exit code (default: 0)
#   $3 - stdout output (optional)
create_logging_mock() {
    local cmd="$1"
    local exit_code="${2:-0}"
    local stdout="${3:-}"

    cat > "$MOCK_DIR/$cmd" << EOF
#!/bin/bash
echo "\$0 \$*" >> "$TEST_TMP/mock_calls.log"
${stdout:+echo "$stdout"}
exit $exit_code
EOF
    chmod +x "$MOCK_DIR/$cmd"
}

# Get logged mock calls
get_mock_calls() {
    cat "$TEST_TMP/mock_calls.log" 2>/dev/null || echo ""
}

# Create a mock java command that reports a specific version
# Arguments:
#   $1 - major version number (e.g., "17", "21", "11")
mock_java_version() {
    local version="$1"

    cat > "$MOCK_DIR/java" << EOF
#!/bin/bash
if [[ "\$1" == "-version" ]]; then
    echo 'openjdk version "${version}.0.2" 2024-01-16' >&2
    echo 'OpenJDK Runtime Environment Temurin-${version}.0.2+8 (build ${version}.0.2+8)' >&2
    echo 'OpenJDK 64-Bit Server VM Temurin-${version}.0.2+8 (build ${version}.0.2+8, mixed mode)' >&2
    exit 0
fi
# For other java invocations
exec /usr/bin/java "\$@" 2>/dev/null || exit 0
EOF
    chmod +x "$MOCK_DIR/java"
}

# Create a mock curl command
# Arguments:
#   $1 - HTTP status code to return
#   $2 - response body (optional)
mock_curl() {
    local http_code="$1"
    local body="${2:-}"

    cat > "$MOCK_DIR/curl" << 'OUTER_EOF'
#!/bin/bash
# Parse curl arguments
OUTPUT_FILE=""
WRITE_OUT=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -o)
            OUTPUT_FILE="$2"
            shift 2
            ;;
        -w)
            WRITE_OUT="$2"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done

OUTER_EOF

    cat >> "$MOCK_DIR/curl" << EOF
# Write body to output file if specified
if [[ -n "\$OUTPUT_FILE" ]]; then
    echo "$body" > "\$OUTPUT_FILE"
fi

# Handle -w "%{http_code}" format
if [[ "\$WRITE_OUT" == *"http_code"* ]]; then
    echo "$http_code"
else
    echo "$body"
fi
exit 0
EOF
    chmod +x "$MOCK_DIR/curl"
}

# Create a mock curl that returns GitHub release info
mock_github_release() {
    local version="$1"

    cat > "$MOCK_DIR/curl" << EOF
#!/bin/bash
# Check if this is a GitHub API call
if [[ "\$*" == *"api.github.com"* ]]; then
    echo '{"tag_name": "$version"}'
    exit 0
fi

# For download calls, parse -o flag
OUTPUT_FILE=""
for arg in "\$@"; do
    if [[ "\$prev" == "-o" ]]; then
        OUTPUT_FILE="\$arg"
    fi
    prev="\$arg"
done

if [[ -n "\$OUTPUT_FILE" ]]; then
    echo "mock jar content" > "\$OUTPUT_FILE"
    echo "200"
else
    echo "200"
fi
exit 0
EOF
    chmod +x "$MOCK_DIR/curl"
}

# Create mock sdk (SDKMAN) command
mock_sdk() {
    local install_success="${1:-true}"

    cat > "$MOCK_DIR/sdk" << EOF
#!/bin/bash
case "\$1" in
    install)
        if [[ "$install_success" == "true" ]]; then
            echo "Installing: java \$3"
            exit 0
        else
            echo "Installation failed" >&2
            exit 1
        fi
        ;;
    list)
        echo "Available Java Versions"
        echo "17.0.2-tem"
        echo "21.0.1-tem"
        exit 0
        ;;
    *)
        exit 0
        ;;
esac
EOF
    chmod +x "$MOCK_DIR/sdk"
}

# =============================================================================
# ASSERTION HELPERS
# =============================================================================

# Assert that output contains a substring
# Arguments:
#   $1 - substring to find
assert_output_contains() {
    local needle="$1"
    if [[ "$output" != *"$needle"* ]]; then
        echo "Expected output to contain: $needle"
        echo "Actual output: $output"
        return 1
    fi
}

# Assert that output does NOT contain a substring
assert_output_not_contains() {
    local needle="$1"
    if [[ "$output" == *"$needle"* ]]; then
        echo "Expected output to NOT contain: $needle"
        echo "Actual output: $output"
        return 1
    fi
}

# Assert that a file exists
assert_file_exists() {
    local file="$1"
    if [[ ! -f "$file" ]]; then
        echo "Expected file to exist: $file"
        return 1
    fi
}

# Assert that a file does not exist
assert_file_not_exists() {
    local file="$1"
    if [[ -f "$file" ]]; then
        echo "Expected file to NOT exist: $file"
        return 1
    fi
}

# Assert that a directory exists
assert_dir_exists() {
    local dir="$1"
    if [[ ! -d "$dir" ]]; then
        echo "Expected directory to exist: $dir"
        return 1
    fi
}

# Assert that a file is executable
assert_file_executable() {
    local file="$1"
    if [[ ! -x "$file" ]]; then
        echo "Expected file to be executable: $file"
        return 1
    fi
}

# Assert that a file contains a string
assert_file_contains() {
    local file="$1"
    local needle="$2"
    if ! grep -q "$needle" "$file" 2>/dev/null; then
        echo "Expected file $file to contain: $needle"
        return 1
    fi
}

# Assert command succeeded (status 0)
assert_success() {
    if [[ "$status" -ne 0 ]]; then
        echo "Expected success (status 0), got status: $status"
        echo "Output: $output"
        return 1
    fi
}

# Assert command failed (status non-zero)
assert_failure() {
    if [[ "$status" -eq 0 ]]; then
        echo "Expected failure (status non-zero), got status: 0"
        echo "Output: $output"
        return 1
    fi
}

# =============================================================================
# INPUT SIMULATION
# =============================================================================

# Simulate user input for interactive prompts
# Arguments:
#   $1 - input to provide (e.g., "y" or "n")
#   $2... - command and arguments to run
run_with_input() {
    local input="$1"
    shift
    echo "$input" | "$@"
}
