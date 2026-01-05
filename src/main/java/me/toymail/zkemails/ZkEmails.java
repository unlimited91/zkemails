package me.toymail.zkemails;

import me.toymail.zkemails.commands.ProfileCmd;
import picocli.CommandLine;

@CommandLine.Command(
    subcommands = {
        ProfileCmd.class
    }
)
public class ZkEmails {
    // Profile operations are now handled via StoreContext
}
