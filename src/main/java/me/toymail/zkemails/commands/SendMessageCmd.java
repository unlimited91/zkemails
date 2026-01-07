package me.toymail.zkemails.commands;

import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "send-message", description = "Send a pure E2E encrypted email using pinned contact keys.")
public final class SendMessageCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SendMessageCmd.class);
    private final StoreContext context;

    public SendMessageCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--to", required = true)
    String to;

    @Option(names="--subject", required = true)
    String subject;

    @Option(names="--body", required = true)
    String body;

    @Option(names="--password", required = true, interactive = true,
            description = "App password / password (not saved)")
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
                log.error("Not initialized. Run: zkemails init ...");
                return;
            }

            IdentityKeys.KeyBundle myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);
            if (myKeys == null) {
                log.error("Missing keys.json. Re-run init.");
                return;
            }

            ContactsStore.Contact c = context.contacts().get(to);
            if (c == null || c.x25519PublicB64 == null || c.fingerprintHex == null) {
                log.error("No pinned X25519 key for contact: {}", to);
                log.error("Run: zkemails sync ack --password   (or ack invi if they invited you).");
                return;
            }

            try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, password))) {
                smtp.sendEncryptedMessage(cfg.email, to, subject, body, myKeys, c.fingerprintHex, c.x25519PublicB64);
            }

            log.info("Encrypted message sent to {} (type=msg)", to);
        } catch (Exception e) {
            log.error("send-message failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}

