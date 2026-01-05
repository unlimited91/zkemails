#!/bin/bash
#
# zkemails installer
# Usage: curl -fsSL https://raw.githubusercontent.com/unlimited91/zkemails/main/install.sh | bash
#        curl -fsSL https://raw.githubusercontent.com/unlimited91/zkemails/main/install.sh | bash -s -- -v 1.0.0
#
set -e

REPO="unlimited91/zkemails"
INSTALL_DIR="$HOME/.zkemails"
BIN_DIR="$INSTALL_DIR/bin"
JAR_NAME="zkemails.jar"
VERSION=""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

usage() {
    echo "Usage: $0 [-v|--version <version>]"
    echo ""
    echo "Options:"
    echo "  -v, --version    Specify version to install (e.g., 1.0.0)"
    echo "  -h, --help       Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                    # Install latest version"
    echo "  $0 -v 1.0.0          # Install version 1.0.0"
    exit 0
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -v|--version)
                VERSION="$2"
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

check_command() {
    command -v "$1" >/dev/null 2>&1
    return $?
}

install_java_via_sdkman() {
    # Check if SDKMAN is already installed first
    local sdkman_exists=false
    export SDKMAN_DIR="$HOME/.sdkman"

    if [[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
        source "$SDKMAN_DIR/bin/sdkman-init.sh" 2>/dev/null || true
        if command -v sdk &> /dev/null; then
            sdkman_exists=true
            info "SDKMAN already installed"
        fi
    fi

    # If SDKMAN doesn't exist, ask user for permission to install it
    if [[ "$sdkman_exists" == false ]]; then
        echo ""
        echo -e "${YELLOW}Java is not installed on your system.${NC}"
        echo ""
        echo "Would you like us to install Java 17?"
        echo "We will do it via an open source product called SDKMAN which is a version"
        echo "manager and will allow you to upgrade or downgrade later."
        echo ""
        read -p "Install Java 17 via SDKMAN? (y/n): " -n 1 -r
        echo ""

        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            error "Java 17+ is required to run zkemails. Installation cancelled. Please install Java 17+ manually and run this script again."
        fi

        info "Installing SDKMAN..."
        curl -s "https://get.sdkman.io" | bash

        # Source SDKMAN to make it available in current shell
        if [[ -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
            source "$SDKMAN_DIR/bin/sdkman-init.sh"
        else
            error "SDKMAN installation failed. Please install Java 17+ manually and try again."
        fi

        info "SDKMAN installed successfully"
    fi

    # Check if Java 17 is already installed via SDKMAN before attempting installation
    if command -v java &> /dev/null; then
        local current_java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
        if [[ "$current_java_version" -ge 17 ]]; then
            info "Java $current_java_version is already installed and satisfies the requirement"
            return 0
        fi
    fi

    info "Installing Java 17 via SDKMAN..."
    # Install Java 17 LTS from Eclipse Temurin (Adoptium)
    # Try different identifier formats that SDKMAN supports:
    # - Short form (e.g., 17-tem) - lets SDKMAN pick latest 17.x
    # - Medium form (e.g., 17.0-tem) - more specific
    # If these fail, we'll show available versions

    if ! sdk install java 17-tem 2>/dev/null; then
        if ! sdk install java 17.0-tem 2>/dev/null; then
            echo ""
            warn "Could not install using generic identifiers. Available Java 17 versions:"
            sdk list java | grep -E "17\..*tem" | head -10 || echo "  (Could not list versions)"
            echo ""
            error "Failed to install Java 17. Please try manually with: sdk install java <version>"
        fi
    fi

    # Source SDKMAN again to ensure Java is available
    source "$SDKMAN_DIR/bin/sdkman-init.sh"

    if ! check_command java; then
        error "Java installation completed but java command not found. Please restart your shell and try again."
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1 2>/dev/null)

    # Validate version is numeric and >= 17
    if [[ ! "$JAVA_VERSION" =~ ^[0-9]+$ ]]; then
        error "Java installation completed but could not verify version. Please check your installation."
    fi

    if [[ "$JAVA_VERSION" -lt 17 ]]; then
        error "Java $JAVA_VERSION was installed but Java 17+ is required. Please try installing a newer version."
    fi

    info "Java $JAVA_VERSION installed successfully"
    echo ""
    echo -e "${GREEN}Note:${NC} After installation completes, you may need to restart your shell"
    echo "or source your shell configuration file for Java to be available everywhere."
    echo ""
}

check_prerequisites() {
    info "Checking prerequisites..."

    # Check curl
    if ! check_command curl; then
        error "curl is required but not installed. Please install curl and try again."
    fi

    # Verify curl actually works by checking its version
    if ! curl --version >/dev/null 2>&1; then
        error "curl command exists but is not functioning properly. Please reinstall curl and try again."
    fi

    # Check Java
    if ! check_command java; then
        install_java_via_sdkman
    fi

    # Check Java version - validate it exists and is numeric
    if ! check_command java; then
        error "Java installation failed or java command not found. Please install Java 17+ manually."
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1 2>/dev/null)

    # Validate that JAVA_VERSION is numeric
    if [[ ! "$JAVA_VERSION" =~ ^[0-9]+$ ]]; then
        error "Could not determine Java version. Please ensure Java 17+ is installed correctly."
    fi

    if [[ "$JAVA_VERSION" -lt 17 ]]; then
        echo ""
        echo -e "${YELLOW}Java $JAVA_VERSION is installed but Java 17+ is required.${NC}"
        echo ""
        echo "Would you like us to install Java 17?"
        echo "We will do it via an open source product called SDKMAN which is a version"
        echo "manager and will allow you to upgrade or downgrade later."
        echo ""
        read -p "Install Java 17 via SDKMAN? (y/n): " -n 1 -r
        echo ""

        if [[ $REPLY =~ ^[Yy]$ ]]; then
            install_java_via_sdkman
            # Re-check version after installation
            if ! check_command java; then
                error "Java installation failed or java command not found. Please install Java 17+ manually."
            fi
            JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1 2>/dev/null)
            if [[ ! "$JAVA_VERSION" =~ ^[0-9]+$ ]] || [[ "$JAVA_VERSION" -lt 17 ]]; then
                error "Java 17+ is required. Installation or verification failed. Please check your setup."
            fi
        else
            error "Java 17+ is required. Found Java $JAVA_VERSION. Please upgrade Java and try again."
        fi
    fi

    info "Prerequisites satisfied (Java $JAVA_VERSION found)"
}

get_latest_version() {
    info "Fetching latest release version..."

    LATEST=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null | \
        grep '"tag_name"' | \
        sed -E 's/.*"tag_name": *"([^"]+)".*/\1/' | \
        sed 's/^v//')

    if [[ -z "$LATEST" ]]; then
        error "Failed to fetch latest release. Check your internet connection or specify a version with -v."
    fi

    VERSION="$LATEST"
    info "Latest version: $VERSION"
}

