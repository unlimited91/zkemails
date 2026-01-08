package me.toymail.zkemails.tui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Message editor that opens the user's default text editor.
 * Uses $EDITOR or $VISUAL environment variable, falls back to vi/nano.
 */
public final class MessageEditor {
    private static final Logger log = LoggerFactory.getLogger(MessageEditor.class);
    private static final String SEPARATOR = "--- Write your message below this line ---";
    private static final String CANCEL_MARKER = "# DELETE THIS LINE TO SEND (save unchanged to cancel)";

    /**
     * Result of the editor session.
     */
    public static final class EditorResult {
        private final String to;
        private final String subject;
        private final String body;
        private final boolean cancelled;

        private EditorResult(String to, String subject, String body, boolean cancelled) {
            this.to = to;
            this.subject = subject;
            this.body = body;
            this.cancelled = cancelled;
        }

        public String getTo() {
            return to;
        }

        public String getSubject() {
            return subject;
        }

        public String getBody() {
            return body;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public static EditorResult cancelled() {
            return new EditorResult(null, null, null, true);
        }

        public static EditorResult success(String to, String subject, String body) {
            return new EditorResult(to, subject, body, false);
        }
    }

    /**
     * Opens the user's default editor for composing a message.
     *
     * @param initialTo      Pre-populated recipient (optional, can be null)
     * @param initialSubject Pre-populated subject (optional, can be null)
     * @param initialBody    Pre-populated body (optional, can be null)
     * @return EditorResult with to, subject, body and cancelled flag
     * @throws IllegalStateException if no editor is available or editing fails
     */
    public static EditorResult open(String initialTo, String initialSubject, String initialBody) {
        Path tempFile = null;
        try {
            // Create temp file with template
            tempFile = Files.createTempFile("zkemails-", ".txt");
            String template = buildTemplate(initialTo, initialSubject, initialBody);
            Files.writeString(tempFile, template);

            // Get editor command
            String editor = getEditor();
            log.debug("Using editor: {}", editor);

            // Open editor and wait
            ProcessBuilder pb = new ProcessBuilder(editor, tempFile.toString());
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.warn("Editor exited with code: {}", exitCode);
                return EditorResult.cancelled();
            }

            // Parse the result
            String content = Files.readString(tempFile);
            return parseContent(content);

        } catch (IOException e) {
            log.error("Failed to open editor: {}", e.getMessage());
            throw new IllegalStateException("Failed to open editor: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EditorResult.cancelled();
        } finally {
            // Clean up temp file
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.debug("Failed to delete temp file: {}", e.getMessage());
                }
            }
        }
    }

    private static String buildTemplate(String to, String subject, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("To: ").append(to != null ? to : "").append("\n");
        sb.append("Subject: ").append(subject != null ? subject : "").append("\n");
        sb.append(CANCEL_MARKER).append("\n");
        sb.append(SEPARATOR).append("\n");
        if (body != null && !body.isBlank()) {
            sb.append(body);
        }
        return sb.toString();
    }

    private static String getEditor() {
        // Check VISUAL first (preferred for full-screen editors)
        String editor = System.getenv("VISUAL");
        if (editor != null && !editor.isBlank()) {
            return editor;
        }

        // Then EDITOR
        editor = System.getenv("EDITOR");
        if (editor != null && !editor.isBlank()) {
            return editor;
        }

        // Fallback: try common editors
        if (commandExists("nano")) {
            return "nano";
        }
        if (commandExists("vi")) {
            return "vi";
        }

        throw new IllegalStateException(
            "No editor found. Set $EDITOR environment variable or install nano/vi."
        );
    }

    private static boolean commandExists(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static EditorResult parseContent(String content) {
        String[] lines = content.split("\n", -1);

        String to = null;
        String subject = null;
        List<String> bodyLines = new ArrayList<>();
        boolean inBody = false;
        boolean cancelMarkerPresent = false;

        for (String line : lines) {
            if (inBody) {
                bodyLines.add(line);
            } else if (line.startsWith(SEPARATOR.substring(0, 3))) {
                // Found separator, everything after is body
                inBody = true;
            } else if (line.startsWith("# DELETE THIS LINE TO SEND")) {
                // Cancel marker still present - user didn't confirm
                cancelMarkerPresent = true;
            } else if (line.toLowerCase().startsWith("to:")) {
                to = line.substring(3).trim();
            } else if (line.toLowerCase().startsWith("subject:")) {
                subject = line.substring(8).trim();
            }
        }

        // If cancel marker is still present, user didn't confirm sending
        if (cancelMarkerPresent) {
            return EditorResult.cancelled();
        }

        String body = String.join("\n", bodyLines).trim();

        // Check if user cleared everything (cancelled)
        if ((to == null || to.isEmpty()) &&
            (subject == null || subject.isEmpty()) &&
            body.isEmpty()) {
            return EditorResult.cancelled();
        }

        return EditorResult.success(to, subject, body);
    }
}
