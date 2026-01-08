package me.toymail.zkemails.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

@Command(
        name = "ls",
        description = "List incoming invites pending acknowledgement from the local store",
        subcommands = { LsInviCmd.class }
)
public final class LsCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LsCmd.class);

    @Override public void run() {
        log.info("Use: zke lsi --help");
    }
}
