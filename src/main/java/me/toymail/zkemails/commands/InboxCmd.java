package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

@Command(name = "inbox", description = "List latest emails (optionally filter by a header).")
public final class InboxCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(InboxCmd.class);
    private final StoreContext context;

    public InboxCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--password", description = "App password (optional if saved to keychain)")
    String password;

    @Option(names="--limit", defaultValue = "20")
    int limit;

    @Option(names="--header-name", description = "Header name to filter on (e.g. X-ZKEmails-Type)")
    String headerName;

    @Option(names="--header-value", description = "Header value to filter on (e.g. invite)")
    String headerValue;

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

            String resolvedPassword = context.passwordResolver().resolve(password, cfg.email, System.console());

            try (ImapClient imap = ImapClient.connect(new ImapClient.ImapConfig(cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, resolvedPassword))) {

                List<ImapClient.MailSummary> msgs;
                if (headerName != null && headerValue != null) {
                    msgs = imap.searchHeaderEquals(headerName, headerValue, limit);
                    log.info("Filtered by header: {}={}", headerName, headerValue);
                } else {
                    msgs = imap.listInboxLatest(limit);
                }

                for (var m : msgs) {
                    log.info("UID={} | seen={} | {} | {} | {}", m.uid(), m.seen(), m.received(), m.from(), m.subject());
                }
            }
        } catch (Exception e) {
            log.error("inbox failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
