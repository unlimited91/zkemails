package me.toymail.zkemails.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

@Command(
        name = "ack",
        description = "Acknowledge invitations",
        subcommands = { AckInviCmd.class },
        footer = {
            "",
            "Usage:",
            "  zke ack invi --invite-id <id>   Accept an invitation",
            "",
            "See 'zke ack invi --help' for more details."
        }
)
public final class AckCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(AckCmd.class);

    @Override public void run() {
        log.info("Use: zke ack invi --help");
    }
}
