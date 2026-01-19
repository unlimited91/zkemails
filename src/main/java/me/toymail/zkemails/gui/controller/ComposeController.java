package me.toymail.zkemails.gui.controller;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
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
 * Supports single and multi-recipient sending with To/CC/BCC.
 */
public class ComposeController {
    private final ServiceContext services;
    private final MainController mainController;

    // To field
    @FXML private ComboBox<String> toCombo;
    @FXML private FlowPane toChips;

    // CC field (hidden by default)
    @FXML private Label ccLabel;
    @FXML private HBox ccRow;
    @FXML private ComboBox<String> ccCombo;
    @FXML private FlowPane ccChips;

    // BCC field (hidden by default)
    @FXML private Label bccLabel;
    @FXML private HBox bccRow;
    @FXML private ComboBox<String> bccCombo;
    @FXML private FlowPane bccChips;

    @FXML private Button ccBccToggle;
    @FXML private TextField subjectField;
    @FXML private TextArea bodyArea;
    @FXML private Button sendButton;
    @FXML private Label statusLabel;
    @FXML private ListView<AttachmentItem> attachmentList;
    @FXML private Label attachmentCountLabel;

    // Recipient lists
    private final ObservableList<String> toRecipients = FXCollections.observableArrayList();
    private final ObservableList<String> ccRecipients = FXCollections.observableArrayList();
    private final ObservableList<String> bccRecipients = FXCollections.observableArrayList();

    private final ObservableList<AttachmentItem> attachments = FXCollections.observableArrayList();
    private List<String> availableContacts = new ArrayList<>();
    private boolean ccBccVisible = false;

    public ComposeController(ServiceContext services, MainController mainController) {
        this.services = services;
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        loadContacts();
        setupAttachmentList();

        // Add listeners to update chips when lists change
        toRecipients.addListener((javafx.collections.ListChangeListener<String>) c -> updateChips(toChips, toRecipients));
        ccRecipients.addListener((javafx.collections.ListChangeListener<String>) c -> updateChips(ccChips, ccRecipients));
        bccRecipients.addListener((javafx.collections.ListChangeListener<String>) c -> updateChips(bccChips, bccRecipients));
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
                    availableContacts = contacts.stream()
                            .map(ContactService.ContactInfo::email)
                            .toList();

                    ObservableList<String> items = FXCollections.observableArrayList(availableContacts);
                    toCombo.setItems(items);
                    ccCombo.setItems(FXCollections.observableArrayList(availableContacts));
                    bccCombo.setItems(FXCollections.observableArrayList(availableContacts));

                    if (contacts.isEmpty()) {
                        statusLabel.setText("No contacts ready for encrypted messaging. Send invites first!");
                    } else {
                        statusLabel.setText("Select recipients and compose your message.");
                    }
                }

