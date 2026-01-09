package me.toymail.zkemails.service;

import me.toymail.zkemails.store.StoreContext;

import java.util.Optional;

/**
 * Service for managing credentials in the system keychain.
 */
public final class CredentialService {
    private final StoreContext context;

    public CredentialService(StoreContext context) {
        this.context = context;
    }

    /**
     * Check if system keychain is available.
     * @return true if keychain is available on this system
     */
    public boolean isKeychainAvailable() {
        return context.credentials().isAvailable();
    }

    /**
     * Check if a password is stored for the given email.
     * @param email the profile email
     * @return true if password is stored
     */
    public boolean hasStoredPassword(String email) {
        return context.credentials().getPassword(email).isPresent();
    }

    /**
     * Get stored password for the given email.
     * @param email the profile email
     * @return the password if stored, empty otherwise
     */
    public Optional<String> getStoredPassword(String email) {
        return context.credentials().getPassword(email);
    }

    /**
     * Save password to system keychain.
     * @param email the profile email
     * @param password the password to store
     * @return true if saved successfully
     */
    public boolean savePassword(String email, String password) {
        return context.credentials().setPassword(email, password);
    }

    /**
     * Delete password from system keychain.
     * @param email the profile email
     * @return true if deleted, false if no password was stored
     */
    public boolean deletePassword(String email) {
        return context.credentials().deletePassword(email);
    }

    /**
     * Resolve password from explicit value, keychain, or return empty.
     * @param explicitPassword password provided by user (may be null)
     * @param email the profile email
     * @return resolved password, or empty if none available
     */
    public Optional<String> resolvePassword(String explicitPassword, String email) {
        if (explicitPassword != null && !explicitPassword.isBlank()) {
            return Optional.of(explicitPassword);
        }
        return getStoredPassword(email);
    }
}
