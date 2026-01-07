package me.toymail.zkemails.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Initializes logging configuration for zkemails.
 * Must be called BEFORE any logging occurs (before SLF4J LoggerFactory is used).
 */
public final class LoggingConfig {

    private static final String LOG_DIR_PROPERTY = "zkemails.log.dir";
    private static boolean initialized = false;

    private LoggingConfig() {}

    /**
     * Initialize logging directory and set system property.
     * Safe to call multiple times - only initializes once.
     */
    public static void init() {
        if (initialized) return;

        Path logDir = Path.of(System.getProperty("user.home"), ".zkemails", "logs");
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            System.err.println("Warning: Could not create log directory: " + logDir);
        }

        System.setProperty(LOG_DIR_PROPERTY, logDir.toString());
        initialized = true;
    }
}
