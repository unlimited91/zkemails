package me.toymail.zkemails.commands;

import me.toymail.zkemails.store.InviteStore;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Command(name = "lsia", description = "List acknowledged invites",
        footer = {
                "",
                "Examples:",
                "  zke lsia              List all acknowledged invites",
                "  zke lsia --limit 10   Show only 10 invites",
                "",
                "These are invites you have already acknowledged.",
                "You can now exchange encrypted messages with these contacts."
        })
public final class LsaInviCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LsaInviCmd.class);
    private final StoreContext context;

    public LsaInviCmd(StoreContext context) {
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

            List<InviteStore.Invite> all = context.invites().listIncoming(false);
            all.removeIf(i -> !"acked".equalsIgnoreCase(i.status));

            if (all.isEmpty()) {
                log.info("(no acked incoming invites found locally)");
                return;
            }

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

            log.info("invite-id | from | created | status | subject");
            log.info("-----------------------------------------------------------------------");

            int n = Math.min(limit, all.size());
            for (int i = 0; i < n; i++) {
                var x = all.get(i);
                String created = fmt.format(Instant.ofEpochSecond(x.createdEpochSec));
                log.info("{} | {} | {} | {} | {}", x.inviteId, x.fromEmail, created, x.status, x.subject);
            }
        } catch (Exception e) {
            log.error("lsa invi failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
