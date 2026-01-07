package me.toymail.zkemails.commands;

import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name = "credential", description = "Manage stored credentials in system keychain",
         subcommands = {CredentialCmd.Status.class, CredentialCmd.Delete.class})
public class CredentialCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(CredentialCmd.class);
    private final StoreContext context;

    public CredentialCmd(StoreContext context) {
        this.context = context;
    }

    StoreContext context() {
        return context;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "status", description = "Check if password is stored for current profile")
    public static class Status implements Runnable {
        @ParentCommand
        private CredentialCmd parent;

        @Override
        public void run() {
            try {
                StoreContext context = parent.context();

                if (!context.credentials().isAvailable()) {
                    log.info("System keychain is not available on this system.");
                    return;
                }

                if (!context.hasActiveProfile()) {
                    log.error("No active profile set. Use 'prof set <email>' to set a profile.");
                    return;
                }

                Config cfg = context.zkStore().readJson("config.json", Config.class);
                if (cfg == null) {
                    log.error("Not initialized. Run: zkemails init ...");
                    return;
                }

                boolean hasPassword = context.credentials().getPassword(cfg.email).isPresent();
                if (hasPassword) {
                    log.info("Password stored in system keychain: Yes");
                    log.info("Profile: {}", cfg.email);
                } else {
                    log.info("Password stored in system keychain: No");
                    log.info("Profile: {}", cfg.email);
                    log.info("Run 'zkemails init' to set up and save password to keychain.");
                }
            } catch (Exception e) {
                log.error("Failed to check credential status: {}", e.getMessage());
            }
        }
    }

    @Command(name = "delete", description = "Remove stored password for current profile")
    public static class Delete implements Runnable {
        @ParentCommand
        private CredentialCmd parent;

        @Override
        public void run() {
            try {
                StoreContext context = parent.context();

                if (!context.credentials().isAvailable()) {
                    log.info("System keychain is not available on this system.");
                    return;
                }

                if (!context.hasActiveProfile()) {
                    log.error("No active profile set. Use 'prof set <email>' to set a profile.");
                    return;
                }

                Config cfg = context.zkStore().readJson("config.json", Config.class);
                if (cfg == null) {
                    log.error("Not initialized. Run: zkemails init ...");
                    return;
                }

                boolean deleted = context.credentials().deletePassword(cfg.email);
                if (deleted) {
                    log.info("Password removed from system keychain for: {}", cfg.email);
                } else {
                    log.info("No password was stored for: {}", cfg.email);
                }
            } catch (Exception e) {
                log.error("Failed to delete credential: {}", e.getMessage());
            }
        }
    }
}
