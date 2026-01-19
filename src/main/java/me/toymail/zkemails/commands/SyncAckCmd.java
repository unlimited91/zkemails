package me.toymail.zkemails.commands;

import me.toymail.zkemails.service.InviteService;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "sync-ack", description = "Sync ACCEPT message for a specific invite and import contact keys",
        footer = {
            "",
            "Examples:",
            "  zke sync-ack --invite-id abc123   Sync accept for invite abc123",
            "",
            "What this command does:",
            "  Searches your inbox for an ACCEPT message matching the invite-id",
            "  and imports the sender's public keys into your contacts.",
            "",
            "When to use:",
            "  After sending an invite with 'zke invite', run this command to",
            "  import keys from the person who accepted your invitation.",
            "",
            "Workflow:",
            "  1. zke invite --to friend@example.com   Send invite",
            "  2. (friend accepts the invite)",
            "  3. zke lso                              List outgoing invites",
            "  4. zke sync-ack --invite-id <id>        Import their keys",
            "  5. zke sem --to friend@example.com      Send encrypted message"
        })
public final class SyncAckCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SyncAckCmd.class);
    private final StoreContext context;

    public SyncAckCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--password", paramLabel = "<password>",
            description = "App password (optional if saved to keychain)")
    String password;

    @Option(names="--invite-id", paramLabel = "<invite-id>", required = true,
            description = "The invite ID to sync accept for (use 'zke lso' to list)")
    String inviteId;

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

            // Check if contact is already ready (prevent overwriting)
            InviteService inviteService = new InviteService(context);

            String resolvedPassword = context.passwordResolver().resolve(password, cfg.email, System.console());

            InviteService.SyncAcceptResult result = inviteService.syncAcceptForInvite(resolvedPassword, inviteId);

            if (result.success()) {
                log.info("sync-ack complete: {}", result.message());
            } else {
                log.error("sync-ack failed: {}", result.message());
            }
        } catch (Exception e) {
            log.error("sync-ack failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
