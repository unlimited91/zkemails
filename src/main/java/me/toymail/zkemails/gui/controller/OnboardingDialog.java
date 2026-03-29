package me.toymail.zkemails.gui.controller;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import me.toymail.zkemails.gui.util.TaskRunner;
import me.toymail.zkemails.service.InitService;
import me.toymail.zkemails.service.InviteService;
import me.toymail.zkemails.service.ServiceContext;

import java.util.List;
import java.util.Optional;

/**
 * First-time user onboarding dialog.
 * Shows when no profiles exist and guides the user through initial setup.
 */
public class OnboardingDialog {

    /**
     * Result of successful onboarding.
     */
    public record OnboardingResult(
        String email,
        String password,
        boolean savedToKeychain
    ) {}

    /**
     * Callback interface for post-onboarding actions.
     */
    public interface OnboardingCallback {
        void onOnboardingComplete(OnboardingResult result);
        void setStatus(String message);
        void showProgress(boolean show);
        void showInfo(String title, String message);
        void selectTab(String tabName);
    }

    private final ServiceContext services;
    private final OnboardingCallback callback;

    public OnboardingDialog(ServiceContext services, OnboardingCallback callback) {
        this.services = services;
        this.callback = callback;
    }

    /**
     * Check if onboarding should be shown (no profiles exist).
     */
    public boolean shouldShow() {
        try {
            return !services.profiles().hasProfiles();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Show onboarding dialog if needed (no profiles exist).
     */
    public void showIfNeeded() {
        if (shouldShow()) {
            show();
        }
    }

    /**
     * Show the onboarding dialog.
     * @return Optional containing the result if successful, empty if cancelled
     */
    public Optional<OnboardingResult> show() {
        Dialog<OnboardingResult> dialog = new Dialog<>();
        dialog.setTitle("Welcome to ZKEmails");
        dialog.setHeaderText("Let's set up your encrypted email account");

        // Set up buttons
        ButtonType initButton = new ButtonType("Initialize", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(initButton, cancelButton);

        // Main content VBox
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setPrefWidth(400);

        // Form fields
        GridPane formGrid = new GridPane();
        formGrid.setHgap(10);
        formGrid.setVgap(10);

        TextField emailField = new TextField();
        emailField.setPromptText("your.email@gmail.com");
        emailField.setPrefWidth(280);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("App password");

        CheckBox saveToKeychainCheckbox = new CheckBox("Save password to system keychain");
        saveToKeychainCheckbox.setSelected(true);

        // Only show keychain option if available
        boolean keychainAvailable = services.credentials().isKeychainAvailable();
        saveToKeychainCheckbox.setVisible(keychainAvailable);
        saveToKeychainCheckbox.setManaged(keychainAvailable);

        formGrid.add(new Label("Email:"), 0, 0);
        formGrid.add(emailField, 1, 0);
        formGrid.add(new Label("Password:"), 0, 1);
        formGrid.add(passwordField, 1, 1);
        formGrid.add(saveToKeychainCheckbox, 1, 2);

        // Gmail app password hint
        Label hintLabel = new Label("For Gmail, use an App Password (not your regular email password)");
        hintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        hintLabel.setWrapText(true);

        // Advanced settings toggle
        Hyperlink advancedToggle = new Hyperlink("Show Advanced Settings");
        VBox advancedSection = new VBox(10);
        advancedSection.setVisible(false);
        advancedSection.setManaged(false);

        // Advanced settings fields
        GridPane advancedGrid = new GridPane();
        advancedGrid.setHgap(10);
        advancedGrid.setVgap(8);

        TextField imapHostField = new TextField("imap.gmail.com");
        TextField imapPortField = new TextField("993");
        imapPortField.setPrefWidth(80);
        TextField smtpHostField = new TextField("smtp.gmail.com");
        TextField smtpPortField = new TextField("587");
        smtpPortField.setPrefWidth(80);

        advancedGrid.add(new Label("IMAP Host:"), 0, 0);
        advancedGrid.add(imapHostField, 1, 0);
        advancedGrid.add(new Label("Port:"), 2, 0);
        advancedGrid.add(imapPortField, 3, 0);
        advancedGrid.add(new Label("SMTP Host:"), 0, 1);
        advancedGrid.add(smtpHostField, 1, 1);
        advancedGrid.add(new Label("Port:"), 2, 1);
        advancedGrid.add(smtpPortField, 3, 1);

        advancedSection.getChildren().add(advancedGrid);

        // Toggle advanced visibility
        final boolean[] advancedVisible = {false};
        advancedToggle.setOnAction(e -> {
            advancedVisible[0] = !advancedVisible[0];
            advancedSection.setVisible(advancedVisible[0]);
            advancedSection.setManaged(advancedVisible[0]);
            advancedToggle.setText(advancedVisible[0] ? "Hide Advanced Settings" : "Show Advanced Settings");
            dialog.getDialogPane().getScene().getWindow().sizeToScene();
        });

        // Progress section (initially hidden)
        HBox progressSection = new HBox(10);
        progressSection.setAlignment(Pos.CENTER_LEFT);
        progressSection.setVisible(false);
        progressSection.setManaged(false);
        ProgressIndicator progressSpinner = new ProgressIndicator();
        progressSpinner.setPrefSize(20, 20);
        Label progressLabel = new Label("Connecting...");
        progressSection.getChildren().addAll(progressSpinner, progressLabel);

        // Status/error label
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #c0392b;");
        statusLabel.setWrapText(true);

        // Assemble content
        content.getChildren().addAll(
            new Label("Enter your email credentials:"),
            formGrid,
            hintLabel,
            advancedToggle,
            advancedSection,
            progressSection,
            statusLabel
        );

        dialog.getDialogPane().setContent(content);

        // Get the button nodes
        Node initButtonNode = dialog.getDialogPane().lookupButton(initButton);
        Node cancelButtonNode = dialog.getDialogPane().lookupButton(cancelButton);

        // Focus on email field
        Platform.runLater(emailField::requestFocus);

        // Handle Initialize button click
        initButtonNode.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String email = emailField.getText().trim();
            String password = passwordField.getText();

            // Validation
            if (email.isEmpty() || !email.contains("@")) {
                statusLabel.setText("Please enter a valid email address");
                event.consume();
                return;
            }
            if (password.isEmpty()) {
                statusLabel.setText("Please enter your password");
                event.consume();
                return;
            }

            // Show progress
            progressSection.setVisible(true);
            progressSection.setManaged(true);
            progressLabel.setText("Testing connections...");
            initButtonNode.setDisable(true);
            statusLabel.setText("");

            // Get IMAP/SMTP settings
            String imapHost = advancedVisible[0] ? imapHostField.getText() : "imap.gmail.com";
            int imapPort = advancedVisible[0] ? Integer.parseInt(imapPortField.getText()) : 993;
            String smtpHost = advancedVisible[0] ? smtpHostField.getText() : "smtp.gmail.com";
            int smtpPort = advancedVisible[0] ? Integer.parseInt(smtpPortField.getText()) : 587;

            InitService.InitConfig config = new InitService.InitConfig(
                email, password, imapHost, imapPort, smtpHost, smtpPort, "INBOX"
            );

            // Run initialization in background
            TaskRunner.run("Initializing profile", () -> {
                return services.init().initializeWithValidation(config);
            }, new TaskRunner.TaskCallback<InitService.InitResult>() {
                @Override
                public void onSuccess(InitService.InitResult result) {
                    if (result.success()) {
                        // Save to keychain if requested
                        if (saveToKeychainCheckbox.isSelected()) {
                            services.credentials().savePassword(email, password);
                        }

                        // Close dialog with result
                        dialog.setResult(new OnboardingResult(email, password, saveToKeychainCheckbox.isSelected()));
                        dialog.close();
                    } else {
                        // Initialization failed
                        progressSection.setVisible(false);
                        progressSection.setManaged(false);
                        initButtonNode.setDisable(false);
                        statusLabel.setText(result.message());
                    }
                }

                @Override
                public void onError(Throwable error) {
                    progressSection.setVisible(false);
                    progressSection.setManaged(false);
                    initButtonNode.setDisable(false);
                    String msg = error.getMessage();
                    if (msg != null && msg.contains("AUTHENTICATE")) {
                        statusLabel.setText("Authentication failed. For Gmail, use an App Password.");
                    } else {
                        statusLabel.setText("Error: " + msg);
                    }
                }
            });

            // Prevent dialog from closing
            event.consume();
        });

        // Handle Cancel button click
        cancelButtonNode.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Setup Later");
            alert.setHeaderText(null);
            alert.setContentText("You can initialize your profile later by going to the Settings section.");
            alert.showAndWait();
        });

