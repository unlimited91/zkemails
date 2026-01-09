package me.toymail.zkemails.gui.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import me.toymail.zkemails.gui.ZkeGuiApplication;
import me.toymail.zkemails.gui.cache.MessageCacheService;
import me.toymail.zkemails.gui.util.TaskRunner;
import me.toymail.zkemails.service.ServiceContext;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Main controller for the application window.
 * Manages navigation between views and profile selection.
 */
public class MainController {
    private final ServiceContext services;
    private MessageCacheService cacheService;

    @FXML private BorderPane rootPane;
    @FXML private VBox sidebar;
    @FXML private StackPane contentArea;
    @FXML private Label statusLabel;
    @FXML private ComboBox<String> profileSelector;
    @FXML private ProgressIndicator progressIndicator;

    @FXML private Button messagesButton;
    @FXML private Button sentButton;
    @FXML private Button composeButton;
    @FXML private Button contactsButton;
    @FXML private Button invitesButton;
    @FXML private Button settingsButton;

    private Button currentNavButton;
    private String currentPassword;
    private boolean cacheServiceStarted = false;

    public MainController(ServiceContext services) {
        this.services = services;
    }

    @FXML
    public void initialize() {
        // Get cache service from application
        cacheService = ZkeGuiApplication.getCacheService();

        // Register for cache updates
        if (cacheService != null) {
            cacheService.addUpdateListener(this::onCacheUpdate);
        }

        // Load profiles into selector
        loadProfiles();

        // Set up profile selector listener
        profileSelector.setOnAction(e -> onProfileChanged());

        // Default to messages view and start cache
        Platform.runLater(() -> {
            initializeCacheService();
            switchToMessages();
        });
    }

    /**
     * Initialize the cache service with password.
     */
    private void initializeCacheService() {
        if (cacheServiceStarted || cacheService == null) {
            return;
        }

        String password = getPasswordSilent();
        if (password != null) {
            cacheService.start(password);
            cacheServiceStarted = true;
            setStatus("Loading messages...");
        }
    }

    /**
     * Handle cache update events.
     */
    private void onCacheUpdate(MessageCacheService.CacheUpdateEvent event) {
        switch (event.type()) {
            case LOADING_STARTED:
                showProgress(true);
                break;
            case LOADING_FINISHED:
                showProgress(false);
                break;
            case MESSAGES_UPDATED:
                int count = event.data() instanceof Integer ? (Integer) event.data() : 0;
                String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
                setStatus("Synced " + count + " new messages (last sync: " + time + ")");
                break;
            case ERROR:
                setStatus("Error: " + event.data());
                break;
        }
    }

    private void loadProfiles() {
        TaskRunner.run("Loading profiles", () -> services.profiles().listProfiles(), new TaskRunner.TaskCallback<>() {
            @Override
            public void onSuccess(List<String> profiles) {
                profileSelector.getItems().clear();
                profileSelector.getItems().addAll(profiles);

                try {
                    String active = services.profiles().getActiveProfile();
                    if (active != null) {
                        profileSelector.setValue(active);
                    }
                } catch (Exception e) {
                    // Ignore
                }

                if (profiles.isEmpty()) {
                    statusLabel.setText("No profiles. Go to Settings to initialize.");
                }
            }

            @Override
            public void onError(Throwable error) {
                statusLabel.setText("Error loading profiles: " + error.getMessage());
            }
        });
    }

