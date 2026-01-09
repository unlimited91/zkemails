package me.toymail.zkemails.gui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import me.toymail.zkemails.ImapConnectionPool;
import me.toymail.zkemails.gui.cache.MessageCacheService;
import me.toymail.zkemails.gui.controller.MainController;
import me.toymail.zkemails.gui.util.TaskRunner;
import me.toymail.zkemails.service.ServiceContext;

import java.io.IOException;

/**
 * Main JavaFX application for ZKE GUI.
 */
public class ZkeGuiApplication extends Application {
    private static ServiceContext serviceContext;
    private static MessageCacheService cacheService;

    /**
     * Set the service context before launching.
     * Used when launching from CLI to share context.
     */
    public static void setServiceContext(ServiceContext context) {
        serviceContext = context;
    }

    /**
     * Get the service context.
     */
    public static ServiceContext getServiceContext() {
        return serviceContext;
    }

    /**
     * Get the message cache service.
     */
    public static MessageCacheService getCacheService() {
        return cacheService;
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        // Initialize service context if not set (direct launch)
        if (serviceContext == null) {
            serviceContext = ServiceContext.create();
        }

        // Initialize cache service
        cacheService = new MessageCacheService(serviceContext);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainWindow.fxml"));
        loader.setControllerFactory(this::createController);

        Parent root = loader.load();
        Scene scene = new Scene(root, 1200, 800);

        // Load CSS
        var cssUrl = getClass().getResource("/css/styles.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        primaryStage.setTitle("ZKE - Zero Knowledge Emails");

        // Set application icon
        try {
            var iconStream = getClass().getResourceAsStream("/images/zkemails-icon.png");
            if (iconStream != null) {
                primaryStage.getIcons().add(new Image(iconStream));
            }
        } catch (Exception e) {
            System.err.println("Failed to load application icon: " + e.getMessage());
        }

        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);

        // Cleanup on close
        primaryStage.setOnCloseRequest(e -> {
            if (cacheService != null) {
                cacheService.shutdown();
            }
            ImapConnectionPool.getInstance().shutdown();
            TaskRunner.shutdown();
        });

        primaryStage.show();
    }

    private Object createController(Class<?> type) {
        try {
            // Try to find a constructor that takes ServiceContext
            try {
                return type.getConstructor(ServiceContext.class).newInstance(serviceContext);
            } catch (NoSuchMethodException e) {
                // Fall back to default constructor
                return type.getConstructor().newInstance();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create controller: " + type.getName(), e);
        }
    }

    /**
     * Main entry point for direct GUI launch.
     */
    public static void main(String[] args) {
        launch(args);
    }
}
