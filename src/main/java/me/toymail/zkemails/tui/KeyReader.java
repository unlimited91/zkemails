package me.toymail.zkemails.tui;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads and interprets individual keystrokes from the terminal.
 * Handles arrow key escape sequences and special keys.
 */
public final class KeyReader {

    /**
     * Recognized key types.
     */
    public enum Key {
        UP,
        DOWN,
        LEFT,
        RIGHT,
        ENTER,
        ESCAPE,
        BACKSPACE,
        QUIT,       // 'q' or 'Q'
        REPLY,      // 'r' or 'R'
        CHAR,       // Regular character
        UNKNOWN
    }

    /**
     * A key event with the key type and optional character value.
     */
    public record KeyEvent(Key key, char character) {
        public static KeyEvent of(Key key) {
            return new KeyEvent(key, '\0');
        }

        public static KeyEvent ofChar(char c) {
            return new KeyEvent(Key.CHAR, c);
        }
    }

    private final InputStream input;

    public KeyReader() {
        this.input = System.in;
    }

    public KeyReader(InputStream input) {
        this.input = input;
    }

    /**
     * Reads a single key event from the terminal.
     * Blocks until a key is pressed.
     * Handles escape sequences for arrow keys.
     */
    public KeyEvent readKey() throws IOException {
        int b = input.read();
        if (b == -1) {
            return KeyEvent.of(Key.UNKNOWN);
        }

        // ESC sequence - could be arrow key or standalone ESC
        if (b == 27) {
            return handleEscapeSequence();
        }

        // Enter key (LF or CR)
        if (b == 10 || b == 13) {
            return KeyEvent.of(Key.ENTER);
        }

        // Backspace (BS or DEL)
        if (b == 8 || b == 127) {
            return KeyEvent.of(Key.BACKSPACE);
        }

        // Vim-style navigation
        if (b == 'j' || b == 'J') {
            return KeyEvent.of(Key.DOWN);
        }
        if (b == 'k' || b == 'K') {
            return KeyEvent.of(Key.UP);
        }

        // Quit keys
        if (b == 'q' || b == 'Q') {
            return KeyEvent.of(Key.QUIT);
        }

        // Reply key
        if (b == 'r' || b == 'R') {
            return KeyEvent.of(Key.REPLY);
        }

        // Regular character
        return KeyEvent.ofChar((char) b);
    }

    /**
     * Handle escape sequences (arrow keys, etc.)
     */
    private KeyEvent handleEscapeSequence() throws IOException {
        // Check if there's more input available (escape sequence)
        // We need a small delay to allow the escape sequence to arrive
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (input.available() == 0) {
            // Standalone ESC key
            return KeyEvent.of(Key.ESCAPE);
        }

        int b2 = input.read();
        if (b2 == -1) {
            return KeyEvent.of(Key.ESCAPE);
        }

        // CSI (Control Sequence Introducer) starts with ESC [
        if (b2 == '[') {
            if (input.available() == 0) {
                return KeyEvent.of(Key.UNKNOWN);
            }

            int b3 = input.read();
            if (b3 == -1) {
                return KeyEvent.of(Key.UNKNOWN);
            }

            // Arrow keys: ESC [ A/B/C/D
            return switch (b3) {
                case 'A' -> KeyEvent.of(Key.UP);
                case 'B' -> KeyEvent.of(Key.DOWN);
                case 'C' -> KeyEvent.of(Key.RIGHT);
                case 'D' -> KeyEvent.of(Key.LEFT);
                default -> KeyEvent.of(Key.UNKNOWN);
            };
        }

        // Some terminals use ESC O for arrow keys
        if (b2 == 'O') {
            if (input.available() == 0) {
                return KeyEvent.of(Key.UNKNOWN);
            }

            int b3 = input.read();
            if (b3 == -1) {
                return KeyEvent.of(Key.UNKNOWN);
            }

            return switch (b3) {
                case 'A' -> KeyEvent.of(Key.UP);
                case 'B' -> KeyEvent.of(Key.DOWN);
                case 'C' -> KeyEvent.of(Key.RIGHT);
                case 'D' -> KeyEvent.of(Key.LEFT);
                default -> KeyEvent.of(Key.UNKNOWN);
            };
        }

        return KeyEvent.of(Key.UNKNOWN);
    }
}
