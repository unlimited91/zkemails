package me.toymail.zkemails;

import me.toymail.zkemails.store.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;
import java.util.Optional;

/**
 * Resolves passwords with fallback chain: explicit argument > keyring > interactive prompt.
 */
public final class PasswordResolver {
    private static final Logger log = LoggerFactory.getLogger(PasswordResolver.class);

    private final CredentialStore credentialStore;

    public PasswordResolver(CredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    /**
     * Resolve password with priority: explicit > keyring > interactive prompt.
     *
     * @param explicitPassword Password from --password flag (may be null)
     * @param email            The email account
     * @param console          Console for interactive prompt (may be null in non-interactive mode)
     * @return Resolved password
     * @throws IllegalStateException if no password available and no console for input
     */
    public String resolve(String explicitPassword, String email, Console console) {
        // 1. Explicit password takes priority
        if (explicitPassword != null && !explicitPassword.isBlank()) {
            log.debug("Using explicit password for {}", email);
            return explicitPassword;
        }

        // 2. Try keyring
        Optional<String> stored = credentialStore.getPassword(email);
        if (stored.isPresent()) {
            log.debug("Using password from keyring for {}", email);
            return stored.get();
        }

        // 3. Fall back to interactive prompt
        if (console == null) {
            throw new IllegalStateException(
                "No password available for " + email + " and no console for interactive input. " +
                "Use --password flag or save password to keychain with 'zkemails init'."
            );
        }

        log.debug("Prompting for password for {}", email);
        char[] pw = console.readPassword("Password for %s: ", email);
        if (pw == null) {
            throw new IllegalStateException("Password input cancelled");
        }
        return new String(pw);
    }
}
