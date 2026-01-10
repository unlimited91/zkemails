package me.toymail.zkemails.gui.controller;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import me.toymail.zkemails.gui.util.TaskRunner;
import me.toymail.zkemails.service.ContactService;
import me.toymail.zkemails.service.MessageService;
import me.toymail.zkemails.service.ServiceContext;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
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
    @FXML private ListView<AttachmentItem> attachmentList;
    @FXML private Label attachmentCountLabel;

    private final ObservableList<AttachmentItem> attachments = FXCollections.observableArrayList();

    public ComposeController(ServiceContext services, MainController mainController) {
        this.services = services;
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        loadContacts();
        setupAttachmentList();
    }

    private void setupAttachmentList() {
        attachmentList.setItems(attachments);
        attachmentList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(AttachmentItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.filename() + " (" + formatSize(item.size()) + ")");
                    // Context menu for removing
                    ContextMenu menu = new ContextMenu();
                    MenuItem removeItem = new MenuItem("Remove");
                    removeItem.setOnAction(e -> {
                        attachments.remove(item);
                        updateAttachmentCount();
                    });
                    menu.getItems().add(removeItem);
                    setContextMenu(menu);
                }
            }
        });
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private void updateAttachmentCount() {
        if (attachments.isEmpty()) {
            attachmentCountLabel.setText("");
        } else {
            long totalSize = attachments.stream().mapToLong(AttachmentItem::size).sum();
            attachmentCountLabel.setText(attachments.size() + " file(s), " + formatSize(totalSize));
        }
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
    public void addAttachments() {
        Window window = attachmentList.getScene().getWindow();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Files to Attach");
        List<File> files = chooser.showOpenMultipleDialog(window);

        if (files != null) {
            for (File file : files) {
                // Check size limit (25MB)
                if (file.length() > MessageService.MAX_ATTACHMENT_SIZE) {
                    mainController.showError("File Too Large",
                            file.getName() + " exceeds 25MB limit (" + formatSize(file.length()) + ")");
                    continue;
                }

                // Check for duplicates
                boolean duplicate = attachments.stream()
                        .anyMatch(a -> a.path().equals(file.toPath()));
                if (duplicate) {
                    continue;
                }

                attachments.add(new AttachmentItem(file.getName(), file.toPath(), file.length()));
            }
            updateAttachmentCount();
        }
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

        List<Path> attachmentPaths = attachments.stream()
                .map(AttachmentItem::path)
                .toList();

        String statusMsg = attachmentPaths.isEmpty()
                ? "Sending message..."
                : "Sending message with " + attachmentPaths.size() + " attachment(s)...";
        mainController.setStatus(statusMsg);

        TaskRunner.run("Sending message",
            () -> services.messages().sendMessageWithAttachments(password, recipient, subject, body,
                    attachmentPaths, null, null, null),
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
        attachments.clear();
        updateAttachmentCount();
        statusLabel.setText("Form cleared.");
    }

    /**
     * Attachment item for the ListView.
     */
    public record AttachmentItem(String filename, Path path, long size) {}
}
