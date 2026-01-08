package me.toymail.zkemails.tui;

import me.toymail.zkemails.ImapClient;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * Renders the interactive menu using ANSI escape codes.
 */
public final class MenuRenderer {

    // ANSI escape codes
    private static final String ANSI_CLEAR_SCREEN = "\033[2J";
    private static final String ANSI_HOME = "\033[H";
    private static final String ANSI_CLEAR_LINE = "\033[2K\r";
    private static final String ANSI_REVERSE = "\033[7m";
    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_BOLD = "\033[1m";
    private static final String ANSI_DIM = "\033[2m";
    private static final String ANSI_HIDE_CURSOR = "\033[?25l";
    private static final String ANSI_SHOW_CURSOR = "\033[?25h";

    // In raw mode, \n alone doesn't return to column 0, need \r\n
    private static final String NL = "\r\n";

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM dd HH:mm");

    private final PrintStream out;

    public MenuRenderer() {
        this.out = System.out;
    }

    public MenuRenderer(PrintStream out) {
        this.out = out;
    }

    /**
     * Hide the cursor for cleaner UI.
     */
    public void hideCursor() {
        out.print(ANSI_HIDE_CURSOR);
        out.flush();
    }

    /**
     * Show the cursor again.
     */
    public void showCursor() {
        out.print(ANSI_SHOW_CURSOR);
        out.flush();
    }

    /**
     * Clear the screen and move cursor to home position.
     */
    public void clearScreen() {
        out.print(ANSI_CLEAR_SCREEN + ANSI_HOME);
        out.flush();
    }

    /**
     * Render the email list view with the selected item highlighted.
     */
    public void renderListView(List<ImapClient.MailSummary> messages,
                               int selectedIndex,
                               int terminalHeight) {
        StringBuilder sb = new StringBuilder();
        sb.append(ANSI_CLEAR_SCREEN).append(ANSI_HOME);

        // Header
        sb.append(ANSI_BOLD)
          .append("=== Encrypted Messages (")
          .append(messages.size())
          .append(") ===")
          .append(ANSI_RESET)
          .append(NL).append(NL);

        if (messages.isEmpty()) {
            sb.append("No encrypted messages found.").append(NL);
        } else {
            // Calculate visible window for scrolling
            int visibleRows = Math.max(5, terminalHeight - 6);
            int startIdx = Math.max(0, selectedIndex - visibleRows / 2);
            int endIdx = Math.min(messages.size(), startIdx + visibleRows);

            // Adjust start if we're near the end
            if (endIdx == messages.size() && endIdx - startIdx < visibleRows) {
                startIdx = Math.max(0, endIdx - visibleRows);
            }

            for (int i = startIdx; i < endIdx; i++) {
                ImapClient.MailSummary msg = messages.get(i);
                boolean isSelected = (i == selectedIndex);

                // Compact format: > UID Date From Subject (fits 80 cols)
                String date = msg.received() != null
                        ? DATE_FORMAT.format(msg.received())
                        : "unknown";
                String from = extractEmail(msg.from());
                String subject = msg.subject() != null ? msg.subject() : "";

                String line = String.format("%s%6d %-12s %-22s %s",
                        isSelected ? ">" : " ",
                        msg.uid(),
                        truncate(date, 12),
                        truncate(from, 22),
                        truncate(subject, 35));

                if (isSelected) {
                    sb.append(ANSI_REVERSE).append(line).append(ANSI_RESET);
                } else {
                    sb.append(line);
                }
                sb.append(NL);
            }

            // Scroll indicator
            if (startIdx > 0 || endIdx < messages.size()) {
                sb.append(ANSI_DIM)
                  .append(NL).append("  [").append(startIdx + 1).append("-").append(endIdx).append(" of ").append(messages.size()).append("]")
                  .append(ANSI_RESET);
            }
        }

        // Footer with controls
        sb.append(NL)
          .append(ANSI_DIM)
          .append("[j/k] Navigate  [Enter] View  [q] Quit")
          .append(ANSI_RESET);

        out.print(sb);
        out.flush();
    }

    /**
     * Render the thread view showing all messages in a conversation.
     */
    public void renderThreadView(List<ImapClient.MailSummary> threadMessages,
                                 List<String> decryptedBodies,
                                 String baseSubject,
                                 int scrollOffset,
                                 int terminalHeight) {
        StringBuilder sb = new StringBuilder();
        sb.append(ANSI_CLEAR_SCREEN).append(ANSI_HOME);

        // Header
        sb.append(ANSI_BOLD)
          .append("=== Thread: ")
          .append(truncate(baseSubject, 50))
          .append(" (")
          .append(threadMessages.size())
          .append(" messages) ===")
          .append(ANSI_RESET)
          .append(NL).append(NL);

        // Build full content
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < threadMessages.size(); i++) {
            ImapClient.MailSummary msg = threadMessages.get(i);
            String body = i < decryptedBodies.size() ? decryptedBodies.get(i) : null;

            String date = msg.received() != null
                    ? DATE_FORMAT.format(msg.received())
                    : "unknown";

            content.append(ANSI_BOLD)
                   .append("[ID=").append(msg.uid()).append("] ")
                   .append("From: ").append(extractEmail(msg.from()))
                   .append(" | ").append(date)
                   .append(ANSI_RESET)
                   .append(NL);

            content.append("Subject: ").append(msg.subject()).append(NL).append(NL);

            if (body != null && !body.isEmpty()) {
                // Replace \n in body with NL for raw mode
                content.append(body.replace("\n", NL));
            } else {
                content.append(ANSI_DIM).append("(could not decrypt)").append(ANSI_RESET);
            }

            content.append(NL).append(NL);

            if (i < threadMessages.size() - 1) {
                content.append("---").append(NL).append(NL);
            }
        }

        // Split into lines and apply scroll
        String[] lines = content.toString().split(NL);
        int visibleLines = Math.max(5, terminalHeight - 6);
        int maxScroll = Math.max(0, lines.length - visibleLines);
        int actualOffset = Math.min(scrollOffset, maxScroll);

        for (int i = actualOffset; i < Math.min(actualOffset + visibleLines, lines.length); i++) {
            sb.append(lines[i]).append(NL);
        }

        // Scroll indicator if content overflows
        if (lines.length > visibleLines) {
            sb.append(ANSI_DIM)
              .append(NL).append("[Lines ").append(actualOffset + 1).append("-")
              .append(Math.min(actualOffset + visibleLines, lines.length))
              .append(" of ").append(lines.length).append("] [j/k] Scroll")
              .append(ANSI_RESET);
        }

        // Footer with controls
        sb.append(NL)
          .append(ANSI_DIM)
          .append("[r] Reply  [q] Back to list")
          .append(ANSI_RESET);

        out.print(sb);
        out.flush();
    }

    /**
     * Render a simple message (for errors, loading, etc.)
     */
    public void renderMessage(String message) {
        out.print(ANSI_CLEAR_SCREEN + ANSI_HOME);
        out.println(message);
        out.flush();
    }

    /**
     * Truncate a string to max length with ellipsis.
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    /**
     * Extract email address from "Name <email>" format.
     */
    private String extractEmail(String from) {
        if (from == null) return "";
        int start = from.indexOf('<');
        int end = from.indexOf('>');
        if (start >= 0 && end > start) {
            return from.substring(start + 1, end).trim();
        }
        return from.trim();
    }
}
