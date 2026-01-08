#!/bin/bash
#
# zke (Zero Knowledge Emails) installer
#
# DESCRIPTION:
#   Installs the zke CLI tool by downloading the JAR from GitHub releases
#   and setting up the necessary wrapper script and PATH configuration.
#
# USAGE:
#   # Install latest version (piped from web):
#   curl -fsSL https://raw.githubusercontent.com/unlimited91/zkemails/main/install.sh | bash
#
#   # Install specific version:
#   curl -fsSL https://raw.githubusercontent.com/unlimited91/zkemails/main/install.sh | bash -s -- -v 1.0.0
#
#   # Local execution:
#   ./install.sh                    # Install latest version
#   ./install.sh -v 1.0.0          # Install version 1.0.0
#   ./install.sh --help            # Show help
#
# REQUIREMENTS:
#   - curl (for downloading)
#   - Java 17+ (will offer to install via SDKMAN if missing)
#
# INSTALLATION DIRECTORY:
#   ~/.zkemails/bin/zkemails.jar    - The JAR file
#   ~/.zkemails/bin/zke             - Wrapper script
#
# EXIT CODES:
#   0 - Success
#   1 - Error (missing prerequisites, download failure, etc.)
#
# -----------------------------------------------------------------------------

set -e

# =============================================================================
# CONFIGURATION
# =============================================================================

readonly REPO="unlimited91/zkemails"
readonly INSTALL_DIR="$HOME/.zkemails"
readonly BIN_DIR="$INSTALL_DIR/bin"
readonly JAR_NAME="zkemails.jar"
readonly MIN_JAVA_VERSION=17

# Version to install (can be overridden via -v flag)
ZKEMAILS_VERSION="0.0.1.beta1"

# =============================================================================
# OUTPUT FORMATTING
# =============================================================================

# Terminal colors (disabled if not a TTY)
if [[ -t 1 ]]; then
    readonly RED='\033[0;31m'
    readonly GREEN='\033[0;32m'
    readonly YELLOW='\033[1;33m'
    readonly NC='\033[0m'
else
    readonly RED=''
    readonly GREEN=''
    readonly YELLOW=''
    readonly NC=''
fi

# Print an info message
# Arguments: $1 - message to display
info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

# Print a warning message
# Arguments: $1 - message to display
warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# Print an error message and exit
# Arguments: $1 - message to display
error() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

# =============================================================================
# HELP AND ARGUMENT PARSING
# =============================================================================

# Display usage information and exit
usage() {
    cat << EOF
Usage: $0 [-v|--version <version>]

Options:
  -v, --version    Specify version to install (e.g., 1.0.0)
  -h, --help       Show this help message

Examples:
  $0                    # Install latest version
  $0 -v 1.0.0          # Install version 1.0.0
EOF
    exit 0
}

# Parse command line arguments
# Arguments: $@ - all command line arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -v|--version)
                ZKEMAILS_VERSION="$2"
                shift 2
                ;;
            -h|--help)
                usage
                ;;
            *)
                error "Unknown option: $1. Use -h for help."
                ;;
        esac
    done
}

# =============================================================================
# UTILITY FUNCTIONS
# =============================================================================

# Check if a command exists
# Arguments: $1 - command name
# Returns: 0 if command exists, 1 otherwise
check_command() {
    command -v "$1" &> /dev/null
}

# Get the major version number of installed Java
# Returns: Outputs the major version number (e.g., "17", "21")
get_java_major_version() {
    java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1
}

# Prompt user for yes/no confirmation
# Arguments: $1 - prompt message
# Returns: 0 if user answered yes, 1 otherwise
prompt_yes_no() {
    local prompt="$1"
    read -p "$prompt (y/n): " -n 1 -r
    echo ""
    [[ $REPLY =~ ^[Yy]$ ]]
}

# =============================================================================
# SDKMAN FUNCTIONS
# =============================================================================

