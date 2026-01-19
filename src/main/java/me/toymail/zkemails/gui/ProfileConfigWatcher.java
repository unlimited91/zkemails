package me.toymail.zkemails.gui;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Watches the profile.config file for external changes (e.g., from CLI).
 * Notifies the GUI when the default profile changes externally.
 */
public class ProfileConfigWatcher {
    private static final Logger log = LoggerFactory.getLogger(ProfileConfigWatcher.class);

    private final Path configPath;
    private final Consumer<String> onProfileChanged;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread watcherThread;
    private WatchService watchService;
    private String lastKnownProfile;

    /**
     * Create a watcher for profile.config changes.
     * @param onProfileChanged callback invoked on JavaFX thread when profile changes externally
     */
    public ProfileConfigWatcher(Consumer<String> onProfileChanged) {
        String home = System.getProperty("user.home");
        this.configPath = Paths.get(home, ".zkemails", "profile.config");
        this.onProfileChanged = onProfileChanged;
    }

    /**
     * Set the current profile (to avoid triggering on our own changes).
     */
    public void setCurrentProfile(String profile) {
        this.lastKnownProfile = profile;
    }

    /**
     * Start watching for changes.
     */
    public void start() {
        if (running.get()) {
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path parentDir = configPath.getParent();

            if (!Files.exists(parentDir)) {
                log.warn("Profile config directory does not exist: {}", parentDir);
                return;
            }

            parentDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
            running.set(true);

            watcherThread = new Thread(this::watchLoop, "ProfileConfigWatcher");
            watcherThread.setDaemon(true);
            watcherThread.start();

            log.info("Started watching profile.config for external changes");
        } catch (IOException e) {
            log.error("Failed to start profile config watcher: {}", e.getMessage());
        }
    }

    /**
     * Stop watching.
     */
    public void stop() {
        running.set(false);

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.debug("Error closing watch service: {}", e.getMessage());
            }
        }

        if (watcherThread != null) {
            watcherThread.interrupt();
        }

        log.info("Stopped profile config watcher");
    }

    private void watchLoop() {
        while (running.get()) {
            try {
                WatchKey key = watchService.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path changedFile = pathEvent.context();

                    if ("profile.config".equals(changedFile.toString())) {
                        handleConfigChange();
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    log.warn("Watch key no longer valid, stopping watcher");
                    break;
                }
            } catch (InterruptedException e) {
                if (running.get()) {
                    log.debug("Watcher interrupted");
                }
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
        }
    }

    private void handleConfigChange() {
        try {
            // Small delay to ensure file write is complete
            Thread.sleep(100);

            String newProfile = readDefaultProfile();

            if (newProfile != null && !newProfile.equals(lastKnownProfile)) {
                log.info("External profile change detected: {} -> {}", lastKnownProfile, newProfile);

                Platform.runLater(() -> {
                    onProfileChanged.accept(newProfile);
                });
            }
        } catch (Exception e) {
            log.debug("Error reading profile config: {}", e.getMessage());
        }
    }

    private String readDefaultProfile() {
        try {
            if (!Files.exists(configPath)) {
                return null;
            }

            String content = Files.readString(configPath);
            // Simple JSON parsing - look for "default" : "email"
            int idx = content.indexOf("\"default\"");
            if (idx < 0) return null;

            int colonIdx = content.indexOf(":", idx);
            if (colonIdx < 0) return null;

            int startQuote = content.indexOf("\"", colonIdx + 1);
            if (startQuote < 0) return null;

            int endQuote = content.indexOf("\"", startQuote + 1);
            if (endQuote < 0) return null;

            return content.substring(startQuote + 1, endQuote);
        } catch (IOException e) {
            return null;
        }
    }
}
