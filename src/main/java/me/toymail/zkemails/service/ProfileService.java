package me.toymail.zkemails.service;

import me.toymail.zkemails.store.StoreContext;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Service for managing user profiles (email accounts).
 */
public final class ProfileService {
    private final StoreContext context;

    public ProfileService(StoreContext context) {
        this.context = context;
    }

    /**
     * List all configured profiles.
     * @return list of profile emails, empty if none configured
     */
    public List<String> listProfiles() throws IOException {
        if (!context.profileConfig().exists()) {
            return Collections.emptyList();
        }
        return context.profileConfig().listProfiles();
    }

    /**
     * Get the currently active profile email.
     * @return active profile email, or null if none set
     */
    public String getActiveProfile() throws IOException {
        if (!context.profileConfig().exists()) {
            return null;
        }
        return context.profileConfig().getDefault();
    }

    /**
     * Check if a profile exists.
     * @param email the profile email to check
     * @return true if profile exists
     */
    public boolean hasProfile(String email) throws IOException {
        return listProfiles().contains(email);
    }

    /**
     * Check if any active profile is set.
     * @return true if an active profile is configured
     */
    public boolean hasActiveProfile() {
        return context.hasActiveProfile();
    }

    /**
     * Switch to a different profile.
     * @param email the profile email to switch to
     * @throws IllegalArgumentException if profile doesn't exist
     */
    public void switchProfile(String email) throws IOException {
        if (!hasProfile(email)) {
            throw new IllegalArgumentException("Profile not found: " + email);
        }
        context.switchProfile(email);
    }

    /**
     * Check if profiles exist.
     * @return true if at least one profile is configured
     */
    public boolean hasProfiles() throws IOException {
        return context.profileConfig().exists() && !listProfiles().isEmpty();
    }
}
