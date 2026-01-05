# zkemails
Email client + protocol scaffolding for "key gossip + TOFU" E2E emails.

## Installation

### Quick Install (Recommended)

```bash
curl -fsSL https://raw.githubusercontent.com/unlimited91/zkemails/main/install.sh | bash
```

This will:
- Check for Java 17+ or offer to install it via SDKMAN
- Download the latest zkemails release
- Create a wrapper script in `~/.zkemails/bin/zkemails`
- Add to your PATH automatically

After installation:
```bash
zkemails --help
zkemails init --email you@gmail.com --password 

```
### Points to consider
This password is not your gmail password. Gmail has a concept called App passwords. You need to enable 2FA for your account, create an app password and then use it here.
You can create your app password [here](https://myaccount.google.com/apppasswords).
Keep in mind that if you see a No access page. You have to enable 2FA for your account and try again.
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

## Prereqs
- Java 17+
- Maven 3.9+
- Gmail App Password (recommended for this toy)

## Run

### Using Installed Version
If you installed via the installer script:
```bash
zkemails --help
zkemails init --email you@gmail.com --password
zkemails inbox --password --limit 20
```

### Development/Maven Build
For development or if building from source:
```bash
mvn -q exec:java -Dexec.args="--help"
```

Or with the fat JAR:
```bash
java -jar target/zkemails-*-fat.jar --help
```

## Commands

All commands can be run either:
- **Installed version**: `zkemails <command> [args]`
- **Development**: `mvn -q exec:java -Dexec.args="<command> [args]"`
- **Fat JAR**: `java -jar target/zkemails-*-fat.jar <command> [args]`

Examples below use the Maven format for development. Replace with `zkemails` if installed.

### 1) Init (auth-gated)
Authenticates to IMAP+SMTP; only then writes `~/.zkemails/config.json` and generates `~/.zkemails/keys.json`.

```bash
# Installed:
zkemails init --email you@gmail.com --password

# Development:
mvn -q exec:java -Dexec.args="init --email you@gmail.com --password"
```

Override hosts/ports:
```bash
zkemails init --email you@corp.com --password --imap-host outlook.office365.com --imap-port 993 --smtp-host smtp.office365.com --smtp-port 587
```

### 2) Inbox listing
```bash
# Installed:
zkemails inbox --password --limit 20

# Development:
mvn -q exec:java -Dexec.args="inbox --password --limit 20"
```

Filter by header:
```bash
zkemails inbox --password --header-name X-ZKEmails-Type --header-value invite --limit 50
```

### 3) Send an invite
Sends a plaintext invite with protocol headers. Also stores outgoing invite in `~/.zkemails/invites.json` (direction=out).

```bash
# Installed:
zkemails send-invite --to friend@gmail.com --password

# Development:
mvn -q exec:java -Dexec.args="send-invite --to friend@gmail.com --password"
```

### 4) List pending incoming invites (local)
`ls invi` shows locally-cached incoming invites that are not acked yet.
(In this toy, incoming invites get cached when you `ack invi`. A future `sync invi` could cache all.)

```bash
zkemails ls invi
```

### 5) Ack an invite (send accept + mark local acked)
Finds the invite email by header `X-ZKEmails-Invite-Id`, sends an accept reply with key-gossip headers,
then marks it acked locally so it no longer appears in `ls invi`.

```bash
zkemails ack invi --invite-id <UUID> --password
```

### 6) List acked invites
```bash
zkemails lsa invi
```

## Local files
Created under `~/.zkemails/`:
- config.json (no passwords stored)
- keys.json (toy: plaintext private keys; do NOT commit)
- invites.json (local cache for outgoing/incoming invites)
- outbox/ (future)

# Testing Commands from the IDE

To test each command from your IDE (such as IntelliJ IDEA or Eclipse):

1. **Locate the Main Class**  
   The entry point is typically `me.toymail.zkemails.ZkEmails`.

2. **Run/Debug Configuration**  
   - Right-click on the `ZkEmails` class and select "Run" or "Debug".
   - Edit the Run Configuration to add command-line arguments for the command you want to test.  
     For example:
     - `init` to initialize a profile
     - `prof ls` to list profiles
     - `prof user@example.com` to switch profile
     - `ackinvi <invitationId>` to acknowledge an invitation

3. **Example Arguments**  
   - To test init:  
     ```
     init
     ```
   - To test listing profiles:  
     ```
     prof ls
     ```
   - To test switching profile:  
     ```
     prof user@example.com
     ```
   - To test ackinvi:  
     ```
     ackinvi INVITATION_ID
     ```

4. **View Output**  
   The output and any errors will appear in the IDE's console.

5. **Repeat**  
   Change the arguments as needed to test other commands.

> Tip: You can create multiple run configurations for different commands to speed up testing.
