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
import me.toymail.zkemails.service.ServiceContext;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Controller for the Contact Version History view.
 */
public class ContactsVersionsController {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ServiceContext services;
    private final MainController mainController;

    @FXML private TableView<VersionRow> versionsTable;
    @FXML private TableColumn<VersionRow, String> dateColumn;
    @FXML private TableColumn<VersionRow, String> countColumn;
    @FXML private TableColumn<VersionRow, String> sizeColumn;

    @FXML private TableView<DiffRow> diffTable;
    @FXML private TableColumn<DiffRow, String> changeTypeColumn;
    @FXML private TableColumn<DiffRow, String> emailColumn;
    @FXML private TableColumn<DiffRow, String> detailsColumn;

    @FXML private Label statusLabel;
    @FXML private Label compareLabel;

    private final ObservableList<VersionRow> versions = FXCollections.observableArrayList();
    private final ObservableList<DiffRow> diffs = FXCollections.observableArrayList();

    public ContactsVersionsController(ServiceContext services, MainController mainController) {
        this.services = services;
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Set up version table columns
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        countColumn.setCellValueFactory(new PropertyValueFactory<>("contactCount"));
        sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        versionsTable.setItems(versions);

        // Set up diff table columns
        changeTypeColumn.setCellValueFactory(new PropertyValueFactory<>("changeType"));
        emailColumn.setCellValueFactory(new PropertyValueFactory<>("email"));
        detailsColumn.setCellValueFactory(new PropertyValueFactory<>("details"));
        diffTable.setItems(diffs);

        // Color-code change type column
        changeTypeColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String changeType, boolean empty) {
                super.updateItem(changeType, empty);
                if (empty || changeType == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(changeType);
                    switch (changeType) {
                        case "ADDED" -> setStyle("-fx-text-fill: #27ae60; -fx-font-weight: bold;");
                        case "REMOVED" -> setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
                        case "MODIFIED" -> setStyle("-fx-text-fill: #f39c12; -fx-font-weight: bold;");
                        default -> setStyle("");
                    }
                }
            }
        });

        // Handle version selection
        versionsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                loadDiff(newVal.getFilename());
            }
        });

        // Load versions
        refresh();
    }

    @FXML
    public void refresh() {
        statusLabel.setText("Loading...");

        TaskRunner.run("Loading versions", () -> services.contacts().listVersions(),
                new TaskRunner.TaskCallback<List<ContactService.VersionInfo>>() {
                    @Override
                    public void onSuccess(List<ContactService.VersionInfo> result) {
                        versions.clear();
                        for (var v : result) {
                            versions.add(new VersionRow(v));
                        }
                        statusLabel.setText(result.size() + " versions found");
                    }

                    @Override
                    public void onError(Throwable error) {
                        statusLabel.setText("Error: " + error.getMessage());
                    }
                });
    }

    private void loadDiff(String filename) {
        compareLabel.setText(filename + " vs current");

        TaskRunner.run("Loading diff", () -> services.contacts().diffWithCurrent(filename),
                new TaskRunner.TaskCallback<List<ContactService.ContactDiff>>() {
                    @Override
                    public void onSuccess(List<ContactService.ContactDiff> result) {
                        diffs.clear();
                        for (var d : result) {
                            diffs.add(new DiffRow(d));
                        }
                        if (result.isEmpty()) {
                            statusLabel.setText("No differences found");
                        } else {
                            long added = result.stream().filter(d -> "added".equals(d.changeType())).count();
                            long removed = result.stream().filter(d -> "removed".equals(d.changeType())).count();
                            long modified = result.stream().filter(d -> "modified".equals(d.changeType())).count();
                            statusLabel.setText(String.format("%d added, %d removed, %d modified", added, removed, modified));
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        statusLabel.setText("Error loading diff: " + error.getMessage());
                    }
                });
    }

    @FXML
    public void backToContacts() {
        mainController.switchToContacts();
    }

    // Row model for versions table
    public static class VersionRow {
        private final StringProperty date;
        private final StringProperty contactCount;
        private final StringProperty size;
        private final String filename;

        public VersionRow(ContactService.VersionInfo info) {
            this.filename = info.filename();
            this.date = new SimpleStringProperty(DATE_FORMATTER.format(info.timestamp()));
            this.contactCount = new SimpleStringProperty(String.valueOf(info.contactCount()));
            this.size = new SimpleStringProperty(formatSize(info.sizeBytes()));
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            return (bytes / 1024) + " KB";
        }

        public String getDate() { return date.get(); }
        public StringProperty dateProperty() { return date; }

        public String getContactCount() { return contactCount.get(); }
        public StringProperty contactCountProperty() { return contactCount; }

        public String getSize() { return size.get(); }
        public StringProperty sizeProperty() { return size; }

        public String getFilename() { return filename; }
    }

    // Row model for diff table
    public static class DiffRow {
        private final StringProperty changeType;
        private final StringProperty email;
        private final StringProperty details;

        public DiffRow(ContactService.ContactDiff diff) {
            this.changeType = new SimpleStringProperty(diff.changeType().toUpperCase());
            this.email = new SimpleStringProperty(diff.email());
            this.details = new SimpleStringProperty(buildDetails(diff));
        }

        private String buildDetails(ContactService.ContactDiff diff) {
            return switch (diff.changeType()) {
                case "added" -> "Status: " + diff.newValue().status() +
                        (diff.newValue().hasKeys() ? ", has keys" : "");
                case "removed" -> "Was: " + diff.oldValue().status();
                case "modified" -> {
                    StringBuilder sb = new StringBuilder();
                    if (diff.changedFields().contains("fingerprintHex")) {
                        sb.append("KEYS CHANGED! ");
                    }
                    if (diff.changedFields().contains("status")) {
                        sb.append("Status: ").append(diff.oldValue().status())
                                .append(" -> ").append(diff.newValue().status());
                    } else {
                        sb.append("Fields: ").append(String.join(", ", diff.changedFields()));
                    }
                    yield sb.toString();
                }
                default -> "";
            };
        }

        public String getChangeType() { return changeType.get(); }
        public StringProperty changeTypeProperty() { return changeType; }

        public String getEmail() { return email.get(); }
        public StringProperty emailProperty() { return email; }

        public String getDetails() { return details.get(); }
        public StringProperty detailsProperty() { return details; }
    }
}
