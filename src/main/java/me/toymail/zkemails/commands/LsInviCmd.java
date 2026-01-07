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

@Command(name = "invi", description = "List incoming invites pending acknowledgement from the local store")
public final class LsInviCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LsInviCmd.class);
    private final StoreContext context;

    public LsInviCmd(StoreContext context) {
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

            List<InviteStore.Invite> pending = context.invites().listIncoming(true);

            if (pending.isEmpty()) {
                log.info("(no pending incoming invites found locally)");
                log.info("Tip: ack invi caches an invite locally. A future `sync invi` could cache all invites.");
                return;
            }

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

            log.info("invite-id | from | created | status | subject");
            log.info("-----------------------------------------------------------------------");

            int n = Math.min(limit, pending.size());
            for (int i = 0; i < n; i++) {
                var x = pending.get(i);
                String created = fmt.format(Instant.ofEpochSecond(x.createdEpochSec));
                log.info("{} | {} | {} | {} | {}", x.inviteId, x.fromEmail, created, x.status, x.subject);
            }
        } catch (Exception e) {
            log.error("ls invi failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
