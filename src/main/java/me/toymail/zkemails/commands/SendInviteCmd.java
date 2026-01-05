package me.toymail.zkemails.commands;

import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.StoreContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "send-invite", description = "Send a zkemails invite to a recipient.")
public final class SendInviteCmd implements Runnable {
    private final StoreContext context;

    public SendInviteCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--to", required = true)
    String to;

    @Option(names="--password", required = true, interactive = true,
            description = "App password / password (not saved)")
    String password;

    @Override
    public void run() {
        try {
            if (!context.hasActiveProfile()) {
                System.err.println("No active profile set or profile directory missing. Use 'prof' to set a profile.");
                return;
            }
            Config cfg = context.zkStore().readJson("config.json", Config.class);
            if (cfg == null) {
                System.err.println("Not initialized. Run: zkemails init ...");
                return;
            }

            IdentityKeys.KeyBundle keys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);
            if (keys == null) {
                System.err.println("Missing keys.json. Re-run init.");
                return;
            }

            context.contacts().upsertBasic(to, "invited-out");

            try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, password))) {
                String inviteId = smtp.sendInvite(cfg.email, to, keys, context.invites());
                System.out.println("Sent invite to " + to + " inviteId=" + inviteId);
                System.out.println("Contact stored/updated in contacts.json (status=invited-out)");

            }
        } catch (Exception e) {
            System.err.println("send-invite failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}

