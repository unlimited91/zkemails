package me.toymail.zkemails.gui.controller;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import me.toymail.zkemails.gui.util.TaskRunner;
import me.toymail.zkemails.service.ContactService;
import me.toymail.zkemails.service.MessageService;
import me.toymail.zkemails.service.ServiceContext;

import java.util.List;

/**
 * Controller for the compose message view.
 */
public class ComposeController {
    private final ServiceContext services;
    private final MainController mainController;

    @FXML private ComboBox<String> recipientCombo;
    @FXML private TextField subjectField;
    @FXML private TextArea bodyArea;
    @FXML private Button sendButton;
    @FXML private Label statusLabel;

    public ComposeController(ServiceContext services, MainController mainController) {
        this.services = services;
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        loadContacts();
    }

    private void loadContacts() {
        TaskRunner.run("Loading contacts", () -> services.contacts().getReadyContacts(),
            new TaskRunner.TaskCallback<>() {
                @Override
                public void onSuccess(List<ContactService.ContactInfo> contacts) {
                    recipientCombo.setItems(FXCollections.observableArrayList(
                        contacts.stream().map(ContactService.ContactInfo::email).toList()
                    ));
                    if (contacts.isEmpty()) {
                        statusLabel.setText("No contacts ready for encrypted messaging. Send invites first!");
                    } else {
                        statusLabel.setText("Select a recipient and compose your message.");
                    }
                }

                @Override
                public void onError(Throwable error) {
                    statusLabel.setText("Error loading contacts: " + error.getMessage());
                }
            });
    }

    @FXML
    public void send() {
        String recipient = recipientCombo.getValue();
        String subject = subjectField.getText();
        String body = bodyArea.getText();

        // Validation
        if (recipient == null || recipient.isBlank()) {
            mainController.showError("Validation Error", "Please select a recipient");
            return;
        }
        if (subject == null || subject.isBlank()) {
            mainController.showError("Validation Error", "Please enter a subject");
            return;
        }
        if (body == null || body.isBlank()) {
            mainController.showError("Validation Error", "Please enter a message body");
            return;
        }

        String password = mainController.getPassword();
        if (password == null) return;

        sendButton.setDisable(true);
        mainController.showProgress(true);
        mainController.setStatus("Sending message...");

        TaskRunner.run("Sending message", () -> services.messages().sendMessage(password, recipient, subject, body),
            new TaskRunner.TaskCallback<>() {
                @Override
                public void onSuccess(MessageService.SendResult result) {
                    sendButton.setDisable(false);
                    mainController.showProgress(false);

                    if (result.success()) {
                        mainController.setStatus("Message sent!");
                        mainController.showInfo("Success", result.message());
                        clearForm();
                    } else {
                        mainController.setStatus("Failed to send");
                        mainController.showError("Send Failed", result.message());
                    }
                }

                @Override
                public void onError(Throwable error) {
                    sendButton.setDisable(false);
                    mainController.showProgress(false);
                    mainController.showError("Error", error.getMessage());
                }
            });
    }

    @FXML
    public void clearForm() {
        recipientCombo.setValue(null);
        subjectField.clear();
        bodyArea.clear();
        statusLabel.setText("Form cleared.");
    }
}
