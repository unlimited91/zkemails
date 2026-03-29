package me.toymail.zkemails.commands;

import me.toymail.zkemails.service.InviteService;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Command(name = "lso", description = "List outgoing invites (invites you sent)",
        footer = {
                "",
                "Examples:",
                "  zke lso              List all outgoing invites",
                "  zke lso --limit 10   Show only 10 invites",
                "",
                "Status meanings:",
                "  sent   - Invite sent, waiting for recipient to accept",
                "  synced - Recipient accepted and keys have been imported",
                "",
                "To sync an accept for a specific invite:",
                "  zke sync-ack --invite-id <invite-id>"
        })
public final class LsoCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LsoCmd.class);
    private final StoreContext context;

    public LsoCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--limit", defaultValue = "50")
    int limit;

    @Override
    public void run() {
        try {
            if (!context.hasActiveProfile()) {
                log.error("No active profile set or profile directory missing. Use 'prof' to set a profile.");
                return;
            }
            context.zkStore().ensure();

            InviteService inviteService = new InviteService(context);
            List<InviteService.InviteSummary> outgoing = inviteService.listOutgoingInvites();

            if (outgoing.isEmpty()) {
                log.info("(no outgoing invites found)");
                log.info("Tip: Use 'zke invite --to <email>' to send an invite.");
                return;
            }

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

            log.info("invite-id | to | created | status | subject");
            log.info("-----------------------------------------------------------------------");

            int n = Math.min(limit, outgoing.size());
            for (int i = 0; i < n; i++) {
                var x = outgoing.get(i);
                String created = fmt.format(Instant.ofEpochSecond(x.createdEpochSec()));
                String status = x.status();

                // Check if contact is already ready (keys synced)
                try {
                    ContactsStore.Contact contact = context.contacts().get(x.email());
                    if (contact != null && "ready".equals(contact.status)) {
                        status = "synced";
                    }
                } catch (Exception e) {
                    // Ignore - use default status
                }

                log.info("{} | {} | {} | {} | {}", x.inviteId(), x.email(), created, status, x.subject());
            }
        } catch (Exception e) {
            log.error("lso failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
