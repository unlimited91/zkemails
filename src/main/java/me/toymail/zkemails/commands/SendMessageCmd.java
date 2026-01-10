package me.toymail.zkemails.commands;

import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.CryptoBox;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.StoreContext;
import me.toymail.zkemails.tui.MessageEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Command(name = "sem", description = "Send an encrypted message to a contact",
        footer = {
                "",
                "Examples:",
                "  zke sem                                    Open editor to compose message",
                "  zke sem --to alice@example.com             Pre-fill recipient",
                "  zke sem --to alice@example.com --subject 'Hello'  Pre-fill recipient and subject",
                "  zke sem --to alice@example.com --attach doc.pdf --attach photo.jpg  Send with attachments",
                "",
                "Note: Recipient must be a contact with exchanged keys (via invite/ack flow).",
                "      Attachments are encrypted with the message (max 25MB each)."
        })
public final class SendMessageCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SendMessageCmd.class);
    private static final long MAX_ATTACHMENT_SIZE = 25 * 1024 * 1024; // 25MB
    private final StoreContext context;

    public SendMessageCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--to", description = "Recipient email (opens editor if not provided)")
    String to;

    @Option(names="--subject", description = "Subject line (opens editor if not provided)")
    String subject;

    @Option(names="--body", description = "Message body (opens editor if not provided)")
    String body;

    @Option(names="--password", description = "App password (optional if saved to keychain)")
    String password;

    @Option(names="--attach", description = "File to attach (repeatable, max 25MB each)")
    List<Path> attachments;

    @Override
    public void run() {
        try {
            if (!context.hasActiveProfile()) {
                log.error("No active profile set or profile directory missing. Use 'prof' to set a profile.");
                return;
            }
            Config cfg = context.zkStore().readJson("config.json", Config.class);
            if (cfg == null) {
                log.error("Not initialized. Run: zke init --email <your-email>");
                return;
            }

            IdentityKeys.KeyBundle myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);
            if (myKeys == null) {
                log.error("Missing keys.json. Re-run init.");
                return;
            }

            // Resolve to, subject, body - open editor if any are missing
            String recipientTo = to;
            String messageSubject = subject;
            String messageBody = body;

            boolean needsEditor = (recipientTo == null || recipientTo.isBlank()) ||
                                  (messageSubject == null || messageSubject.isBlank()) ||
                                  (messageBody == null || messageBody.isBlank());

            if (needsEditor) {
                try {
                    MessageEditor.EditorResult result = MessageEditor.open(
                        recipientTo,
                        messageSubject,
                        messageBody
                    );
                    if (result.isCancelled()) {
                        log.info("Message cancelled.");
                        return;
                    }
                    recipientTo = result.getTo();
                    messageSubject = result.getSubject();
                    messageBody = result.getBody();
                } catch (IllegalStateException e) {
                    log.error("{}", e.getMessage());
                    return;
                }
            }

            // Validate all fields
            if (recipientTo == null || recipientTo.isBlank()) {
                log.error("Recipient (--to) is required.");
                return;
            }
            if (messageSubject == null || messageSubject.isBlank()) {
                log.error("Subject (--subject) is required.");
                return;
            }
            if (messageBody == null || messageBody.isBlank()) {
                log.error("Cannot send empty message.");
                return;
            }

            ContactsStore.Contact c = context.contacts().get(recipientTo);
            if (c == null || c.x25519PublicB64 == null || c.fingerprintHex == null) {
                log.error("No pinned X25519 key for contact: {}", recipientTo);
                log.error("Run: zke sync-ack (or ack invi if they invited you).");
                return;
            }

            String resolvedPassword = context.passwordResolver().resolve(password, cfg.email, System.console());

            // Process attachments if any
            List<CryptoBox.AttachmentInput> attachmentInputs = new ArrayList<>();
            if (attachments != null && !attachments.isEmpty()) {
                for (Path path : attachments) {
                    if (!path.toFile().exists()) {
                        log.error("Attachment not found: {}", path);
                        return;
                    }
                    if (!path.toFile().isFile()) {
                        log.error("Not a file: {}", path);
                        return;
                    }
                    if (path.toFile().length() > MAX_ATTACHMENT_SIZE) {
                        log.error("Attachment too large (max 25MB): {} ({} MB)",
                                path.getFileName(), path.toFile().length() / 1024 / 1024);
                        return;
                    }
                    try {
                        attachmentInputs.add(CryptoBox.AttachmentInput.fromFile(path));
                        log.info("Attaching: {} ({} bytes)", path.getFileName(), path.toFile().length());
                    } catch (Exception e) {
                        log.error("Failed to read attachment {}: {}", path, e.getMessage());
                        return;
                    }
                }
            }

            try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, resolvedPassword))) {
                if (attachmentInputs.isEmpty()) {
                    smtp.sendEncryptedMessage(cfg.email, recipientTo, messageSubject, messageBody, myKeys, c.fingerprintHex, c.x25519PublicB64);
                } else {
                    smtp.sendEncryptedMessageWithAttachments(cfg.email, recipientTo, messageSubject, messageBody,
                            attachmentInputs, myKeys, c.fingerprintHex, c.x25519PublicB64, null, null, null);
                }
            }

            String attachmentInfo = attachmentInputs.isEmpty() ? "" : " with " + attachmentInputs.size() + " attachment(s)";
            log.info("Encrypted message sent to {} (type=msg){}", recipientTo, attachmentInfo);
        } catch (Exception e) {
            log.error("send-message failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}

