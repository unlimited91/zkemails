package me.toymail.zkemails.commands;

import picocli.CommandLine.Command;

@Command(
        name = "ack",
        description = "Acknowledge protocol items.",
        subcommands = { AckInviCmd.class }
)
public final class AckCmd implements Runnable {
    @Override public void run() {
        System.out.println("Use: zkemails ack invi --help");
    }
}