download_jar() {
    info "Downloading zkemails v$VERSION..."

    mkdir -p "$BIN_DIR"

    # Construct download URL - assuming release asset is named zkemails-<version>-fat.jar
    DOWNLOAD_URL="https://github.com/$REPO/releases/download/$VERSION/zkemails-$VERSION-fat.jar"

    # Try downloading
    HTTP_CODE=$(curl -fsSL -w "%{http_code}" -o "$BIN_DIR/$JAR_NAME" "$DOWNLOAD_URL" 2>/dev/null || echo "000")

    if [[ "$HTTP_CODE" != "200" ]]; then
        # Try alternate naming pattern (without version in filename)
        DOWNLOAD_URL="https://github.com/$REPO/releases/download/$VERSION/zkemails-fat.jar"
        HTTP_CODE=$(curl -fsSL -w "%{http_code}" -o "$BIN_DIR/$JAR_NAME" "$DOWNLOAD_URL" 2>/dev/null || echo "000")
    fi

    if [[ "$HTTP_CODE" != "200" ]]; then
        rm -f "$BIN_DIR/$JAR_NAME"
        error "Failed to download release v$VERSION. HTTP status: $HTTP_CODE. Please verify the version exists."
    fi

    info "Downloaded to $BIN_DIR/$JAR_NAME"
}

create_wrapper() {
    info "Creating wrapper script..."

    WRAPPER="$BIN_DIR/zkemails"

    cat > "$WRAPPER" << 'EOF'
#!/bin/bash
exec java -jar "$HOME/.zkemails/bin/zkemails.jar" "$@"
EOF

    chmod +x "$WRAPPER"
    info "Created executable: $WRAPPER"
}

add_to_path_file() {
    local file="$1"
    local path_entry='export PATH="$HOME/.zkemails/bin:$PATH"'

    if [[ -f "$file" ]]; then
        if ! grep -q '.zkemails/bin' "$file" 2>/dev/null; then
            echo "" >> "$file"
            echo "# zkemails" >> "$file"
            echo "$path_entry" >> "$file"
            info "Added to PATH in $file"
            return 0
        else
            info "PATH already configured in $file"
            return 1
        fi
    fi
    return 1
}

setup_path() {
    info "Setting up PATH..."

    MODIFIED=0

    # Detect shell and update appropriate files
    case "$SHELL" in
        */zsh)
            if add_to_path_file "$HOME/.zshrc"; then
                MODIFIED=1
            fi
            ;;
        */bash)
            # On macOS, .bash_profile is typically used for login shells
            if [[ "$(uname)" == "Darwin" ]]; then
                if add_to_path_file "$HOME/.bash_profile"; then
                    MODIFIED=1
                fi
            fi
            if add_to_path_file "$HOME/.bashrc"; then
                MODIFIED=1
            fi
            ;;
        *)
            warn "Unknown shell: $SHELL. You may need to add $BIN_DIR to your PATH manually."
            ;;
    esac

    # Also try to cover both shells if files exist (user might switch)
    if [[ -f "$HOME/.zshrc" ]] && [[ "$SHELL" != */zsh ]]; then
        if add_to_path_file "$HOME/.zshrc"; then
            MODIFIED=1
        fi
    fi

    if [[ -f "$HOME/.bashrc" ]] && [[ "$SHELL" != */bash ]]; then
        if add_to_path_file "$HOME/.bashrc"; then
            MODIFIED=1
        fi
    fi

    if [[ $MODIFIED -eq 1 ]]; then
        warn "Please restart your shell or run: source ~/.$(basename $SHELL)rc"
    fi
}

print_success() {
    echo ""
    echo -e "${GREEN}Installation complete!${NC}"
    echo ""
    echo "zkemails v$VERSION has been installed to $BIN_DIR"
    echo ""
    echo "To get started, restart your terminal or run:"
    echo "  source ~/.$(basename $SHELL)rc"
    echo ""
    echo "Then try:"
    echo "  zkemails --help"
    echo "  zkemails init --email your@email.com --password"
    echo ""
}

main() {
    echo ""
    echo "====================================="
    echo "       zkemails installer"
    echo "====================================="
    echo ""

    parse_args "$@"
    check_prerequisites

    if [[ -z "$VERSION" ]]; then
        get_latest_version
    else
        info "Installing specified version: $VERSION"
    fi

    download_jar
    create_wrapper
    setup_path
    print_success
}

main "$@"
