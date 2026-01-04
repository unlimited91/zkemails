package me.toymail.zkemails;

import me.toymail.zkemails.commands.InitCmd;
import me.toymail.zkemails.commands.RootCmd;
import picocli.CommandLine;

public final class CliMain {
    public static void main(String[] args) {
        int code = new CommandLine(new RootCmd()).execute(args);
//        int code = new CommandLine(new InitCmd()).execute(args);

        System.exit(code);
    }
}
