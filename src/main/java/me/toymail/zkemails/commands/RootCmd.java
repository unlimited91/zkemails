package me.toymail.zkemails.commands;

import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

@Command(
        name = "zke",
        mixinStandardHelpOptions = true,
        description = "Zero Knowledge Email client - End-to-end encrypted email on top of regular email",
        footer = {
                "",
                "Quick Start:",
                "  zke init --email you@gmail.com   Initialize with your email",
                "  zke invite --to friend@email.com Send an invite to start encrypted chat",
                "  zke sem                          Send an encrypted message (opens editor)",
                "  zke rem                          Read encrypted messages",
                "  zke gui                          Launch the graphical user interface",
                "",
                "Common Commands:",
                "  init      Initialize zke with your email",
                "  invite    Send an invite to a new contact",
                "  sem       Send an encrypted message",
                "  rem       Read encrypted messages (list, view, thread, reply)",
                "  inbox     View your inbox",
                "  lsi       List pending incoming invites",
                "  lsia      List acknowledged invites",
                "  ack       Acknowledge invites",
                "  lsp       List profiles",
                "  pset      Set active profile",
                "  gui       Launch graphical user interface",
                "",
                "Use 'zke <command> --help' for more information on a command."
        },
        subcommands = {
                InitCmd.class,
                InboxCmd.class,
                SendInviteCmd.class,
                LsInviCmd.class,
                AckCmd.class,
                LsaInviCmd.class,
                LspCmd.class,
                PsetCmd.class,
                SendMessageCmd.class,
                ReadEncryptedMessageCmd.class,
                SyncAckCmd.class,
                CredentialCmd.class,
                ContactVersionsCmd.class,
                GuiCmd.class
        }
)
public final class RootCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(RootCmd.class);
    private final StoreContext context;

    public RootCmd(StoreContext context) {
        this.context = context;
    }

    @Override public void run() {
        log.info("Use --help. Example: zke init --help");
    }
}
