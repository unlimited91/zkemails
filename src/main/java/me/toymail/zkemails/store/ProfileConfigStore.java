package me.toymail.zkemails.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public final class ProfileConfigStore {
    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path configPath;

    public ProfileConfigStore() {
        String home = System.getProperty("user.home");
        this.configPath = Paths.get(home, ".zkemails", "profile.config");
    }

    public ProfileConfig read() throws IOException {
        if (!Files.exists(configPath)) {
            return new ProfileConfig();
        }
        return M.readValue(configPath.toFile(), ProfileConfig.class);
    }

    public void write(ProfileConfig config) throws IOException {
        Files.createDirectories(configPath.getParent());
        M.writeValue(configPath.toFile(), config);
    }

    public List<String> listProfiles() throws IOException {
        return read().profiles;
    }

    public String getDefault() throws IOException {
        return read().defaultProfile;
    }

    public void setDefault(String email) throws IOException {
        ProfileConfig config = read();
        if (!config.profiles.contains(email)) {
            throw new IllegalArgumentException("Profile not found: " + email);
        }
        config.defaultProfile = email;
        write(config);
    }

    public void addProfile(String email) throws IOException {
        ProfileConfig config = read();
        if (!config.profiles.contains(email)) {
            config.profiles.add(email);
        }
        config.defaultProfile = email;
        write(config);
    }

    public boolean hasProfile(String email) throws IOException {
        return read().profiles.contains(email);
    }

    public boolean exists() {
        return Files.exists(configPath);
    }
}
