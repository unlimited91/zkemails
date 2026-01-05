package me.toymail.zkemails.commands;

import me.toymail.zkemails.StoreAwareFactory;
import me.toymail.zkemails.store.StoreContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

public class CommandTestBase {

    @TempDir
    protected Path tempDir;

    protected StoreContext context;

    private String originalUserHome;

    @BeforeEach
    public void setUp() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        context = StoreContext.initialize();
    }

    @AfterEach
    public void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    /**
     * Re-initialize context after profile config changes (e.g., after creating profiles)
     */
    protected void reinitializeContext() {
        context = StoreContext.initialize();
    }

    /**
     * Execute a command using picocli with proper DI
     */
    protected int executeCommand(Object command, String... args) {
        CommandLine.IFactory factory = new StoreAwareFactory(context);
        return new CommandLine(command, factory).execute(args);
    }
}

