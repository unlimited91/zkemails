package me.toymail.zkemails.gui.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import me.toymail.zkemails.gui.util.TaskRunner;
import me.toymail.zkemails.service.InviteService;
import me.toymail.zkemails.service.ServiceContext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Controller for the invites view.
 */
public class InvitesController {
    private final ServiceContext services;
    private final MainController mainController;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @FXML private TableView<InviteRow> pendingTable;
    @FXML private TableColumn<InviteRow, String> pendingIdColumn;
    @FXML private TableColumn<InviteRow, String> pendingEmailColumn;
    @FXML private TableColumn<InviteRow, String> pendingSubjectColumn;
    @FXML private TableColumn<InviteRow, String> pendingDateColumn;

    @FXML private TableView<InviteRow> outgoingTable;
    @FXML private TableColumn<InviteRow, String> outgoingEmailColumn;
    @FXML private TableColumn<InviteRow, String> outgoingSubjectColumn;
    @FXML private TableColumn<InviteRow, String> outgoingDateColumn;
    @FXML private TableColumn<InviteRow, String> outgoingStatusColumn;

    @FXML private Label statusLabel;

    private final ObservableList<InviteRow> pendingInvites = FXCollections.observableArrayList();
    private final ObservableList<InviteRow> outgoingInvites = FXCollections.observableArrayList();

    public InvitesController(ServiceContext services, MainController mainController) {
        this.services = services;
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Pending invites table
        pendingIdColumn.setCellValueFactory(new PropertyValueFactory<>("inviteId"));
        pendingEmailColumn.setCellValueFactory(new PropertyValueFactory<>("email"));
        pendingSubjectColumn.setCellValueFactory(new PropertyValueFactory<>("subject"));
        pendingDateColumn.setCellValueFactory(new PropertyValueFactory<>("dateString"));
        pendingTable.setItems(pendingInvites);

        // Outgoing invites table
        outgoingEmailColumn.setCellValueFactory(new PropertyValueFactory<>("email"));
        outgoingSubjectColumn.setCellValueFactory(new PropertyValueFactory<>("subject"));
        outgoingDateColumn.setCellValueFactory(new PropertyValueFactory<>("dateString"));
        outgoingStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        outgoingTable.setItems(outgoingInvites);

        refresh();
    }

    @FXML
    public void refresh() {
        loadPendingInvites();
        loadOutgoingInvites();
    }

    private void loadPendingInvites() {
        TaskRunner.run("Loading pending invites", () -> services.invites().listPendingInvites(),
            new TaskRunner.TaskCallback<>() {
                @Override
                public void onSuccess(List<InviteService.InviteSummary> result) {
                    pendingInvites.clear();
                    for (var i : result) {
                        pendingInvites.add(new InviteRow(i));
                    }
                }

                @Override
                public void onError(Throwable error) {
                    statusLabel.setText("Error loading pending: " + error.getMessage());
                }
            });
    }

    private void loadOutgoingInvites() {
        TaskRunner.run("Loading outgoing invites", () -> services.invites().listOutgoingInvites(),
            new TaskRunner.TaskCallback<>() {
                @Override
                public void onSuccess(List<InviteService.InviteSummary> result) {
                    outgoingInvites.clear();
                    for (var i : result) {
                        outgoingInvites.add(new InviteRow(i));
                    }
                    statusLabel.setText("Loaded " + pendingInvites.size() + " pending, " + result.size() + " outgoing");
                }

                @Override
                public void onError(Throwable error) {
                    statusLabel.setText("Error loading outgoing: " + error.getMessage());
                }
            });
    }

    @FXML
    public void acknowledgeSelected() {
        InviteRow selected = pendingTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            mainController.showError("No Selection", "Please select an invite to acknowledge");
            return;
        }

        String password = mainController.getPassword();
        if (password == null) return;

        mainController.showProgress(true);
        mainController.setStatus("Acknowledging invite...");

        TaskRunner.run("Acknowledging invite", () -> services.invites().acknowledgeInvite(password, selected.getInviteId()),
            new TaskRunner.TaskCallback<>() {
                @Override
                public void onSuccess(InviteService.AckInviteResult result) {
                    mainController.showProgress(false);
                    if (result.success()) {
                        mainController.setStatus("Invite acknowledged!");
                        mainController.showInfo("Success", result.message());
                        refresh();
                    } else {
                        mainController.setStatus("Failed to acknowledge");
                        mainController.showError("Acknowledgement Failed", result.message());
                    }
                }

                @Override
                public void onError(Throwable error) {
                    mainController.showProgress(false);
                    mainController.showError("Error", error.getMessage());
                }
            });
    }

    /**
     * Table row model for invites.
     */
    public static class InviteRow {
        private final StringProperty inviteId;
        private final StringProperty email;
        private final StringProperty subject;
        private final StringProperty dateString;
        private final StringProperty status;

        public InviteRow(InviteService.InviteSummary summary) {
            this.inviteId = new SimpleStringProperty(summary.inviteId());
            this.email = new SimpleStringProperty(summary.email());
            this.subject = new SimpleStringProperty(summary.subject() != null ? summary.subject() : "(no subject)");
            this.status = new SimpleStringProperty(summary.status());

            String dateStr = "";
            if (summary.createdEpochSec() > 0) {
                dateStr = Instant.ofEpochSecond(summary.createdEpochSec())
                    .atZone(ZoneId.systemDefault())
                    .format(DATE_FORMAT);
            }
            this.dateString = new SimpleStringProperty(dateStr);
        }

        public String getInviteId() { return inviteId.get(); }
        public StringProperty inviteIdProperty() { return inviteId; }
        public String getEmail() { return email.get(); }
        public StringProperty emailProperty() { return email; }
        public String getSubject() { return subject.get(); }
        public StringProperty subjectProperty() { return subject; }
        public String getDateString() { return dateString.get(); }
        public StringProperty dateStringProperty() { return dateString; }
        public String getStatus() { return status.get(); }
        public StringProperty statusProperty() { return status; }
    }
}