                @Override
                public void onError(Throwable error) {
                    statusLabel.setText("Error loading contacts: " + error.getMessage());
                }
            });
    }

    @FXML
    public void toggleCcBcc() {
        ccBccVisible = !ccBccVisible;

        ccLabel.setVisible(ccBccVisible);
        ccLabel.setManaged(ccBccVisible);
        ccRow.setVisible(ccBccVisible);
        ccRow.setManaged(ccBccVisible);

        bccLabel.setVisible(ccBccVisible);
        bccLabel.setManaged(ccBccVisible);
        bccRow.setVisible(ccBccVisible);
        bccRow.setManaged(ccBccVisible);

        ccBccToggle.setText(ccBccVisible ? "Hide CC/BCC" : "CC/BCC");
    }

    @FXML
    public void addToRecipient() {
        addRecipient(toCombo, toRecipients);
    }

    @FXML
    public void addCcRecipient() {
        addRecipient(ccCombo, ccRecipients);
    }

    @FXML
    public void addBccRecipient() {
        addRecipient(bccCombo, bccRecipients);
    }

    private void addRecipient(ComboBox<String> combo, ObservableList<String> list) {
        String email = combo.getValue();
        if (email == null || email.isBlank()) {
            return;
        }

        final String normalizedEmail = email.trim().toLowerCase();

        // Check if already added in any list
        if (toRecipients.contains(normalizedEmail) || ccRecipients.contains(normalizedEmail) || bccRecipients.contains(normalizedEmail)) {
            mainController.showError("Duplicate", normalizedEmail + " is already added");
            return;
        }

        // Check if contact exists with keys
        boolean contactReady = availableContacts.stream()
                .anyMatch(c -> c.equalsIgnoreCase(normalizedEmail));

        if (!contactReady) {
            mainController.showError("Contact Not Ready",
                normalizedEmail + " is not a contact with exchanged keys.\nSend an invite first.");
            return;
        }

        list.add(normalizedEmail);
        combo.setValue(null);
    }

    private void updateChips(FlowPane pane, ObservableList<String> recipients) {
        pane.getChildren().clear();
        for (String email : recipients) {
            Label chip = createChip(email, recipients);
            pane.getChildren().add(chip);
        }
    }

    private Label createChip(String email, ObservableList<String> list) {
        // Show just the username part for brevity
        String display = email.contains("@") ? email.substring(0, email.indexOf("@")) : email;

        Label chip = new Label(display + " x");
        chip.getStyleClass().add("recipient-chip");
        chip.setTooltip(new Tooltip(email + "\nClick to remove"));

        chip.setOnMouseClicked(e -> list.remove(email));

        return chip;
    }

    @FXML
    public void addAttachments() {
        Window window = attachmentList.getScene().getWindow();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Files to Attach");
        List<File> files = chooser.showOpenMultipleDialog(window);

        if (files != null) {
            for (File file : files) {
                if (file.length() > MessageService.MAX_ATTACHMENT_SIZE) {
                    mainController.showError("File Too Large",
                            file.getName() + " exceeds 25MB limit (" + formatSize(file.length()) + ")");
                    continue;
                }

                boolean duplicate = attachments.stream()
                        .anyMatch(a -> a.path().equals(file.toPath()));
                if (duplicate) continue;

                attachments.add(new AttachmentItem(file.getName(), file.toPath(), file.length()));
            }
            updateAttachmentCount();
        }
    }

    @FXML
    public void send() {
        String subject = subjectField.getText();
        String body = bodyArea.getText();

        // Validation
        if (toRecipients.isEmpty()) {
            mainController.showError("Validation Error", "Please add at least one To recipient");
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

        int totalRecipients = toRecipients.size() + ccRecipients.size() + bccRecipients.size();
        boolean isMultiRecipient = totalRecipients > 1 || !ccRecipients.isEmpty() || !bccRecipients.isEmpty();

        List<Path> attachmentPaths = attachments.stream()
                .map(AttachmentItem::path)
                .toList();

        if (isMultiRecipient) {
            // Use v2 multi-recipient send
            if (!attachmentPaths.isEmpty()) {
                mainController.showInfo("Note", "Attachments not yet supported for multi-recipient messages.");
            }

            String statusMsg = "Sending to " + totalRecipients + " recipient(s)...";
            mainController.setStatus(statusMsg);

            MessageService.MultiRecipientInput recipients = new MessageService.MultiRecipientInput(
                new ArrayList<>(toRecipients),
                ccRecipients.isEmpty() ? null : new ArrayList<>(ccRecipients),
                bccRecipients.isEmpty() ? null : new ArrayList<>(bccRecipients)
            );

            TaskRunner.run("Sending multi-recipient message",
                () -> services.messages().sendMultiRecipientMessage(password, recipients, subject, body,
                        null, null, null),
                new TaskRunner.TaskCallback<>() {
                    @Override
                    public void onSuccess(MessageService.MultiSendResult result) {
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
        } else {
            // Use v1 single-recipient send (backward compatible)
            String recipient = toRecipients.get(0);
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
    }

    @FXML
    public void clearForm() {
        toCombo.setValue(null);
        ccCombo.setValue(null);
        bccCombo.setValue(null);
        toRecipients.clear();
        ccRecipients.clear();
        bccRecipients.clear();
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