        // Show dialog and handle result
        Optional<OnboardingResult> result = dialog.showAndWait();
        if (result.isPresent()) {
            callback.onOnboardingComplete(result.get());
            fetchInvitesAfterOnboarding(result.get().password());
            callback.showInfo("Profile Created",
                "Your profile has been created successfully!\n\n" +
                "Next step: Send or accept invites to exchange keys with contacts.");
        }
        return result;
    }

    /**
     * Fetch invites after onboarding and navigate to Invites tab if any are found.
     */
    private void fetchInvitesAfterOnboarding(String password) {
        callback.setStatus("Checking for invites...");
        callback.showProgress(true);

        TaskRunner.run("Fetching invites", () -> {
            return services.invites().fetchInvitesFromImap(password, 100);
        }, new TaskRunner.TaskCallback<List<InviteService.ImapInvite>>() {
            @Override
            public void onSuccess(List<InviteService.ImapInvite> invites) {
                callback.showProgress(false);

                int pendingCount = (int) invites.stream()
                    .filter(i -> !i.alreadyAcked())
                    .count();

                if (pendingCount > 0) {
                    callback.setStatus("Found " + pendingCount + " pending invite(s)!");
                    callback.selectTab("Invites");
                    callback.showInfo("Invites Found",
                        "You have " + pendingCount + " pending invite(s).\n" +
                        "Accept them to start exchanging encrypted emails.");
                } else {
                    callback.setStatus("Ready! Send an invite to get started.");
                }
            }

            @Override
            public void onError(Throwable error) {
                callback.showProgress(false);
                callback.setStatus("Could not check for invites: " + error.getMessage());
            }
        });
    }
}
