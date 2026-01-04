package me.toymail.zkemails.commands;

import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.ZkEmails;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.ZkStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "send-message", description = "Send a pure E2E encrypted email using pinned contact keys.")
public final class SendMessageCmd implements Runnable {

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
            String profile = ZkEmails.getCurrentProfileDir();
            if (profile == null) {
                System.err.println("No active profile set or profile directory missing. Use 'prof' to set a profile.");
                return;
            }
            ZkStore store = new ZkStore(profile);
            Config cfg = store.readJson("config.json", Config.class);
            if (cfg == null) {
                System.err.println("❌ Not initialized. Run: zkemails init ...");
                return;
            }

            IdentityKeys.KeyBundle myKeys = store.readJson("keys.json", IdentityKeys.KeyBundle.class);
            if (myKeys == null) {
                System.err.println("❌ Missing keys.json. Re-run init.");
                return;
            }

            ContactsStore contacts = new ContactsStore(store);
            ContactsStore.Contact c = contacts.get(to);
            if (c == null || c.x25519PublicB64 == null || c.fingerprintHex == null) {
                System.err.println("❌ No pinned X25519 key for contact: " + to);
                System.err.println("Run: zkemails sync ack --password   (or ack invi if they invited you).");
                return;
            }

            try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, password))) {
                smtp.sendEncryptedMessage(cfg.email, to, subject, body, myKeys, c.fingerprintHex, c.x25519PublicB64);
            }

            System.out.println("✅ Encrypted message sent to " + to + " (type=msg)");
        } catch (Exception e) {
            System.err.println("❌ send-message failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}

