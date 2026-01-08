package me.toymail.zkemails.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

@Command(name = "prof", description = "Profile management (use 'lsp' and 'pset' commands)")
public class ProfileCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ProfileCmd.class);

    @Override
    public void run() {
        log.info("Use: zke lsp (list profiles) or zke pset <email> (set profile)");
    }
}

