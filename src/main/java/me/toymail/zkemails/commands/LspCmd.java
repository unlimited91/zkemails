package me.toymail.zkemails.commands;

import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

import java.util.List;

@Command(name = "lsp", description = "List all profiles",
        footer = {
            "",
            "Examples:",
            "  zke lsp              List all profiles",
            "",
            "Output shows '*' next to the active profile."
        })
public final class LspCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LspCmd.class);
    private final StoreContext context;

    public LspCmd(StoreContext context) {
        this.context = context;
    }

    @Override
    public void run() {
        try {
            if (!context.profileConfig().exists()) {
                log.info("No profiles found.");
                return;
            }
            List<String> profiles = context.profileConfig().listProfiles();
            String def = context.profileConfig().getDefault();
            for (String p : profiles) {
                if (p.equals(def)) {
                    log.info("* {} (active)", p);
                } else {
                    log.info("  {}", p);
                }
            }
        } catch (Exception e) {
            log.error("Failed to list profiles: {}", e.getMessage());
        }
    }
}
