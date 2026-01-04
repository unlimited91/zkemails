package me.toymail.zkemails.commands;

import picocli.CommandLine.Command;

@Command(
        name = "ls",
        description = "List protocol items.",
        subcommands = { LsInviCmd.class }
)
public final class LsCmd implements Runnable {
    @Override public void run() {
        System.out.println("Use: zkemails ls invi --help");
    }
}
