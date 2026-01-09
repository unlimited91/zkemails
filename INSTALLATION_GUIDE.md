# ZKE (Zero Knowledge Emails) - Installation Guide

End-to-end encrypted email using your existing Gmail account. No new email address needed.

---

## Table of Contents

1. [What is ZKE?](#what-is-zke)
2. [Prerequisites](#prerequisites)
3. [Installation](#installation)
4. [Gmail App Password Setup](#gmail-app-password-setup)
5. [Getting Started](#getting-started)
6. [How It Works](#how-it-works)
7. [Troubleshooting](#troubleshooting)

---

## What is ZKE?

ZKE (Zero Knowledge Emails) is a privacy-focused email client that adds end-to-end encryption to your existing Gmail account. Your messages are encrypted on your device before being sent, and only the intended recipient can decrypt them.

**Key Features:**
- Works with your existing Gmail account
- End-to-end encryption (only you and your recipient can read messages)
- Both GUI and command-line interfaces available
- Multi-profile support for multiple email accounts
- No servers involved - encryption happens locally

---

## Prerequisites

### Required
- **Java 17 or higher** - The installer will help you install this if needed
- **Gmail account** with 2-Factor Authentication enabled
- **Gmail App Password** (not your regular Gmail password)

### Supported Platforms
- macOS (Intel and Apple Silicon)
- Linux (Ubuntu, Debian, Fedora, etc.)
- Windows (via WSL)

---

## Installation

### Quick Install (Recommended)

Open your terminal and run:

```bash
curl -fsSL https://raw.githubusercontent.com/unlimited91/zkemails/main/install.sh | bash
```

This will:
1. Check for Java 17+ (and offer to install via SDKMAN if missing)
2. Download the latest ZKE release
3. Create the `zke` command in your PATH

After installation, restart your terminal or run:
```bash
source ~/.zshrc    # for zsh
source ~/.bashrc   # for bash
```

### Verify Installation

```bash
zke --help
```

You should see the help menu with available commands.

### Manual Install

If you prefer to install manually:

1. Download the installer script:
   ```bash
   curl -fsSL https://raw.githubusercontent.com/unlimited91/zkemails/main/install.sh -o install.sh
   chmod +x install.sh
   ```

2. Run the installer:
   ```bash
   ./install.sh
   ```

3. Or install a specific version:
   ```bash
   ./install.sh -v 1.0.0.beta1
   ```

---

## Gmail App Password Setup

**Important:** ZKE requires a Gmail App Password, not your regular Gmail password.

### Step 1: Enable 2-Factor Authentication

1. Go to [Google Account Security](https://myaccount.google.com/security)
2. Under "How you sign in to Google", click **2-Step Verification**
3. Follow the prompts to enable 2FA if not already enabled

### Step 2: Create an App Password

1. Go to [App Passwords](https://myaccount.google.com/apppasswords)
   - If you don't see this option, 2FA may not be enabled
2. Click **Select app** and choose "Mail"
3. Click **Select device** and choose "Other (Custom name)"
4. Enter "ZKE" as the name
5. Click **Generate**
6. Copy the 16-character password (looks like: `xxxx xxxx xxxx xxxx`)
7. Remove the spaces when using it in ZKE

**Keep this password safe!** You'll need it to initialize ZKE.

---

## Getting Started

### Option 1: GUI Mode (Recommended for beginners)

Launch the graphical interface:

```bash
zke gui
```

The GUI provides:
- Inbox view with encrypted messages
- Compose new encrypted messages
- Manage contacts and invites
- Multi-profile support

### Option 2: CLI Mode (For terminal lovers)

#### Initialize Your Profile

```bash
zke init --email your.email@gmail.com
```

You'll be prompted for your Gmail App Password.

#### Invite a Contact

Before you can exchange encrypted messages, you need to exchange keys with your contact:

```bash
zke invite --to friend@gmail.com
```

This sends an invitation email. Once they accept, you can exchange encrypted messages.

#### Accept an Invite

When someone invites you:

```bash
# List pending invites
zke lsi

# Accept an invite
zke ack invi --invite-id <INVITE-UUID>
```

#### Sync Keys

After your contact accepts your invite, sync their public key:

```bash
zke sync-ack
```

#### Send Encrypted Messages

```bash
# Opens an editor to compose
zke sem --to friend@gmail.com

# Or specify subject
zke sem --to friend@gmail.com --subject "Secret plans"
```

#### Read Encrypted Messages

```bash
# List encrypted messages
zke rem

# Read a specific message
zke rem --message 42

# View entire conversation thread
zke rem --thread 42

# Reply to a message
zke rem --reply 42
```

---

## How It Works

### Key Exchange (TOFU - Trust On First Use)

1. **You send an invite** - ZKE generates a unique key pair and sends your public key
2. **They accept** - They generate their keys and send their public key back
3. **Keys are exchanged** - Now you can encrypt messages for each other

### Encryption

- Messages are encrypted using your contact's public key
- Only they can decrypt it with their private key
- Your private key never leaves your device

### Local Storage

All data is stored locally in `~/.zkemails/`:
- `config.json` - Email server configuration
- `keys.json` - Your encryption keys (keep private!)
- `contacts.json` - Public keys of your contacts
- `inbox/` - Cached encrypted messages
- `outbox/` - Sent messages

---

## Troubleshooting

### "Java not found" error

The installer should offer to install Java via SDKMAN. If it fails:

```bash
# Install SDKMAN
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install Java 17
sdk install java 17-tem
```

### "Authentication failed" error

1. Make sure you're using a **Gmail App Password**, not your regular password
2. Verify 2FA is enabled on your Google account
3. Try generating a new App Password

### "Connection refused" error

Check your internet connection and firewall settings. ZKE needs access to:
- `imap.gmail.com:993` (reading emails)
- `smtp.gmail.com:587` (sending emails)

### GUI doesn't start

Make sure you have Java 17+ with JavaFX support:

```bash
java -version
```

If using SDKMAN, install a JDK with JavaFX:
```bash
sdk install java 17.0.9-zulu
```

### Messages not decrypting

- Verify the sender is in your contacts: `zke lsc`
- Make sure you've completed the key exchange: `zke sync-ack`

### Need more help?

- Check logs in `~/.zkemails/logs/`
- Run commands with `--verbose` flag for more details
- Open an issue at [GitHub Issues](https://github.com/unlimited91/zkemails/issues)

---

## Command Reference

| Command | Description |
|---------|-------------|
| `zke gui` | Launch graphical interface |
| `zke init` | Initialize with your email |
| `zke invite` | Send invite to start encrypted chat |
| `zke sem` | Send encrypted message |
| `zke rem` | Read encrypted messages |
| `zke lsi` | List pending invites |
| `zke ack invi` | Accept an invite |
| `zke sync-ack` | Sync keys from accepted invites |
| `zke lsc` | List contacts |
| `zke lsp` | List profiles |
| `zke pset` | Switch active profile |

Use `zke <command> --help` for detailed options.

---

## Uninstall

To remove ZKE:

```bash
rm -rf ~/.zkemails
```

Remove the PATH entry from your `~/.zshrc` or `~/.bashrc` if desired.

---

**Questions or feedback?** Open an issue at https://github.com/unlimited91/zkemails/issues
