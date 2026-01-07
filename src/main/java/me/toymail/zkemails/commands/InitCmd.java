package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.StoreContext;
import me.toymail.zkemails.store.ZkStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.*;

@Command(name = "init", description = "Authenticates the email credentials provided by the user")
public final class InitCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(InitCmd.class);
    private final StoreContext context;

    public InitCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--email", required = true, description = "Email address (e.g. user@gmail.com)")
    String email;

    @Option(names="--password", description = "App password (will prompt if not provided)")
    String password;

    @Option(names="--imap-host", defaultValue = "imap.gmail.com")
    String imapHost;

    @Option(names="--imap-port", defaultValue = "993")
    int imapPort;

    @Option(names="--smtp-host", defaultValue = "smtp.gmail.com")
    String smtpHost;

    @Option(names="--smtp-port", defaultValue = "587")
    int smtpPort;

    @Override
    public void run() {
        try {
            // Prompt for password if not provided
            if (password == null || password.isBlank()) {
                java.io.Console console = System.console();
                if (console == null) {
                    log.error("No console available for password input. Use --password flag.");
                    return;
                }
                char[] pw = console.readPassword("Password for %s: ", email);
                if (pw == null) {
                    log.error("Password input cancelled.");
                    return;
                }
                password = new String(pw);
            }

            // 1) Test IMAP
            try (ImapClient imap = ImapClient.connect(new ImapClient.ImapConfig(imapHost, imapPort, true, email, password))) {
                imap.listInboxLatest(1);
            }

            // 2) Test SMTP
            try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(smtpHost, smtpPort, email, password))) {
                smtp.testLogin();
            }

            // 3) Persist config + keys only after successful auth
            Path zkRoot = Paths.get(System.getProperty("user.home"), ".zkemails");
            Path emailDir = zkRoot.resolve(email);
            if (!Files.exists(emailDir)) {
                Files.createDirectories(emailDir);
            }
            ZkStore store = new ZkStore(email);
            store.ensure();

            Config cfg = new Config();
            cfg.email = email;
            cfg.imap.host = imapHost;
            cfg.imap.port = imapPort;
            cfg.imap.ssl = true;
            cfg.imap.username = email;
            cfg.smtp.host = smtpHost;
            cfg.smtp.port = smtpPort;
            cfg.smtp.username = email;

            store.writeJson("config.json", cfg);

            if (!store.exists("keys.json")) {
                var keys = IdentityKeys.generate();
                store.writeJson("keys.json", keys);
                log.info("Auth OK. Wrote config.json and generated keys.json in {}", store.baseDir());
            } else {
                log.info("Auth OK. Wrote config.json. keys.json already exists in {}", store.baseDir());
            }

            // Update profile config and switch to new profile
            context.addAndSwitchProfile(email);
            log.info("Set {} as default profile", email);

            // Offer to save password to system keychain
            if (context.credentials().isAvailable()) {
                java.io.Console console = System.console();
                if (console != null) {
                    String response = console.readLine("Save password to system keychain? [Y/n]: ");
                    if (response == null || response.isBlank() || response.toLowerCase().startsWith("y")) {
                        boolean saved = context.credentials().setPassword(email, password);
                        if (saved) {
                            log.info("Password saved to system keychain.");
                        } else {
                            log.warn("Failed to save password to keychain.");
                        }
                    } else {
                        log.info("Password not saved. You will need to enter it each time.");
                    }
                }
            } else {
                log.info("Note: System keychain not available. Password will be required each time.");
            }

        } catch (Exception e) {
            log.error("init failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
