# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ZKE (Zero Knowledge Emails) is a Java-based end-to-end encrypted email client that adds encryption on top of existing Gmail accounts. It uses key gossip and TOFU (Trust On First Use) for key exchange. Users can exchange encrypted messages while using standard email infrastructure - no new email addresses needed, all encryption/decryption happens locally.

## Build & Run Commands

```bash
# Build (creates fat JAR with all dependencies)
mvn clean package

# Run CLI from source
mvn -q exec:java -Dexec.args="--help"

# Run CLI from fat JAR
java -jar target/zkemails-*-fat.jar --help

# Run GUI
mvn javafx:run
```

## Testing

```bash
# Run all Maven tests
mvn test

# Run a specific test class
mvn test -Dtest=CryptoBoxTest

# Run a specific test method
mvn test -Dtest=CryptoBoxTest#testEncryptDecrypt

# Run BATS shell tests (requires bats-core)
./test/run_tests.sh              # All tests
./test/run_tests.sh unit         # Unit only (fast, mocked)
./test/run_tests.sh integration  # Integration only (uses Docker)
```

## Architecture

### Layered Context Pattern

The application uses a two-layer context pattern for dependency management:

```
Entry Points (CliMain / ZkeGuiApplication)
    ↓
StoreContext (Persistence layer)
    ├─ ProfileConfigStore (multi-profile management)
    ├─ CredentialStore (system keychain integration)
    ├─ ZkStore (per-profile local storage)
    ├─ ContactsStore, InviteStore, SentStore, InboxStore
    └─ PasswordResolver
    ↓
ServiceContext (Business logic layer)
    ├─ ProfileService, CredentialService
    ├─ ContactService, InviteService
    ├─ MessageService, InitService
    ↓
Commands (PicoCLI) / Controllers (JavaFX)
```

- `StoreContext.initialize()` creates persistence layer
- `ServiceContext.create()` or `ServiceContext.fromStoreContext()` creates business logic layer
- Commands receive context via `StoreAwareFactory` (PicoCLI injection)

### Package Structure

- `me.toymail.zkemails.commands` - CLI commands (`*Cmd.java` suffix)
- `me.toymail.zkemails.service` - Business logic (`*Service.java` suffix)
- `me.toymail.zkemails.store` - Persistence (`*Store.java` suffix)
- `me.toymail.zkemails.crypto` - Encryption (`CryptoBox`, `IdentityKeys`)
- `me.toymail.zkemails.gui` - JavaFX application and controllers
- `me.toymail.zkemails.tui` - Terminal UI (console menus, message editor)

### Cryptography

Implemented in `CryptoBox.java`:
- **Message Encryption**: AES-256-GCM
- **Key Exchange**: X25519 ECDH
- **Key Derivation**: HKDF-SHA256
- **Signing**: Ed25519
- V2 payload format supports multi-recipient with shared ciphertext and per-recipient wrapped keys

### Local Storage

Profile data stored in `~/.zkemails/<email>/`:
- `config.json` - IMAP/SMTP configuration
- `keys.json` - Ed25519/X25519 key pair
- `contacts.json` - Public keys of contacts
- `invites.json` - Invite cache
- `inbox/`, `outbox/` - Message caches

Profile configuration: `~/.zkemails/profile.config`

## Key Dependencies

- **Java 17+** (source/target)
- **JavaFX 21.0.2** - GUI framework
- **PicoCLI 4.7.6** - CLI framework
- **BouncyCastle 1.79** - Cryptography
- **Jakarta Mail/Angus 2.0.3** - IMAP/SMTP
- **Jackson 2.17.2** - JSON processing
- **java-keyring 1.0.4** - System keychain (macOS Keychain, Linux Secret Service, Windows Credential Manager)
- **JUnit 5 + Mockito** - Testing

## Development Guidelines

- **Plan before implementing**: Present a design plan with pros and cons before writing code. Get approval on the approach first.
- **Modular code**: Keep classes focused and single-purpose. Follow the existing layered architecture (Store → Service → Command/Controller).
- **Testing**: Write tests for new functionality. Follow existing test patterns in `src/test/java/`.
- **Comments**: Add comments only where logic isn't self-evident. Avoid over-commenting obvious code.
