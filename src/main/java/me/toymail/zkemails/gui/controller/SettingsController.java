package me.toymail.zkemails.gui.controller;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import me.toymail.zkemails.gui.util.TaskRunner;
import me.toymail.zkemails.service.InitService;
import me.toymail.zkemails.service.ServiceContext;

import java.util.List;
import java.util.Optional;

/**
 * Controller for the settings view.
 */
public class SettingsController {
    private final ServiceContext services;
    private final MainController mainController;

    @FXML private VBox initSection;
    @FXML private TextField emailField;
    @FXML private PasswordField passwordField;
    @FXML private TextField imapHostField;
    @FXML private TextField imapPortField;
    @FXML private TextField smtpHostField;
    @FXML private TextField smtpPortField;
    @FXML private CheckBox savePasswordCheck;
    @FXML private Button initButton;

    @FXML private VBox profileSection;
    @FXML private Label activeProfileLabel;
    @FXML private Label fingerprintLabel;
    @FXML private Label keychainStatusLabel;
    @FXML private Label currentFolderLabel;
    @FXML private Button changeFolderButton;

    // Folder selection for new profile
    @FXML private ComboBox<String> folderComboBox;
    @FXML private Button fetchFoldersButton;
    @FXML private ProgressIndicator folderLoadingIndicator;

    @FXML private Label statusLabel;

    public SettingsController(ServiceContext services, MainController mainController) {
        this.services = services;
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Set defaults for Gmail
        imapHostField.setText("imap.gmail.com");
        imapPortField.setText("993");
        smtpHostField.setText("smtp.gmail.com");
        smtpPortField.setText("587");

        // Initialize folder combobox with INBOX as default
        folderComboBox.setItems(FXCollections.observableArrayList("INBOX"));
        folderComboBox.setValue("INBOX");

        // Check if profiles exist
        loadProfileInfo();
    }

    private void loadProfileInfo() {
        TaskRunner.run("Loading profile info", () -> {
            boolean hasProfiles = services.profiles().hasProfiles();
            String activeProfile = services.profiles().getActiveProfile();
            boolean keychainAvailable = services.credentials().isKeychainAvailable();
            String currentFolder = activeProfile != null ? services.init().getImapFolder(activeProfile) : "INBOX";
            return new ProfileInfo(hasProfiles, activeProfile, keychainAvailable, currentFolder);
        }, new TaskRunner.TaskCallback<>() {
            @Override
            public void onSuccess(ProfileInfo info) {
                if (info.hasProfiles && info.activeProfile != null) {
                    activeProfileLabel.setText(info.activeProfile);
                    profileSection.setVisible(true);

                    // Load fingerprint
                    loadFingerprint();

                    // Show current folder
                    currentFolderLabel.setText(info.currentFolder);

                    if (info.keychainAvailable) {
                        boolean hasPassword = services.credentials().hasStoredPassword(info.activeProfile);
                        keychainStatusLabel.setText(hasPassword ? "Password stored in keychain" : "Password not stored");
                    } else {
                        keychainStatusLabel.setText("Keychain not available");
                    }
                } else {
                    profileSection.setVisible(false);
                    statusLabel.setText("No profile configured. Initialize one below.");
                }
            }

            @Override
            public void onError(Throwable error) {
                statusLabel.setText("Error: " + error.getMessage());
            }
        });
    }

    private void loadFingerprint() {
        try {
            var store = services.storeContext().zkStore();
            if (store != null) {
                var keys = store.readJson("keys.json", me.toymail.zkemails.crypto.IdentityKeys.KeyBundle.class);
                if (keys != null) {
                    fingerprintLabel.setText(keys.fingerprintHex());
                }
            }
        } catch (Exception e) {
            fingerprintLabel.setText("(error loading)");
        }
    }

    @FXML
    public void initializeProfile() {
        String email = emailField.getText();
        String password = passwordField.getText();
        String imapHost = imapHostField.getText();
        String smtpHost = smtpHostField.getText();

        // Validation
        if (email == null || email.isBlank() || !email.contains("@")) {
            mainController.showError("Validation Error", "Please enter a valid email address");
            return;
        }
        if (password == null || password.isBlank()) {
            mainController.showError("Validation Error", "Please enter your app password");
            return;
        }

        int imapPort, smtpPort;
        try {
            imapPort = Integer.parseInt(imapPortField.getText());
            smtpPort = Integer.parseInt(smtpPortField.getText());
        } catch (NumberFormatException e) {
            mainController.showError("Validation Error", "Invalid port numbers");
            return;
        }

        initButton.setDisable(true);
        mainController.showProgress(true);
        mainController.setStatus("Testing connection...");

        String selectedFolder = folderComboBox.getValue();
        if (selectedFolder == null || selectedFolder.isBlank()) {
            selectedFolder = "INBOX";
        }

        InitService.InitConfig config = new InitService.InitConfig(
            email, password, imapHost, imapPort, smtpHost, smtpPort, selectedFolder
        );

        TaskRunner.run("Initializing profile", () -> services.init().initializeWithValidation(config),
            new TaskRunner.TaskCallback<>() {
                @Override
                public void onSuccess(InitService.InitResult result) {
                    initButton.setDisable(false);
                    mainController.showProgress(false);

                    if (result.success()) {
                        mainController.setStatus("Profile initialized!");

                        // Save password to keychain if requested
                        if (savePasswordCheck.isSelected()) {
                            boolean saved = services.init().savePasswordToKeychain(email, password);
                            if (saved) {
                                mainController.showInfo("Success",
                                    result.message() + "\n\nPassword saved to keychain.\nFingerprint: " + result.fingerprint());
                            } else {
                                mainController.showInfo("Success",
                                    result.message() + "\n\nCould not save password to keychain.\nFingerprint: " + result.fingerprint());
                            }
                        } else {
                            mainController.showInfo("Success",
                                result.message() + "\n\nFingerprint: " + result.fingerprint());
                        }

                        // Clear form
                        passwordField.clear();

                        // Refresh profile info and main controller
                        loadProfileInfo();
                        mainController.refreshProfiles();
                    } else {
                        mainController.setStatus("Initialization failed");
                        mainController.showError("Initialization Failed", result.message());
                    }
                }

                @Override
                public void onError(Throwable error) {
                    initButton.setDisable(false);
                    mainController.showProgress(false);
                    mainController.showError("Error", error.getMessage());
                }
            });
    }

