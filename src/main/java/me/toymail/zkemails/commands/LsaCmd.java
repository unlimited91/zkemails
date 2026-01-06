package me.toymail.zkemails.commands;

import picocli.CommandLine.Command;

@Command(
        name = "lsa",
        description = "List acknowledged invites. This is fetched from local store.",
        subcommands = { LsaInviCmd.class }
)
public final class LsaCmd implements Runnable {
    @Override public void run() {
        System.out.println("Use: zkemails lsa invi --help");
    }
}
