package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Map;

@Command(name = "sync-ack", description = "Sync ACCEPT messages and store sender public keys for future encrypted communication.")
public final class SyncAckCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SyncAckCmd.class);
    private final StoreContext context;

    public SyncAckCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--password", required = true, interactive = true,
            description = "App password / password (not saved)")
    String password;

    @Option(names="--limit", defaultValue = "200")
    int limit;

    @Override
    public void run() {
        try {
            if (!context.hasActiveProfile()) {
                log.error("No active profile set or profile directory missing. Use 'prof' to set a profile.");
                return;
            }
            Config cfg = context.zkStore().readJson("config.json", Config.class);
            if (cfg == null) {
                log.error("Not initialized. Run: zkemails init ...");
                return;
            }

            int updated = 0;
            try (ImapClient imap = ImapClient.connect(new ImapClient.ImapConfig(
                    cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, password
            ))) {
                List<ImapClient.MailSummary> accepts = imap.searchHeaderEquals("X-ZKEmails-Type", "accept", limit);
                for (var m : accepts) {
                    Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(m.uid());
                    String fp = first(hdrs, "X-ZKEmails-Fingerprint");
                    String ed = first(hdrs, "X-ZKEmails-PubKey-Ed25519");
                    String x = first(hdrs, "X-ZKEmails-PubKey-X25519");

                    String sender = extractEmail(m.from());
                    if (sender == null) continue;
                    if (fp == null || ed == null || x == null) continue;

                    context.contacts().upsertKeys(sender, "ready", fp, ed, x);
                    updated++;
                }
            }

            log.info("sync ack complete. Contacts updated: {}", updated);
        } catch (Exception e) {
            log.error("sync ack failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private static String first(Map<String, List<String>> hdrs, String key) {
        List<String> v = hdrs.get(key);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private static String extractEmail(String fromHeader) {
        if (fromHeader == null) return null;
        int lt = fromHeader.indexOf('<');
        int gt = fromHeader.indexOf('>');
        if (lt >= 0 && gt > lt) return fromHeader.substring(lt + 1, gt).trim();
        String s = fromHeader.trim();
        if (s.contains("@") && !s.contains(" ")) return s;
        return null;
    }
}
