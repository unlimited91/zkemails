# zke (Zero Knowledge Emails)

End-to-end encrypted email client using key gossip + TOFU (Trust On First Use).

## Installation

### Quick Install (Recommended)

```bash
curl -fsSL https://raw.githubusercontent.com/unlimited91/zkemails/main/install.sh | bash
```

This will:
- Check for Java 17+ or offer to install it via SDKMAN
- Download the latest zke release
- Create a wrapper script in `~/.zkemails/bin/zke`
- Add to your PATH automatically

After installation:
```bash
zke --help
zke init --email you@gmail.com
```

### Gmail App Password Setup

This password is **not** your Gmail password. Gmail requires an App Password:

1. Enable 2FA for your account at [Google Account Security](https://myaccount.google.com/)
2. Create an app password at [App Passwords](https://myaccount.google.com/apppasswords)
3. Use the 16-character password (remove spaces)

### Manual Install

1. Download the installer:
   ```bash
   curl -fsSL https://raw.githubusercontent.com/unlimited91/zkemails/main/install.sh -o install.sh
   chmod +x install.sh
   ```

2. Run the installer:
   ```bash
   ./install.sh
   ```

3. Optionally specify a version:
   ```bash
   ./install.sh --version 0.0.1.beta1
   ```

### Development Install

For development, build from source:

```bash
git clone https://github.com/unlimited91/zkemails.git
cd zkemails
mvn clean package
```

Then run with:
```bash
java -jar target/zkemails-*-fat.jar --help
```

## Prerequisites

- Java 17+
- Maven 3.9+ (for development)
- Gmail App Password (recommended)

## Quick Start

```bash
# 1. Initialize with your email
zke init --email you@gmail.com

# 2. Invite a friend to start encrypted chat
zke invite --to friend@gmail.com

# 3. After they accept, sync their keys
zke sync-ack

# 4. Send encrypted messages
zke sem --to friend@gmail.com

# 5. Read encrypted messages
zke rem
```

## Commands

| Command | Description |
|---------|-------------|
| `zke init` | Initialize with your email account |
| `zke invite` | Send an invite to start encrypted chat |
| `zke sem` | Send an encrypted message |
| `zke rem` | Read encrypted messages (list, view, thread, reply) |
| `zke inbox` | List emails from your inbox |
| `zke lsi` | List pending incoming invites |
| `zke lsia` | List acknowledged invites |
| `zke ack invi` | Acknowledge an invite |
| `zke sync-ack` | Sync ACCEPT messages and import keys |
| `zke lsp` | List profiles |
| `zke pset` | Set active profile |
| `zke credential` | Manage stored credentials |

Use `zke <command> --help` for detailed usage of any command.

### Examples

#### Initialize
```bash
zke init --email you@gmail.com
```

#### Send an Invite
```bash
zke invite --to friend@gmail.com
```

#### Acknowledge an Invite
```bash
# List pending invites to find the invite ID
zke lsi

# Accept the invite
zke ack invi --invite-id <UUID>
```

#### Send Encrypted Message
```bash
# Opens editor to compose message
zke sem

# Pre-fill recipient
zke sem --to friend@gmail.com
```

#### Read Encrypted Messages
```bash
# List recent encrypted messages
zke rem

# View a specific message
zke rem --message 42

# View entire conversation thread
zke rem --thread 42

# Reply to a message
zke rem --reply 42
```

#### Manage Profiles
```bash
# List all profiles
zke lsp

# Switch to a different profile
zke pset other@gmail.com
```

## Development

### Running from Source

```bash
# Using Maven
mvn -q exec:java -Dexec.args="--help"

# Using fat JAR
java -jar target/zkemails-*-fat.jar --help
```

### Testing from IDE

1. **Main Class**: `me.toymail.zkemails.CliMain`

2. **Run Configuration**: Add command-line arguments for the command to test:
   - `init --email test@example.com`
   - `lsp`
   - `pset user@example.com`
   - `ack invi --invite-id <UUID>`

3. View output in the IDE's console.

## Local Files

Created under `~/.zkemails/<email>/`:
- `config.json` - IMAP/SMTP configuration (no passwords)
- `keys.json` - Ed25519/X25519 key pair (keep private!)
- `contacts.json` - Public keys of your contacts
- `invites.json` - Local cache for outgoing/incoming invites

Profile configuration: `~/.zkemails/profile.config`
