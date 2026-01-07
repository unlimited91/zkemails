package me.toymail.zkemails.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

@Command(
        name = "ack",
        description = "Acknowledge the invite via an invite-id.",
        subcommands = { AckInviCmd.class }
)
public final class AckCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(AckCmd.class);

    @Override public void run() {
        log.info("Use: zkemails ack invi --help");
    }
}
