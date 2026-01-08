package me.toymail.zkemails.commands;

import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "invite", description = "Send an invite to start encrypted communication with someone",
        footer = {
                "",
                "Examples:",
                "  zke invite --to alice@example.com   Send invite to Alice",
                "",
                "After sending, the recipient needs to:",
                "  1. Install zke and initialize with their email",
                "  2. Run: zke ack invi --invite-id <id-from-email>",
                "",
                "Once they acknowledge, you can exchange encrypted messages."
        })
public final class SendInviteCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SendInviteCmd.class);
    private final StoreContext context;

    public SendInviteCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--to", required = true)
    String to;

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

            IdentityKeys.KeyBundle keys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);
            if (keys == null) {
                log.error("Missing keys.json. Re-run init.");
                return;
            }

            context.contacts().upsertBasic(to, "invited-out");

            String resolvedPassword = context.passwordResolver().resolve(password, cfg.email, System.console());

            try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, resolvedPassword))) {
                String inviteId = smtp.sendInvite(cfg.email, to, keys, context.invites());
                log.info("Sent invite to {} inviteId={}", to, inviteId);
                log.info("Contact stored/updated in contacts.json (status=invited-out)");

            }
        } catch (Exception e) {
            log.error("send-invite failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}

