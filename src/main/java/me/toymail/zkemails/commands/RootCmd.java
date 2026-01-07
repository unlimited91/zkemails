package me.toymail.zkemails.commands;

import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

@Command(
        name = "zkemails",
        mixinStandardHelpOptions = true,
        description = "Zero Knowledge email client",
        subcommands = {
                InitCmd.class,
                InboxCmd.class,
                SendInviteCmd.class,
                LsCmd.class,
                AckCmd.class,
                LsaCmd.class,
                ProfileCmd.class,
                SendMessageCmd.class,
                ReadEncryptedMessageCmd.class,
                SyncAckCmd.class
        }
)
public final class RootCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(RootCmd.class);
    private final StoreContext context;

    public RootCmd(StoreContext context) {
        this.context = context;
    }

    @Override public void run() {
        log.info("Use --help. Example: zkemails init --help");
    }
}
