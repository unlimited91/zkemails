package me.toymail.zkemails.gui.controller;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import me.toymail.zkemails.gui.util.TaskRunner;
import me.toymail.zkemails.service.InitService;
import me.toymail.zkemails.service.ServiceContext;

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

        // Check if profiles exist
        loadProfileInfo();
    }

    private void loadProfileInfo() {
        TaskRunner.run("Loading profile info", () -> {
            boolean hasProfiles = services.profiles().hasProfiles();
            String activeProfile = services.profiles().getActiveProfile();
            boolean keychainAvailable = services.credentials().isKeychainAvailable();
            return new ProfileInfo(hasProfiles, activeProfile, keychainAvailable);
        }, new TaskRunner.TaskCallback<>() {
            @Override
            public void onSuccess(ProfileInfo info) {
                if (info.hasProfiles && info.activeProfile != null) {
                    activeProfileLabel.setText(info.activeProfile);
                    profileSection.setVisible(true);

                    // Load fingerprint
                    loadFingerprint();

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

        InitService.InitConfig config = new InitService.InitConfig(
            email, password, imapHost, imapPort, smtpHost, smtpPort
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

    private record ProfileInfo(boolean hasProfiles, String activeProfile, boolean keychainAvailable) {}
}
