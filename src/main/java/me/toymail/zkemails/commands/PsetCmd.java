package me.toymail.zkemails.commands;

import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "pset", description = "Set the active profile",
        footer = {
            "",
            "Examples:",
            "  zke pset alice@gmail.com    Switch to alice's profile",
            "  zke pset work@company.com   Switch to work profile",
            "",
            "Use 'zke lsp' to see available profiles."
        })
public final class PsetCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(PsetCmd.class);
    private final StoreContext context;

    public PsetCmd(StoreContext context) {
        this.context = context;
    }

    @Parameters(index = "0", paramLabel = "<email>", description = "Profile email to set as active")
    String profileId;

    @Override
    public void run() {
        try {
            if (!context.profileConfig().exists()) {
                log.info("No profiles found. Run: zke init --email <your-email>");
                return;
            }
            context.switchProfile(profileId);
            log.info("Switched to profile: {}", profileId);
        } catch (IllegalArgumentException e) {
            log.info("{}", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to set profile: {}", e.getMessage());
        }
    }
}
