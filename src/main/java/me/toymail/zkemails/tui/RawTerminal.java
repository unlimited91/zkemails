package me.toymail.zkemails.tui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Manages terminal raw mode for reading individual keystrokes.
 * Uses stty on Unix-like systems to disable line buffering and echo.
 */
public final class RawTerminal implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RawTerminal.class);

    private final String originalSettings;
    private final boolean isWindows;
    private boolean closed = false;

    private RawTerminal(String originalSettings, boolean isWindows) {
        this.originalSettings = originalSettings;
        this.isWindows = isWindows;
    }

    /**
     * Enable raw terminal mode for reading individual keystrokes.
     * @return A RawTerminal instance that must be closed to restore settings
     * @throws IOException if terminal mode cannot be changed
     */
    public static RawTerminal enable() throws IOException {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        if (isWindows) {
            // Windows doesn't support stty, return a no-op instance
            log.debug("Windows detected, raw mode not fully supported");
            return new RawTerminal(null, true);
        }

        // Save current terminal settings
        String originalSettings = runStty("-g");
        if (originalSettings == null || originalSettings.isEmpty()) {
            throw new IOException("Failed to save terminal settings");
        }

        // Enable raw mode: disable canonical mode and echo
        try {
            runSttyNoOutput("raw", "-echo", "min", "1", "time", "0");
        } catch (IOException e) {
            throw new IOException("Failed to enable raw terminal mode: " + e.getMessage(), e);
        }

        log.debug("Terminal raw mode enabled");
        return new RawTerminal(originalSettings.trim(), false);
    }

    /**
     * Check if the current terminal supports raw mode.
     */
    public static boolean isSupported() {
        if (System.console() == null) {
            return false;
        }
        return !System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Restore original terminal settings.
     */
    @Override
    public void close() {
        if (closed || isWindows || originalSettings == null) {
            return;
        }

        try {
            runSttyNoOutput(originalSettings);
            log.debug("Terminal settings restored");
        } catch (IOException e) {
            log.warn("Failed to restore terminal settings: {}", e.getMessage());
        }
        closed = true;
    }

    /**
     * Run stty command and return output.
     */
    private static String runStty(String... args) throws IOException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "stty";
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("stty exited with code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("stty interrupted", e);
        }

        return output;
    }

    /**
     * Run stty command without capturing output.
     */
    private static void runSttyNoOutput(String... args) throws IOException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "stty";
        System.arraycopy(args, 0, cmd, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();

        Process process = pb.start();

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("stty exited with code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("stty interrupted", e);
        }
    }

    /**
     * Get terminal height in rows.
     */
    public static int getTerminalHeight() {
        try {
            ProcessBuilder pb = new ProcessBuilder("tput", "lines");
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Integer.parseInt(result);
        } catch (Exception e) {
            return 24; // Default terminal height
        }
    }

    /**
     * Get terminal width in columns.
     */
    public static int getTerminalWidth() {
        try {
            ProcessBuilder pb = new ProcessBuilder("tput", "cols");
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            String result = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Integer.parseInt(result);
        } catch (Exception e) {
            return 80; // Default terminal width
        }
    }
}
