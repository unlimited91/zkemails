package me.toymail.zkemails.gui.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import me.toymail.zkemails.gui.util.TaskRunner;
import me.toymail.zkemails.service.ContactService;
import me.toymail.zkemails.service.InviteService;
import me.toymail.zkemails.service.ServiceContext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Controller for the contacts view.
 */
public class ContactsController {
    private final ServiceContext services;
    private final MainController mainController;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @FXML private TableView<ContactRow> contactsTable;
    @FXML private TableColumn<ContactRow, String> emailColumn;
    @FXML private TableColumn<ContactRow, String> statusColumn;
    @FXML private TableColumn<ContactRow, String> lastSeenColumn;
    @FXML private TextField inviteEmailField;
    @FXML private Label statusLabel;

    private final ObservableList<ContactRow> contacts = FXCollections.observableArrayList();

    public ContactsController(ServiceContext services, MainController mainController) {
        this.services = services;
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        emailColumn.setCellValueFactory(new PropertyValueFactory<>("email"));
        statusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        lastSeenColumn.setCellValueFactory(new PropertyValueFactory<>("lastSeen"));

        // Style status column
        statusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(status);
                    if ("ready".equals(status)) {
                        setStyle("-fx-text-fill: #27ae60; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #f39c12;");
                    }
                }
            }
        });

        contactsTable.setItems(contacts);
        refresh();
    }

    @FXML
    public void refresh() {
        TaskRunner.run("Loading contacts", () -> services.contacts().listContacts(),
            new TaskRunner.TaskCallback<>() {
                @Override
                public void onSuccess(List<ContactService.ContactInfo> result) {
                    contacts.clear();
                    for (var c : result) {
                        contacts.add(new ContactRow(c));
                    }
                    statusLabel.setText("Loaded " + result.size() + " contacts");
                }

                @Override
                public void onError(Throwable error) {
                    statusLabel.setText("Error: " + error.getMessage());
                }
            });
    }

    @FXML
    public void sendInvite() {
        String email = inviteEmailField.getText();
        if (email == null || email.isBlank() || !email.contains("@")) {
            mainController.showError("Validation Error", "Please enter a valid email address");
            return;
        }

        String password = mainController.getPassword();
        if (password == null) return;

        mainController.showProgress(true);
        mainController.setStatus("Sending invite...");

        TaskRunner.run("Sending invite", () -> services.invites().sendInvite(password, email),
            new TaskRunner.TaskCallback<>() {
                @Override
                public void onSuccess(InviteService.SendInviteResult result) {
                    mainController.showProgress(false);
                    if (result.success()) {
                        mainController.setStatus("Invite sent!");
                        mainController.showInfo("Invite Sent", result.message() + "\nInvite ID: " + result.inviteId());
                        inviteEmailField.clear();
                        refresh(); // Reload contacts
                    } else {
                        mainController.setStatus("Failed to send invite");
                        mainController.showError("Send Failed", result.message());
                    }
                }

                @Override
                public void onError(Throwable error) {
                    mainController.showProgress(false);
                    mainController.showError("Error", error.getMessage());
                }
            });
    }

    @FXML
    public void syncAcceptMessages() {
        String password = mainController.getPassword();
        if (password == null) return;

        mainController.showProgress(true);
        mainController.setStatus("Syncing accept messages...");

        TaskRunner.run("Syncing accepts", () -> services.invites().syncAcceptMessages(password, 200),
            new TaskRunner.TaskCallback<>() {
                @Override
                public void onSuccess(Integer count) {
                    mainController.showProgress(false);
                    mainController.setStatus("Synced " + count + " contacts");
                    mainController.showInfo("Sync Complete", "Updated " + count + " contacts with keys");
                    refresh();
                }

                @Override
                public void onError(Throwable error) {
                    mainController.showProgress(false);
                    mainController.showError("Sync Failed", error.getMessage());
                }
            });
    }

    /**
     * Table row model for contacts.
     */
    public static class ContactRow {
        private final StringProperty email;
        private final StringProperty status;
        private final StringProperty lastSeen;

        public ContactRow(ContactService.ContactInfo info) {
            this.email = new SimpleStringProperty(info.email());
            this.status = new SimpleStringProperty(info.status() != null ? info.status() : "unknown");

            String lastSeenStr = "";
            if (info.lastUpdatedEpochSec() > 0) {
                lastSeenStr = Instant.ofEpochSecond(info.lastUpdatedEpochSec())
                    .atZone(ZoneId.systemDefault())
                    .format(DATE_FORMAT);
            }
            this.lastSeen = new SimpleStringProperty(lastSeenStr);
        }

        public String getEmail() { return email.get(); }
        public StringProperty emailProperty() { return email; }
        public String getStatus() { return status.get(); }
        public StringProperty statusProperty() { return status; }
        public String getLastSeen() { return lastSeen.get(); }
        public StringProperty lastSeenProperty() { return lastSeen; }
    }
}
