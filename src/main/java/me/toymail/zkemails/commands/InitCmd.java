package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.StoreContext;
import me.toymail.zkemails.store.ZkStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.*;

@Command(name = "init", description = "Authenticate to IMAP/SMTP, then generate/store keys + config.")
public final class InitCmd implements Runnable {
    private final StoreContext context;

    public InitCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--email", required = true, description = "Email address (e.g. user@gmail.com)")
    String email;

    @Option(names="--password", required = true, interactive = true,
            description = "App password / password (will not be saved)")
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
                System.out.println("Auth OK. Wrote config.json and generated keys.json in " + store.baseDir());
            } else {
                System.out.println("Auth OK. Wrote config.json. keys.json already exists in " + store.baseDir());
            }

            // Update profile config and switch to new profile
            context.addAndSwitchProfile(email);
            System.out.println("Set " + email + " as default profile");


        } catch (Exception e) {
            System.err.println("init failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}
