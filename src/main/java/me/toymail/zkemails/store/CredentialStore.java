package me.toymail.zkemails.store;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Stores email credentials in the system keychain.
 * Uses macOS Keychain, Linux Secret Service (GNOME Keyring/KWallet), or Windows Credential Manager.
 */
public final class CredentialStore {
    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);
    private static final String SERVICE_NAME = "zkemails";

    private final Keyring keyring;
    private final boolean available;

    public CredentialStore() {
        Keyring kr = null;
        boolean avail = false;
        try {
            kr = Keyring.create();
            avail = true;
            log.debug("System keyring initialized successfully");
        } catch (BackendNotSupportedException e) {
            log.debug("System keyring not available: {}", e.getMessage());
        }
        this.keyring = kr;
        this.available = avail;
    }

    public boolean isAvailable() {
        return available;
    }

    public Optional<String> getPassword(String email) {
        if (!available) {
            return Optional.empty();
        }
        try {
            String password = keyring.getPassword(SERVICE_NAME, email);
            if (password != null) {
                log.debug("Retrieved password from keyring for {}", email);
            }
            return Optional.ofNullable(password);
        } catch (PasswordAccessException e) {
            log.debug("Failed to retrieve password from keyring for {}: {}", email, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean setPassword(String email, String password) {
        if (!available) {
            return false;
        }
        try {
            keyring.setPassword(SERVICE_NAME, email, password);
            log.debug("Saved password to keyring for {}", email);
            return true;
        } catch (PasswordAccessException e) {
            log.warn("Failed to save password to keyring for {}: {}", email, e.getMessage());
            return false;
        }
    }

    public boolean deletePassword(String email) {
        if (!available) {
            return false;
        }
        try {
            keyring.deletePassword(SERVICE_NAME, email);
            log.debug("Deleted password from keyring for {}", email);
            return true;
        } catch (PasswordAccessException e) {
            log.debug("Failed to delete password from keyring for {}: {}", email, e.getMessage());
            return false;
        }
    }
}