# Initialize SDKMAN environment
# Sets SDKMAN_DIR and sources the init script if available
init_sdkman() {
    export SDKMAN_DIR="$HOME/.sdkman"
    if [[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
        # shellcheck source=/dev/null
        source "$SDKMAN_DIR/bin/sdkman-init.sh" 2>/dev/null || true
    fi
}

# Check if SDKMAN is installed and functional
# Returns: 0 if SDKMAN is available, 1 otherwise
is_sdkman_installed() {
    init_sdkman
    check_command sdk
}

# Install SDKMAN
# Downloads and installs SDKMAN from the official source
install_sdkman() {
    info "Installing SDKMAN..."
    curl -s "https://get.sdkman.io" | bash

    init_sdkman

    if ! check_command sdk; then
        error "SDKMAN installation failed. Please install Java $MIN_JAVA_VERSION+ manually and try again."
    fi

    info "SDKMAN installed successfully"
}

# Install Java using SDKMAN
# Attempts to install Java 17 Temurin distribution
install_java_with_sdkman() {
    info "Installing Java $MIN_JAVA_VERSION via SDKMAN..."

    # Try different identifier formats that SDKMAN supports
    if ! sdk install java "${MIN_JAVA_VERSION}-tem" 2>/dev/null; then
        if ! sdk install java "${MIN_JAVA_VERSION}.0-tem" 2>/dev/null; then
            echo ""
            warn "Could not install using generic identifiers. Available Java $MIN_JAVA_VERSION versions:"
            sdk list java | grep -E "${MIN_JAVA_VERSION}\..*tem" | head -10 || echo "  (Could not list versions)"
            echo ""
            error "Failed to install Java $MIN_JAVA_VERSION. Please try manually with: sdk install java <version>"
        fi
    fi

    # Re-source SDKMAN to ensure Java is available
    init_sdkman

    if ! check_command java; then
        error "Java installation completed but java command not found. Please restart your shell and try again."
    fi

    local installed_version
    installed_version=$(get_java_major_version)
    info "Java $installed_version installed successfully"

    echo ""
    echo -e "${GREEN}Note:${NC} After installation completes, you may need to restart your shell"
    echo "or source your shell configuration file for Java to be available everywhere."
    echo ""
}

# Display the Java installation prompt
# Shows information about SDKMAN and asks for user confirmation
show_java_install_prompt() {
    local current_version="${1:-}"

    echo ""
    if [[ -n "$current_version" ]]; then
        echo -e "${YELLOW}Java $current_version is installed but Java $MIN_JAVA_VERSION+ is required.${NC}"
    else
        echo -e "${YELLOW}Java is not installed on your system.${NC}"
    fi
    echo ""
    echo "Would you like us to install Java $MIN_JAVA_VERSION?"
    echo "We will do it via an open source product called SDKMAN which is a version"
    echo "manager and will allow you to upgrade or downgrade later."
    echo ""
}

# Full Java installation flow via SDKMAN
# Handles SDKMAN installation if needed, then installs Java
install_java_via_sdkman() {
    local current_version="${1:-}"

    show_java_install_prompt "$current_version"

    if ! prompt_yes_no "Install Java $MIN_JAVA_VERSION via SDKMAN?"; then
        error "Java $MIN_JAVA_VERSION+ is required to run zke. Please install Java manually and run this script again."
    fi

    # Install SDKMAN if not already installed
    if ! is_sdkman_installed; then
        install_sdkman
    else
        info "SDKMAN already installed"
    fi

    # Check if Java already satisfies requirements after SDKMAN init
    if check_command java; then
        local version
        version=$(get_java_major_version)
        if [[ "$version" -ge $MIN_JAVA_VERSION ]]; then
            info "Java $version is already installed and satisfies the requirement"
            return 0
        fi
    fi

    install_java_with_sdkman
}

# =============================================================================
# PREREQUISITES
# =============================================================================

# Check all prerequisites are met
# Verifies curl is available and Java 17+ is installed
check_prerequisites() {
    info "Checking prerequisites..."

    # Check curl
    if ! check_command curl; then
        error "curl is required but not installed. Please install curl and try again."
    fi

    # Check Java installation
    if ! check_command java; then
        install_java_via_sdkman ""
    fi

    # Verify Java version meets minimum requirement
    local java_version
    java_version=$(get_java_major_version)

    if [[ "$java_version" -lt $MIN_JAVA_VERSION ]]; then
        install_java_via_sdkman "$java_version"
        # Re-check after installation
        java_version=$(get_java_major_version)
        if [[ "$java_version" -lt $MIN_JAVA_VERSION ]]; then
            error "Java $MIN_JAVA_VERSION+ is required. Found Java $java_version after installation."
        fi
    fi

    info "Prerequisites satisfied (Java $java_version found)"
}

# =============================================================================
# VERSION FETCHING
# =============================================================================

# Fetch the latest release version from GitHub
# Sets ZKEMAILS_VERSION to the latest version tag
get_latest_version() {
    info "Fetching latest release version..."

    local latest
    latest=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null | \
        grep '"tag_name"' | \
        sed -E 's/.*"tag_name": *"([^"]+)".*/\1/' | \
        sed 's/^v//')

    if [[ -z "$latest" ]]; then
        error "Failed to fetch latest release. Check your internet connection or specify a version with -v."
    fi

    ZKEMAILS_VERSION="$latest"
    info "Latest version: $ZKEMAILS_VERSION"
}

# =============================================================================
# DOWNLOAD AND INSTALLATION
# =============================================================================

# Download the zkemails JAR file from GitHub releases
download_jar() {
    info "Downloading zkemails v$ZKEMAILS_VERSION..."

    mkdir -p "$BIN_DIR"

    local download_url="https://github.com/$REPO/releases/download/$ZKEMAILS_VERSION/zkemails-$ZKEMAILS_VERSION-fat.jar"
    local http_code

    # Try primary download URL
    http_code=$(curl -fsSL -w "%{http_code}" -o "$BIN_DIR/$JAR_NAME" "$download_url" 2>/dev/null || echo "000")

    # Try alternate naming pattern if primary fails
    if [[ "$http_code" != "200" ]]; then
        download_url="https://github.com/$REPO/releases/download/$ZKEMAILS_VERSION/zkemails-fat.jar"
        http_code=$(curl -fsSL -w "%{http_code}" -o "$BIN_DIR/$JAR_NAME" "$download_url" 2>/dev/null || echo "000")
    fi

    if [[ "$http_code" != "200" ]]; then
        rm -f "$BIN_DIR/$JAR_NAME"
        error "Failed to download release v$ZKEMAILS_VERSION. HTTP status: $http_code. Please verify the version exists."
    fi

    info "Downloaded to $BIN_DIR/$JAR_NAME"
}

# Create the wrapper script that invokes the JAR
create_wrapper() {
    info "Creating wrapper script..."

    local wrapper="$BIN_DIR/zke"

    cat > "$wrapper" << 'EOF'
#!/bin/bash
exec java -jar "$HOME/.zkemails/bin/zkemails.jar" "$@"
EOF

    chmod +x "$wrapper"
    info "Created executable: $wrapper"
}

# =============================================================================
# PATH CONFIGURATION
# =============================================================================

# Add PATH entry to a shell configuration file
# Arguments: $1 - path to the shell config file
# Returns: 0 if PATH was added, 1 if already configured or file doesn't exist
add_to_path_file() {
    local file="$1"
    local path_entry='export PATH="$HOME/.zkemails/bin:$PATH"'

    if [[ ! -f "$file" ]]; then
        return 1
    fi

    if grep -q '.zkemails/bin' "$file" 2>/dev/null; then
        info "PATH already configured in $file"
        return 1
    fi

    {
        echo ""
        echo "# zke (Zero Knowledge Emails)"
        echo "$path_entry"
    } >> "$file"

    info "Added to PATH in $file"
    return 0
}

# Configure PATH for the user's shell
setup_path() {
    info "Setting up PATH..."

    local modified=0

    # Configure based on current shell
    case "$SHELL" in
        */zsh)
            add_to_path_file "$HOME/.zshrc" && modified=1
            ;;
        */bash)
            # On macOS, .bash_profile is typically used for login shells
            if [[ "$(uname)" == "Darwin" ]]; then
                add_to_path_file "$HOME/.bash_profile" && modified=1
            fi
            add_to_path_file "$HOME/.bashrc" && modified=1
            ;;
        *)
            warn "Unknown shell: $SHELL. You may need to add $BIN_DIR to your PATH manually."
            ;;
    esac

    # Also configure other shells if their config files exist (user might switch)
    if [[ -f "$HOME/.zshrc" ]] && [[ "$SHELL" != */zsh ]]; then
        add_to_path_file "$HOME/.zshrc" && modified=1
    fi

    if [[ -f "$HOME/.bashrc" ]] && [[ "$SHELL" != */bash ]]; then
        add_to_path_file "$HOME/.bashrc" && modified=1
    fi

    if [[ $modified -eq 1 ]]; then
        warn "Please restart your shell or run: source ~/.$(basename "$SHELL")rc"
    fi
}

# =============================================================================
# COMPLETION MESSAGE
# =============================================================================

# Print installation success message with next steps
print_success() {
    local shell_rc
    shell_rc=".$(basename "$SHELL")rc"

    cat << EOF

${GREEN}Installation complete!${NC}

zke v$ZKEMAILS_VERSION has been installed to $BIN_DIR

To get started, restart your terminal or run:
  source ~/$shell_rc

Then try:
  zke --help
  zke init --email your@email.com

Quick start:
  zke init --email you@gmail.com     # Initialize with your email
  zke invite --to friend@email.com   # Send an invite
  zke sem                            # Send encrypted message
  zke rem                            # Read encrypted messages

EOF
}

# Print the installer banner
print_banner() {
    cat << 'EOF'

=====================================
    zke (Zero Knowledge Emails)
           installer
=====================================

EOF
}

# =============================================================================
# MAIN ENTRY POINT
# =============================================================================

main() {
    print_banner
    parse_args "$@"
    check_prerequisites

    if [[ -z "$ZKEMAILS_VERSION" ]]; then
        get_latest_version
    else
        info "Installing specified version: $ZKEMAILS_VERSION"
    fi

    download_jar
    create_wrapper
    setup_path
    print_success
}

# Run main function with all arguments
main "$@"