    private void onProfileChanged() {
        String selected = profileSelector.getValue();
        if (selected == null) return;

        TaskRunner.run("Switching profile", () -> {
            services.profiles().switchProfile(selected);
            return null;
        }, new TaskRunner.TaskCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                statusLabel.setText("Switched to profile: " + selected);
                currentPassword = null; // Clear cached password
                cacheServiceStarted = false;

                // Get password for new profile - try silent first, then prompt if needed
                String password = getPasswordSilent();
                if (password == null) {
                    // Prompt user for password for the new profile
                    password = getPassword();
                }

                // Notify cache service of profile switch
                if (password != null && cacheService != null) {
                    cacheService.onProfileSwitch(selected, password);
                    cacheServiceStarted = true;
                }

                // Refresh current view
                if (currentNavButton == messagesButton) {
                    switchToMessages();
                }
            }

            @Override
            public void onError(Throwable error) {
                statusLabel.setText("Error switching profile: " + error.getMessage());
            }
        });
    }

    @FXML
    public void switchToMessages() {
        setActiveNavButton(messagesButton);
        loadView("/fxml/MessagesView.fxml");
    }

    @FXML
    public void switchToSent() {
        setActiveNavButton(sentButton);
        loadView("/fxml/SentView.fxml");
    }

    @FXML
    public void switchToCompose() {
        setActiveNavButton(composeButton);
        loadView("/fxml/ComposeView.fxml");
    }

    @FXML
    public void switchToContacts() {
        setActiveNavButton(contactsButton);
        loadView("/fxml/ContactsView.fxml");
    }

    @FXML
    public void switchToInvites() {
        setActiveNavButton(invitesButton);
        loadView("/fxml/InvitesView.fxml");
    }

    @FXML
    public void switchToSettings() {
        setActiveNavButton(settingsButton);
        loadView("/fxml/SettingsView.fxml");
    }

    private void setActiveNavButton(Button button) {
        if (currentNavButton != null) {
            currentNavButton.getStyleClass().remove("nav-button-active");
        }
        currentNavButton = button;
        if (button != null) {
            button.getStyleClass().add("nav-button-active");
        }
    }

    private void loadView(String fxmlPath) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            loader.setControllerFactory(type -> {
                try {
                    // Pass services and main controller to child controllers
                    try {
                        return type.getConstructor(ServiceContext.class, MainController.class)
                                   .newInstance(services, this);
                    } catch (NoSuchMethodException e1) {
                        try {
                            return type.getConstructor(ServiceContext.class)
                                       .newInstance(services);
                        } catch (NoSuchMethodException e2) {
                            return type.getConstructor().newInstance();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create controller: " + type.getName(), e);
                }
            });

            Node view = loader.load();
            contentArea.getChildren().clear();
            contentArea.getChildren().add(view);
        } catch (IOException e) {
            showError("Failed to load view", e.getMessage());
        }
    }

    public void setStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }

    public void showProgress(boolean show) {
        Platform.runLater(() -> progressIndicator.setVisible(show));
    }

    public void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    public void showInfo(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    /**
     * Get password silently (from cache or keychain, without prompting).
     * Returns null if password not available.
     */
    private String getPasswordSilent() {
        if (currentPassword != null) {
            return currentPassword;
        }

        // Try to get from keychain
        try {
            String profile = services.profiles().getActiveProfile();
            if (profile != null) {
                var stored = services.credentials().getStoredPassword(profile);
                if (stored.isPresent()) {
                    currentPassword = stored.get();
                    return currentPassword;
                }
            }
        } catch (Exception e) {
            // Return null
        }

        return null;
    }

    /**
     * Get or prompt for password.
     */
    public String getPassword() {
        // First try silent retrieval
        String password = getPasswordSilent();
        if (password != null) {
            // Start cache service if not started yet
            if (!cacheServiceStarted && cacheService != null) {
                cacheService.start(password);
                cacheServiceStarted = true;
            }
            return password;
        }

        // Get current profile for keychain storage
        String currentProfile = null;
        try {
            currentProfile = services.profiles().getActiveProfile();
        } catch (Exception e) {
            // Ignore
        }

        // Create custom dialog with password field and save checkbox
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Password Required");
        dialog.setHeaderText("Enter your app password" +
            (currentProfile != null ? " for " + currentProfile : ""));

        // Set up buttons
        ButtonType loginButton = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(loginButton, ButtonType.CANCEL);

        // Create dialog content
        VBox content = new VBox(10);
        content.setPadding(new javafx.geometry.Insets(10));

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        passwordField.setPrefWidth(300);

        CheckBox saveToKeychain = new CheckBox("Save password to keychain");
        saveToKeychain.setSelected(false);

        // Only show checkbox if keychain is available
        boolean keychainAvailable = services.credentials().isKeychainAvailable();
        saveToKeychain.setVisible(keychainAvailable);
        saveToKeychain.setManaged(keychainAvailable);

        content.getChildren().addAll(
            new Label("Password:"),
            passwordField,
            saveToKeychain
        );

        dialog.getDialogPane().setContent(content);

        // Focus on password field
        Platform.runLater(passwordField::requestFocus);

        // Convert result
        final String profileForSave = currentProfile;
        dialog.setResultConverter(buttonType -> {
            if (buttonType == loginButton) {
                String pwd = passwordField.getText();
                if (pwd != null && !pwd.isEmpty()) {
                    // Save to keychain if requested
                    if (saveToKeychain.isSelected() && profileForSave != null) {
                        boolean saved = services.credentials().savePassword(profileForSave, pwd);
                        if (saved) {
                            setStatus("Password saved to keychain for " + profileForSave);
                        }
                    }
                    return pwd;
                }
            }
            return null;
        });

        var result = dialog.showAndWait();
        if (result.isPresent() && result.get() != null) {
            currentPassword = result.get();

            // Start cache service with the password
            if (!cacheServiceStarted && cacheService != null) {
                cacheService.start(currentPassword);
                cacheServiceStarted = true;
            }

            return currentPassword;
        }
        return null;
    }

    /**
     * Clear cached password.
     */
    public void clearPassword() {
        currentPassword = null;
    }

    /**
     * Refresh profiles list.
     */
    public void refreshProfiles() {
        loadProfiles();
    }

    public ServiceContext getServices() {
        return services;
    }

    /**
     * Get the message cache service.
     */
    public MessageCacheService getCacheService() {
        return cacheService;
    }
}
