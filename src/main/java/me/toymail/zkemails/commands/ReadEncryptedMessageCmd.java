package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.CryptoBox;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.StoreContext;
import me.toymail.zkemails.tui.ConsoleMenu;
import me.toymail.zkemails.tui.MessageEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

@Command(name = "rem", description = "Read encrypted messages (interactive browser or CLI mode)",
        footer = {
            "",
            "Interactive Mode (default):",
            "  zke rem                  Opens interactive email browser",
            "                           [UP/DOWN] Navigate  [ENTER] View thread  [q] Quit",
            "                           [r] Reply  [q] Back to list (in thread view)",
            "",
            "CLI Mode:",
            "  zke rem --no-interactive List messages in plain text",
            "  zke rem --message 42     View and decrypt message with ID 42",
            "  zke rem --thread 42      View entire conversation containing message 42",
            "  zke rem --reply 42       Reply to message 42 (opens editor)",
            "",
            "Sample interactive list:",
            "  === Encrypted Messages (3) ===",
            "  > 142   Jan 08 14:30 | alice@example.com | Project update",
            "    138   Jan 07 10:15 | bob@example.com   | Re: Hello",
            "    135   Jan 06 09:00 | alice@example.com | Hello"
        })
public class ReadEncryptedMessageCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ReadEncryptedMessageCmd.class);
    private final StoreContext context;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public ReadEncryptedMessageCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names = "--password", description = "App password (optional if saved to keychain)")
    String password;

    @Option(names = "--message", paramLabel = "<id>", description = "View and decrypt a specific message by its ID")
    Long messageId;

    @Option(names = "--thread", paramLabel = "<id>", description = "View entire conversation thread containing the message")
    Long threadId;

    @Option(names = "--reply", paramLabel = "<id>", description = "Reply to a message (opens editor with quoted original)")
    Long replyTo;

    @Option(names = "--limit", defaultValue = "20", description = "Maximum number of messages to display (default: ${DEFAULT-VALUE})")
    int limit;

    @Option(names = "--no-interactive", description = "Disable interactive mode, use plain list output")
    boolean noInteractive;

    @Override
    public void run() {
        if (!context.hasActiveProfile()) {
            log.error("No active profile set or profile directory missing. Use 'prof' to set a profile.");
            return;
        }
        Config cfg;
        IdentityKeys.KeyBundle myKeys;
        try {
            cfg = context.zkStore().readJson("config.json", Config.class);
            myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);
        } catch (IOException e) {
            log.error("Error reading config or keys: {}", e.getMessage());
            return;
        }
        if (cfg == null) {
            log.error("Not initialized. Run: zke init --email <your-email>");
            return;
        }
        if (myKeys == null) {
            log.error("Missing keys.json. Re-run init.");
            return;
        }

        String resolvedPassword = context.passwordResolver().resolve(password, cfg.email, System.console());

        try (ImapClient imap = ImapClient.connect(new ImapClient.ImapConfig(cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, resolvedPassword))) {

            if (replyTo != null) {
                // Reply mode
                handleReply(imap, cfg, myKeys, resolvedPassword);
            } else if (threadId != null) {
                // Thread view mode
                handleThread(imap, cfg, myKeys);
            } else if (messageId != null) {
                // Single message view mode
                handleSingleMessage(imap, cfg, myKeys);
            } else {
                // No options - check if interactive mode is available
                if (!noInteractive && ConsoleMenu.isInteractiveSupported()) {
                    handleInteractive(imap, cfg, myKeys, resolvedPassword);
                } else {
                    // Fallback to list mode
                    handleList(imap);
                }
            }

        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
        }
    }

    private void handleInteractive(ImapClient imap, Config cfg, IdentityKeys.KeyBundle myKeys, String resolvedPassword) throws Exception {
        ConsoleMenu menu = new ConsoleMenu(context, cfg, myKeys, resolvedPassword);
        ConsoleMenu.MenuResult result = menu.run(imap, limit);

        switch (result.action()) {
            case REPLY:
                // User selected a message to reply to
                replyTo = result.selectedMessageId();
                // Need to reconnect since the menu may have used the connection
                try (ImapClient newImap = ImapClient.connect(new ImapClient.ImapConfig(
                        cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, resolvedPassword))) {
                    handleReply(newImap, cfg, myKeys, resolvedPassword);
                }
                break;
            case QUIT:
                // Normal exit
                break;
            case CANCELLED:
                // Fallback to list mode
                handleList(imap);
                break;
        }
    }

    private void handleList(ImapClient imap) throws Exception {
        List<ImapClient.MailSummary> msgs = imap.searchHeaderEquals("X-ZKEmails-Type", "msg", limit);
        if (msgs.isEmpty()) {
            log.info("No encrypted messages found.");
            return;
        }
        for (var m : msgs) {
            String date = m.received() != null ? DATE_FORMAT.format(m.received()) : "unknown";
            log.info("ID={} | from={} | subject={} | date={}", m.uid(), m.from(), m.subject(), date);
        }
    }

    private void handleSingleMessage(ImapClient imap, Config cfg, IdentityKeys.KeyBundle myKeys) throws Exception {
        ImapClient.MailSummary msg = imap.getMessageByUid(messageId);
        if (msg == null) {
            log.error("No message found with ID={}", messageId);
            return;
        }

        String plaintext = decryptMessage(imap, msg, cfg, myKeys);
        if (plaintext != null) {
            log.info("From: {}", msg.from());
            log.info("Subject: {}", msg.subject());
            log.info("Date: {}", msg.received() != null ? DATE_FORMAT.format(msg.received()) : "unknown");
            log.info("---");
            log.info("{}", plaintext);
        }
    }

    private void handleThread(ImapClient imap, Config cfg, IdentityKeys.KeyBundle myKeys) throws Exception {
        // Build the set of Message-IDs in this thread
        Set<String> threadIds = imap.buildThreadIdSet(threadId);
        if (threadIds.isEmpty()) {
            // Fallback: just show the single message
            log.warn("No thread information found, showing single message.");
            messageId = threadId;
            handleSingleMessage(imap, cfg, myKeys);
            return;
        }

        // Find all messages in the thread
        List<ImapClient.MailSummary> threadMsgs = imap.searchThread(threadIds, limit);
        if (threadMsgs.isEmpty()) {
            log.info("No messages found in thread.");
            return;
        }

        // Get base subject (strip "Re: " prefixes)
        String baseSubject = threadMsgs.get(0).subject();
        if (baseSubject != null) {
            baseSubject = baseSubject.replaceAll("(?i)^(Re:\\s*)+", "").trim();
        }

        log.info("=== Thread: {} ({} messages) ===", baseSubject, threadMsgs.size());
        log.info("");

        for (var m : threadMsgs) {
            String date = m.received() != null ? DATE_FORMAT.format(m.received()) : "unknown";
            log.info("[ID={}] From: {} | Date: {}", m.uid(), m.from(), date);
            log.info("Subject: {}", m.subject());
            log.info("");

            String plaintext = decryptMessage(imap, m, cfg, myKeys);
            if (plaintext != null) {
                log.info("{}", plaintext);
            } else {
                log.info("(could not decrypt)");
            }

            log.info("");
            log.info("---");
            log.info("");
        }
    }

    private void handleReply(ImapClient imap, Config cfg, IdentityKeys.KeyBundle myKeys, String resolvedPassword) throws Exception {
        // Get the original message
        ImapClient.MailSummary msg = imap.getMessageByUid(replyTo);
        if (msg == null) {
            log.error("No message found with ID={}", replyTo);
            return;
        }

        // Decrypt the original message for quoting
        String originalPlaintext = decryptMessage(imap, msg, cfg, myKeys);
        if (originalPlaintext == null) {
            log.error("Could not decrypt original message for quoting.");
            return;
        }

        // Get sender email (this is who we're replying to)
        String replyToEmail = extractEmail(msg.from());
        if (replyToEmail == null) {
            log.error("Could not extract email from: {}", msg.from());
            return;
        }

        // Check if we have keys for this contact
        ContactsStore.Contact contact = context.contacts().get(replyToEmail);
        if (contact == null || contact.x25519PublicB64 == null || contact.fingerprintHex == null) {
            log.error("No pinned X25519 key for contact: {}", replyToEmail);
            log.error("Run: zke sync-ack (or ack invi if they invited you).");
            return;
        }

        // Build reply subject
        String replySubject = msg.subject();
        if (replySubject != null && !replySubject.toLowerCase().startsWith("re:")) {
            replySubject = "Re: " + replySubject;
        }

        // Build quoted body
        String quotedBody = quoteText(originalPlaintext);

        // Get threading info
        String originalMessageId = imap.getMessageId(replyTo);
        String originalReferences = imap.getReferences(replyTo);

        // Build new References header: original References + original Message-ID
        String newReferences = originalReferences != null ? originalReferences + " " : "";
        if (originalMessageId != null) {
            newReferences += originalMessageId;
        }
        newReferences = newReferences.trim();
        if (newReferences.isEmpty()) newReferences = null;

        // Open editor
        MessageEditor.EditorResult result;
        try {
            result = MessageEditor.open(replyToEmail, replySubject, quotedBody);
        } catch (IllegalStateException e) {
            log.error("{}", e.getMessage());
            return;
        }

        if (result.isCancelled()) {
            log.info("Reply cancelled.");
            return;
        }

        String body = result.getBody();
        if (body == null || body.isBlank()) {
            log.error("Cannot send empty message.");
            return;
        }

        // Send the reply
        try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, resolvedPassword))) {
            smtp.sendEncryptedMessage(
                    cfg.email,
                    replyToEmail,
                    replySubject,
                    body,
                    myKeys,
                    contact.fingerprintHex,
                    contact.x25519PublicB64,
                    originalMessageId,
                    newReferences
            );
        }

        log.info("Reply sent to {}", replyToEmail);
    }

    private String decryptMessage(ImapClient imap, ImapClient.MailSummary msg, Config cfg, IdentityKeys.KeyBundle myKeys) {
        try {
            Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(msg.uid());

            String ephemX25519PubB64 = first(hdrs, "X-ZKEmails-Ephem-X25519");
            String wrappedKeyB64 = first(hdrs, "X-ZKEmails-WrappedKey");
            String wrappedKeyNonceB64 = first(hdrs, "X-ZKEmails-WrappedKey-Nonce");
            String msgNonceB64 = first(hdrs, "X-ZKEmails-Nonce");
            String ciphertextB64 = first(hdrs, "X-ZKEmails-Ciphertext");
            String sigB64 = first(hdrs, "X-ZKEmails-Sig");
            String recipientFpHex = first(hdrs, "X-ZKEmails-Recipient-Fp");

            String fromEmail = extractEmail(msg.from());
            ContactsStore.Contact contact = context.contacts().get(fromEmail);
            if (contact == null || contact.ed25519PublicB64 == null) {
                log.warn("No contact found for sender: {}", fromEmail);
                return null;
            }

            CryptoBox.EncryptedPayload payload = new CryptoBox.EncryptedPayload(
                    ephemX25519PubB64, wrappedKeyB64, wrappedKeyNonceB64,
                    msgNonceB64, ciphertextB64, sigB64, recipientFpHex
            );

            return CryptoBox.decryptForRecipient(
                    fromEmail,
                    cfg.email,
                    msg.subject(),
                    payload,
                    myKeys.x25519PrivateB64(),
                    contact.ed25519PublicB64
            );
        } catch (Exception e) {
            log.debug("Decryption failed for message {}: {}", msg.uid(), e.getMessage());
            return null;
        }
    }

    private String extractEmail(String from) {
        if (from == null) return null;
        // Handle formats like "Name <email@example.com>" or just "email@example.com"
        int start = from.indexOf('<');
        int end = from.indexOf('>');
        if (start >= 0 && end > start) {
            return from.substring(start + 1, end).trim();
        }
        return from.trim();
    }

    private String quoteText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append("> ").append(line).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static String first(Map<String, List<String>> map, String key) {
        List<String> v = map.get(key);
        return (v != null && !v.isEmpty()) ? v.get(0) : null;
    }
}
