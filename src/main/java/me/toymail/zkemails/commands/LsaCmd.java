package me.toymail.zkemails.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

@Command(
        name = "lsa",
        description = "List acknowledged invites. This is fetched from local store.",
        subcommands = { LsaInviCmd.class }
)
public final class LsaCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LsaCmd.class);

    @Override public void run() {
        log.info("Use: zke lsia --help");
    }
}
