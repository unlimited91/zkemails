package me.toymail.zkemails.commands;

import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.CryptoBox;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.service.MessageService;
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

@Command(name = "sem", description = "Send an encrypted message to one or more contacts",
        footer = {
                "",
                "Examples:",
                "  zke sem                                    Open editor to compose message",
                "  zke sem --to alice@example.com             Pre-fill recipient",
                "  zke sem --to alice@example.com --to bob@example.com  Multiple To recipients",
                "  zke sem --to alice@example.com --cc bob@example.com  With CC",
                "  zke sem --to alice@example.com --bcc charlie@example.com  With BCC",
                "  zke sem --to alice@example.com --attach doc.pdf  Send with attachments",
                "",
                "Note: All recipients must be contacts with exchanged keys (via invite/ack flow).",
                "      Attachments are encrypted with the message (max 25MB each).",
                "      BCC recipients receive separate emails for privacy."
        })
public final class SendMessageCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SendMessageCmd.class);
    private static final long MAX_ATTACHMENT_SIZE = 25 * 1024 * 1024; // 25MB
    private final StoreContext context;

    public SendMessageCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--to", description = "To recipient email (repeatable for multiple)")
    List<String> toEmails;

    @Option(names="--cc", description = "CC recipient email (repeatable for multiple)")
    List<String> ccEmails;

    @Option(names="--bcc", description = "BCC recipient email (repeatable for multiple, sent separately)")
    List<String> bccEmails;

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

            // Get first To recipient for editor pre-fill
            String recipientTo = (toEmails != null && !toEmails.isEmpty()) ? toEmails.get(0) : null;
            String messageSubject = subject;
            String messageBody = body;

            // Open editor if any required fields are missing
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

                    // Update toEmails with editor result
                    if (toEmails == null) toEmails = new ArrayList<>();
                    if (toEmails.isEmpty()) {
                        toEmails.add(recipientTo);
                    } else {
                        toEmails.set(0, recipientTo);
                    }
                } catch (IllegalStateException e) {
                    log.error("{}", e.getMessage());
                    return;
                }
            }

            // Validate required fields
            if (toEmails == null || toEmails.isEmpty() || toEmails.get(0).isBlank()) {
                log.error("At least one recipient (--to) is required.");
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

            // Check if this is a multi-recipient send or single recipient (v1)
            int totalRecipients = (toEmails != null ? toEmails.size() : 0) +
                                  (ccEmails != null ? ccEmails.size() : 0) +
                                  (bccEmails != null ? bccEmails.size() : 0);

            boolean isMultiRecipient = totalRecipients > 1 || ccEmails != null || bccEmails != null;

            // Validate all recipients have keys
            List<String> allRecipients = new ArrayList<>();
            if (toEmails != null) allRecipients.addAll(toEmails);
            if (ccEmails != null) allRecipients.addAll(ccEmails);
            if (bccEmails != null) allRecipients.addAll(bccEmails);

            for (String email : allRecipients) {
                ContactsStore.Contact c = context.contacts().get(email);
                if (c == null || c.x25519PublicB64 == null || c.fingerprintHex == null) {
                    log.error("No pinned X25519 key for contact: {}", email);
                    log.error("Run: zke sync-ack (or ack invi if they invited you).");
                    return;
                }
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

            // Send the message
            if (isMultiRecipient) {
                // Use v2 multi-recipient send
                MessageService msgService = new MessageService(context);
                MessageService.MultiRecipientInput recipients = new MessageService.MultiRecipientInput(
                    toEmails, ccEmails, bccEmails
                );

                // Note: attachments not yet supported for multi-recipient v2 - log warning
                if (!attachmentInputs.isEmpty()) {
                    log.warn("Attachments with multi-recipient send not yet supported. Sending without attachments.");
                }

                MessageService.MultiSendResult result = msgService.sendMultiRecipientMessage(
                    resolvedPassword, recipients, messageSubject, messageBody, null, null, null
                );

                if (result.success()) {
                    log.info("{}", result.message());
                } else {
                    log.error("Failed to send: {}", result.message());
                }
            } else {
                // Use v1 single-recipient send (backward compatible)
                String singleRecipient = toEmails.get(0);
                ContactsStore.Contact c = context.contacts().get(singleRecipient);

                try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(
                        cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, resolvedPassword))) {
                    if (attachmentInputs.isEmpty()) {
                        smtp.sendEncryptedMessage(cfg.email, singleRecipient, messageSubject, messageBody,
                            myKeys, c.fingerprintHex, c.x25519PublicB64);
                    } else {
                        smtp.sendEncryptedMessageWithAttachments(cfg.email, singleRecipient, messageSubject, messageBody,
                            attachmentInputs, myKeys, c.fingerprintHex, c.x25519PublicB64, null, null, null);
                    }
                }

                String attachmentInfo = attachmentInputs.isEmpty() ? "" : " with " + attachmentInputs.size() + " attachment(s)";
                log.info("Encrypted message sent to {} (type=msg){}", singleRecipient, attachmentInfo);
            }
        } catch (Exception e) {
            log.error("send-message failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}

