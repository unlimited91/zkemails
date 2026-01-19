package me.toymail.zkemails.commands;

import me.toymail.zkemails.gui.ZkeGuiApplication;
import me.toymail.zkemails.service.ServiceContext;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

@Command(name = "gui", description = "Launch the graphical user interface",
        footer = {
            "",
            "Examples:",
            "  zke gui                    Launch the GUI",
            "",
            "The GUI provides a visual interface for:",
            "  - Reading and composing encrypted messages",
            "  - Managing contacts and invites",
            "  - Profile configuration and settings",
            "",
            "Note: Requires a display. Falls back to CLI if no display available."
        })
public final class GuiCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(GuiCmd.class);
    private final StoreContext context;

    public GuiCmd(StoreContext context) {
        this.context = context;
    }

    @Override
    public void run() {
        try {
            // Check if display is available
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                log.error("No display available. GUI requires a graphical environment.");
                log.info("Use CLI commands instead: zke --help");
                return;
            }

            log.info("Launching ZKE GUI...");

            // Pass context to GUI application
            ServiceContext serviceContext = ServiceContext.fromStoreContext(context);
            ZkeGuiApplication.setServiceContext(serviceContext);

            // Launch JavaFX application
            // Note: This blocks until the GUI window is closed
            javafx.application.Application.launch(ZkeGuiApplication.class);

        } catch (Exception e) {
            log.error("Failed to launch GUI: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            log.info("Use CLI commands instead: zke --help");
        }
    }
}
