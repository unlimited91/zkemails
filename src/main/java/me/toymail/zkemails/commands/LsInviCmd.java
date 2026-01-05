package me.toymail.zkemails.commands;

import me.toymail.zkemails.store.InviteStore;
import me.toymail.zkemails.store.StoreContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Command(name = "invi", description = "List incoming invites pending acknowledgement (local; no IMAP).")
public final class LsInviCmd implements Runnable {
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
                System.err.println("No active profile set or profile directory missing. Use 'prof' to set a profile.");
                return;
            }
            context.zkStore().ensure();

            List<InviteStore.Invite> pending = context.invites().listIncoming(true);

            if (pending.isEmpty()) {
                System.out.println("(no pending incoming invites found locally)");
                System.out.println("Tip: ack invi caches an invite locally. A future `sync invi` could cache all invites.");
                return;
            }

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

            System.out.println("invite-id | from | created | status | subject");
            System.out.println("-----------------------------------------------------------------------");

            int n = Math.min(limit, pending.size());
            for (int i = 0; i < n; i++) {
                var x = pending.get(i);
                String created = fmt.format(Instant.ofEpochSecond(x.createdEpochSec));
                System.out.printf("%s | %s | %s | %s | %s%n",
                        x.inviteId, x.fromEmail, created, x.status, x.subject);
            }
        } catch (Exception e) {
            System.err.println("❌ ls invi failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}
