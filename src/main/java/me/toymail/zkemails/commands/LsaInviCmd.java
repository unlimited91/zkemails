package me.toymail.zkemails.commands;

import me.toymail.zkemails.ZkEmails;
import me.toymail.zkemails.store.InviteStore;
import me.toymail.zkemails.store.ZkStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Command(name = "invi", description = "List acked incoming invites (local).")
public final class LsaInviCmd implements Runnable {

    @Option(names="--limit", defaultValue = "50")
    int limit;

    @Override
    public void run() {
        try {
            String profile = ZkEmails.getCurrentProfileDir();
            if (profile == null) {
                System.err.println("No active profile set or profile directory missing. Use 'prof' to set a profile.");
                return;
            }
            ZkStore store = new ZkStore(profile);
            store.ensure();
            InviteStore inv = new InviteStore(store);

            List<InviteStore.Invite> all = inv.listIncoming(false);
            all.removeIf(i -> !"acked".equalsIgnoreCase(i.status));

            if (all.isEmpty()) {
                System.out.println("(no acked incoming invites found locally)");
                return;
            }

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

            System.out.println("invite-id | from | created | status | subject");
            System.out.println("-----------------------------------------------------------------------");

            int n = Math.min(limit, all.size());
            for (int i = 0; i < n; i++) {
                var x = all.get(i);
                String created = fmt.format(Instant.ofEpochSecond(x.createdEpochSec));
                System.out.printf("%s | %s | %s | %s | %s%n",
                        x.inviteId, x.fromEmail, created, x.status, x.subject);
            }
        } catch (Exception e) {
            System.err.println("❌ lsa invi failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}
