package me.toymail.zkemails.commands;

import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.ZkEmails;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.InviteStore;
import me.toymail.zkemails.store.ZkStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;

@Command(name = "send-invite", description = "Send a zkemails invite to a recipient.")
public final class SendInviteCmd implements Runnable {

    @Option(names="--to", required = true)
    String to;

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

            IdentityKeys.KeyBundle keys = store.readJson("keys.json", IdentityKeys.KeyBundle.class);
            if (keys == null) {
                System.err.println("❌ Missing keys.json. Re-run init.");
                return;
            }

            InviteStore inviteStore = new InviteStore(store);
            ContactsStore contacts = new ContactsStore(store);

            contacts.upsertBasic(to, "invited-out");

            try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, password))) {
                String inviteId = smtp.sendInvite(cfg.email, to, keys, inviteStore);
                System.out.println("✅ Sent invite to " + to + " inviteId=" + inviteId);
                System.out.println("✅ Contact stored/updated in contacts.json (status=invited-out)");
            }
        } catch (Exception e) {
            System.err.println("❌ send-invite failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}

