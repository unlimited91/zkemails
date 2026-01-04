package me.toymail.zkemails.commands;

import picocli.CommandLine.Command;

@Command(
        name = "zkemails",
        mixinStandardHelpOptions = true,
        description = "Zero Knowledge email client (key gossip + TOFU).",
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
        }
)
public final class RootCmd implements Runnable {
    @Override public void run() {
        System.out.println("Use --help. Example: zkemails init --help");
    }
}
