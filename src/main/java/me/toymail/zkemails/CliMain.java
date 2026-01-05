package me.toymail.zkemails;

import me.toymail.zkemails.commands.RootCmd;
import me.toymail.zkemails.store.StoreContext;
import picocli.CommandLine;

public final class CliMain {
    public static void main(String[] args) {
        StoreContext context = StoreContext.initialize();
        CommandLine.IFactory factory = new StoreAwareFactory(context);
        int code = new CommandLine(new RootCmd(context), factory).execute(args);
        System.exit(code);
    }
}
