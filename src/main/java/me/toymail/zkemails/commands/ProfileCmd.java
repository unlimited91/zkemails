package me.toymail.zkemails.commands;

import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.util.List;

@Command(name = "prof", description = "List or switch profiles", subcommands = {ProfileCmd.Ls.class, ProfileCmd.Use.class})
public class ProfileCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ProfileCmd.class);
    private final StoreContext context;

    public ProfileCmd(StoreContext context) {
        this.context = context;
    }

    StoreContext context() {
        return context;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "ls", description = "List profiles")
    public static class Ls implements Runnable {
        @ParentCommand
        private ProfileCmd parent;

        @Override
        public void run() {
            try {
                StoreContext context = parent.context();
                if (!context.profileConfig().exists()) {
                    log.info("No profiles found.");
                    return;
                }
                List<String> profiles = context.profileConfig().listProfiles();
                String def = context.profileConfig().getDefault();
                for (String p : profiles) {
                    if (p.equals(def)) {
                        log.info("* {} (default)", p);
                    } else {
                        log.info(" {}", p);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to list profiles: {}", e.getMessage());
            }
        }
    }

    @Command(name = "set", description = "Set current profile (prof <profile-id>)")
    public static class Use implements Runnable {
        @ParentCommand
        private ProfileCmd parent;

        @Parameters(index = "0", description = "Profile id (email)")
        String profileId;

        @Override
        public void run() {
            try {
                StoreContext context = parent.context();
                if (!context.profileConfig().exists()) {
                    log.info("No profiles found.");
                    return;
                }
                context.switchProfile(profileId);
                log.info("Set default profile to: {}", profileId);
            } catch (IllegalArgumentException e) {
                log.info("{}", e.getMessage());
            } catch (Exception e) {
                log.error("Failed to set profile: {}", e.getMessage());
            }
        }
    }
}

