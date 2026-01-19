package me.toymail.zkemails.service;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.StoreContext;
import me.toymail.zkemails.store.ZkStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Service for account initialization.
 */
public final class InitService {
    private final StoreContext context;

    public InitService(StoreContext context) {
        this.context = context;
    }

    /**
     * Configuration for initialization.
     */
    public record InitConfig(
        String email,
        String password,
        String imapHost,
        int imapPort,
        String smtpHost,
        int smtpPort
    ) {
        public static InitConfig forGmail(String email, String password) {
            return new InitConfig(email, password, "imap.gmail.com", 993, "smtp.gmail.com", 587);
        }
    }

    /**
     * Result of initialization.
     */
    public record InitResult(
        boolean success,
        String message,
        String fingerprint
    ) {}

    /**
     * Test IMAP connection.
     * @param config the init configuration
     * @throws Exception if connection fails
     */
    public void testImap(InitConfig config) throws Exception {
        try (ImapClient imap = ImapClient.connect(new ImapClient.ImapConfig(
                config.imapHost(), config.imapPort(), true, config.email(), config.password()
        ))) {
            imap.listInboxLatest(1);
        }
    }

    /**
     * Test SMTP connection.
     * @param config the init configuration
     * @throws Exception if connection fails
     */
    public void testSmtp(InitConfig config) throws Exception {
        try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(
                config.smtpHost(), config.smtpPort(), config.email(), config.password()
        ))) {
            smtp.testLogin();
        }
    }

    /**
     * Initialize profile (creates config.json, keys.json).
     * Assumes connections have already been tested.
     * @param config the init configuration
     * @return initialization result with fingerprint
     */
    public InitResult initialize(InitConfig config) throws Exception {
        Path zkRoot = Paths.get(System.getProperty("user.home"), ".zkemails");
        Path emailDir = zkRoot.resolve(config.email());
        if (!Files.exists(emailDir)) {
            Files.createDirectories(emailDir);
        }

        ZkStore store = new ZkStore(config.email());
        store.ensure();

        Config cfg = new Config();
        cfg.email = config.email();
        cfg.imap.host = config.imapHost();
        cfg.imap.port = config.imapPort();
        cfg.imap.ssl = true;
        cfg.imap.username = config.email();
        cfg.smtp.host = config.smtpHost();
        cfg.smtp.port = config.smtpPort();
        cfg.smtp.username = config.email();

        store.writeJson("config.json", cfg);

        String fingerprint;
        if (!store.exists("keys.json")) {
            IdentityKeys.KeyBundle keys = IdentityKeys.generate();
            store.writeJson("keys.json", keys);
            fingerprint = keys.fingerprintHex();
        } else {
            IdentityKeys.KeyBundle existingKeys = store.readJson("keys.json", IdentityKeys.KeyBundle.class);
            fingerprint = existingKeys != null ? existingKeys.fingerprintHex() : null;
        }

        context.addAndSwitchProfile(config.email());

        return new InitResult(true, "Initialized profile: " + config.email(), fingerprint);
    }

    /**
     * Full init flow: test connections then initialize.
     * @param config the init configuration
     * @return initialization result
     */
    public InitResult initializeWithValidation(InitConfig config) throws Exception {
        // Test IMAP
        try {
            testImap(config);
        } catch (Exception e) {
            return new InitResult(false, "IMAP connection failed: " + e.getMessage(), null);
        }

        // Test SMTP
        try {
            testSmtp(config);
        } catch (Exception e) {
            return new InitResult(false, "SMTP connection failed: " + e.getMessage(), null);
        }

        // Initialize
        return initialize(config);
    }

    /**
     * Save password to system keychain.
     * @param email the profile email
     * @param password the password to save
     * @return true if saved successfully
     */
    public boolean savePasswordToKeychain(String email, String password) {
        if (!context.credentials().isAvailable()) {
            return false;
        }
        return context.credentials().setPassword(email, password);
    }

    /**
     * Check if system keychain is available.
     */
    public boolean isKeychainAvailable() {
        return context.credentials().isAvailable();
    }
}
