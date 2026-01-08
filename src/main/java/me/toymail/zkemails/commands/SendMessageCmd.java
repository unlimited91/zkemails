package me.toymail.zkemails.commands;

import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.StoreContext;
import me.toymail.zkemails.tui.MessageEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "sem", description = "Send an encrypted message to a contact",
        footer = {
                "",
                "Examples:",
                "  zke sem                                    Open editor to compose message",
                "  zke sem --to alice@example.com             Pre-fill recipient",
                "  zke sem --to alice@example.com --subject 'Hello'  Pre-fill recipient and subject",
                "",
                "Note: Recipient must be a contact with exchanged keys (via invite/ack flow)."
        })
public final class SendMessageCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SendMessageCmd.class);
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

            try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, resolvedPassword))) {
                smtp.sendEncryptedMessage(cfg.email, recipientTo, messageSubject, messageBody, myKeys, c.fingerprintHex, c.x25519PublicB64);
            }

            log.info("Encrypted message sent to {} (type=msg)", recipientTo);
        } catch (Exception e) {
            log.error("send-message failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}