    @FXML
    public void deleteCredential() {
        try {
            String profile = services.profiles().getActiveProfile();
            if (profile == null) {
                mainController.showError("Error", "No active profile");
                return;
            }

            boolean deleted = services.credentials().deletePassword(profile);
            if (deleted) {
                mainController.showInfo("Success", "Password removed from keychain");
                mainController.clearPassword();
                loadProfileInfo();
            } else {
                mainController.showInfo("Info", "No password was stored for this profile");
            }
        } catch (Exception e) {
            mainController.showError("Error", e.getMessage());
        }
    }

    private record ProfileInfo(boolean hasProfiles, String activeProfile, boolean keychainAvailable, String currentFolder) {}

    /**
     * Fetch available folders from IMAP server.
     */
    @FXML
    public void fetchFolders() {
        String email = emailField.getText();
        String password = passwordField.getText();
        String imapHost = imapHostField.getText();

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            mainController.showError("Validation Error", "Please enter email and password first");
            return;
        }

        int imapPort;
        try {
            imapPort = Integer.parseInt(imapPortField.getText());
        } catch (NumberFormatException e) {
            mainController.showError("Validation Error", "Invalid IMAP port");
            return;
        }

        fetchFoldersButton.setDisable(true);
        folderLoadingIndicator.setVisible(true);
        statusLabel.setText("Fetching folders...");

        TaskRunner.run("Fetching folders", () -> services.init().listImapFolders(email, password, imapHost, imapPort),
            new TaskRunner.TaskCallback<>() {
                @Override
                public void onSuccess(List<String> folders) {
                    fetchFoldersButton.setDisable(false);
                    folderLoadingIndicator.setVisible(false);
                    statusLabel.setText("Found " + folders.size() + " folders");

                    folderComboBox.setItems(FXCollections.observableArrayList(folders));
                    folderComboBox.setDisable(false);

                    // Select INBOX by default if available
                    if (folders.contains("INBOX")) {
                        folderComboBox.setValue("INBOX");
                    } else if (!folders.isEmpty()) {
                        folderComboBox.setValue(folders.get(0));
                    }
                }

                @Override
                public void onError(Throwable error) {
                    fetchFoldersButton.setDisable(false);
                    folderLoadingIndicator.setVisible(false);
                    statusLabel.setText("Failed to fetch folders");
                    mainController.showError("Connection Error", "Could not fetch folders: " + error.getMessage());
                }
            });
    }

    /**
     * Show dialog to change folder for existing profile.
     */
    @FXML
    public void showFolderDialog() {
        String activeProfile = activeProfileLabel.getText();
        if (activeProfile == null || activeProfile.equals("-")) {
            mainController.showError("Error", "No active profile");
            return;
        }

        // Get password from keychain or prompt
        String password = mainController.getPassword();
        if (password == null || password.isBlank()) {
            mainController.showError("Error", "Please enter your password first (use the password field in Messages view)");
            return;
        }

        // Show loading
        changeFolderButton.setDisable(true);
        mainController.setStatus("Fetching folders...");

        TaskRunner.run("Fetching folders", () -> {
            var store = services.storeContext().zkStore();
            var cfg = store.readJson("config.json", me.toymail.zkemails.store.Config.class);
            return services.init().listImapFolders(cfg.email, password, cfg.imap.host, cfg.imap.port);
        }, new TaskRunner.TaskCallback<>() {
            @Override
            public void onSuccess(List<String> folders) {
                changeFolderButton.setDisable(false);
                mainController.setStatus("");

                // Show choice dialog
                ChoiceDialog<String> dialog = new ChoiceDialog<>(currentFolderLabel.getText(), folders);
                dialog.setTitle("Select IMAP Folder");
                dialog.setHeaderText("Choose the folder to read encrypted messages from");
                dialog.setContentText("Folder:");

                Optional<String> result = dialog.showAndWait();
                result.ifPresent(selectedFolder -> {
                    // Update folder in config
                    TaskRunner.run("Updating folder", () -> {
                        services.init().updateImapFolder(activeProfile, selectedFolder);
                        return selectedFolder;
                    }, new TaskRunner.TaskCallback<>() {
                        @Override
                        public void onSuccess(String folder) {
                            currentFolderLabel.setText(folder);
                            mainController.showInfo("Success", "IMAP folder changed to: " + folder +
                                "\n\nRestart the application for changes to take effect.");
                        }

                        @Override
                        public void onError(Throwable error) {
                            mainController.showError("Error", "Failed to update folder: " + error.getMessage());
                        }
                    });
                });
            }

            @Override
            public void onError(Throwable error) {
                changeFolderButton.setDisable(false);
                mainController.setStatus("");
                mainController.showError("Connection Error", "Could not fetch folders: " + error.getMessage());
            }
        });
    }
}
