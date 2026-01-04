# zkemails (toy)
A toy email client + protocol scaffolding for "key gossip + TOFU" E2E email experiments.

## Prereqs
- Java 17+
- Maven 3.9+
- Gmail App Password (recommended for this toy)

## Run
```bash
mvn -q exec:java -Dexec.args="--help"
```

## Commands

### 1) Init (auth-gated)
Authenticates to IMAP+SMTP; only then writes `~/.zkemails/config.json` and generates `~/.zkemails/keys.json`.

```bash
mvn -q exec:java -Dexec.args="init --email you@gmail.com --password"
```

Override hosts/ports:
```bash
mvn -q exec:java -Dexec.args="init --email you@corp.com --password --imap-host outlook.office365.com --imap-port 993 --smtp-host smtp.office365.com --smtp-port 587"
```

### 2) Inbox listing
```bash
mvn -q exec:java -Dexec.args="inbox --password --limit 20"
```

Filter by header:
```bash
mvn -q exec:java -Dexec.args='inbox --password --header-name X-ZKEmails-Type --header-value invite --limit 50'
```

### 3) Send an invite
Sends a plaintext invite with protocol headers. Also stores outgoing invite in `~/.zkemails/invites.json` (direction=out).

```bash
mvn -q exec:java -Dexec.args="send-invite --to friend@gmail.com --password"
```

### 4) List pending incoming invites (local)
`ls invi` shows locally-cached incoming invites that are not acked yet.
(In this toy, incoming invites get cached when you `ack invi`. A future `sync invi` could cache all.)

```bash
mvn -q exec:java -Dexec.args="ls invi"
```

### 5) Ack an invite (send accept + mark local acked)
Finds the invite email by header `X-ZKEmails-Invite-Id`, sends an accept reply with key-gossip headers,
then marks it acked locally so it no longer appears in `ls invi`.

```bash
mvn -q exec:java -Dexec.args="ack invi --invite-id <UUID> --password"
```

### 6) List acked invites
```bash
mvn -q exec:java -Dexec.args="lsa invi"
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
