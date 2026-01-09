package me.toymail.zkemails.gui.controller;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import me.toymail.zkemails.service.ServiceContext;
import me.toymail.zkemails.store.SentStore;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Controller for the Sent messages view.
 * Displays sent encrypted messages with reading pane.
 */
public class SentController {
    private final ServiceContext services;
    private final MainController mainController;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM dd HH:mm");
    private static final SimpleDateFormat FULL_DATE_FORMAT = new SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a");

    // Left pane - Message List
    @FXML private SplitPane splitPane;
    @FXML private TableView<SentMessageRow> messageTable;
    @FXML private TableColumn<SentMessageRow, String> toColumn;
    @FXML private TableColumn<SentMessageRow, String> subjectColumn;
    @FXML private TableColumn<SentMessageRow, String> dateColumn;
    @FXML private Button refreshButton;
    @FXML private Label emptyLabel;
    @FXML private Label messageCountLabel;

    // Right pane - Reading Pane
    @FXML private VBox readingPane;
    @FXML private VBox messageHeader;
    @FXML private Label messageSubject;
    @FXML private Label messageTo;
    @FXML private Label messageDate;
    @FXML private ScrollPane messageScrollPane;
    @FXML private VBox messageContent;
    @FXML private VBox noSelectionPane;
    @FXML private Button closeReadingPaneButton;

    // Loading overlay
    @FXML private VBox loadingOverlay;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label loadingLabel;

    private final ObservableList<SentMessageRow> messages = FXCollections.observableArrayList();

    public SentController(ServiceContext services, MainController mainController) {
        this.services = services;
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Set up table columns
        toColumn.setCellValueFactory(new PropertyValueFactory<>("to"));
        subjectColumn.setCellValueFactory(new PropertyValueFactory<>("subject"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("dateString"));

        messageTable.setItems(messages);

        // Single-click selection to show message in reading pane
        messageTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            if (newSel != null) {
                onMessageSelected(newSel);
            }
        });

        // Initial state - hide reading pane, show only message list
        readingPane.setVisible(false);
        readingPane.setManaged(false);
        splitPane.setDividerPositions(1.0);

        // Load sent messages
        refresh();
    }

    @FXML
    public void refresh() {
        showLoading("Loading sent messages...");

        CompletableFuture.supplyAsync(() -> {
            try {
                return services.storeContext().sentStore().list(50);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).thenAccept((List<SentStore.SentMessage> result) -> Platform.runLater(() -> {
            messages.clear();
            for (var m : result) {
                messages.add(new SentMessageRow(m));
            }
            hideLoading();
            updateMessageCount(result.size());
            emptyLabel.setVisible(result.isEmpty());
            messageTable.setVisible(!result.isEmpty());
            mainController.setStatus("Loaded " + result.size() + " sent messages");
        })).exceptionally(error -> {
            Platform.runLater(() -> {
                hideLoading();
                String errorMsg = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
                mainController.setStatus("Error: " + errorMsg);
                mainController.showError("Failed to load sent messages", errorMsg);
            });
            return null;
        });
    }

    private void updateMessageCount(int count) {
        if (count == 0) {
            messageCountLabel.setText("");
        } else if (count == 1) {
            messageCountLabel.setText("1 sent message");
        } else {
            messageCountLabel.setText(count + " sent messages");
        }
    }

    /**
     * Called when a message is selected in the table.
     */
    private void onMessageSelected(SentMessageRow row) {
        // Show the reading pane if hidden
        if (!readingPane.isVisible()) {
            readingPane.setVisible(true);
            readingPane.setManaged(true);
            splitPane.setDividerPositions(0.35);
        }

        displaySentMessage(row);
    }

    /**
     * Display sent message content in the reading pane.
     */
    private void displaySentMessage(SentMessageRow row) {
        // Show header and content, hide no-selection
        noSelectionPane.setVisible(false);
        noSelectionPane.setManaged(false);
        messageHeader.setVisible(true);
        messageHeader.setManaged(true);
        messageScrollPane.setVisible(true);
        messageScrollPane.setManaged(true);

        // Set header info
        messageSubject.setText(row.getSubject());
        messageTo.setText("To: " + row.getTo());
        messageDate.setText(row.getFullDate());

        // Build message view with actual plaintext content
        messageContent.getChildren().clear();
        messageContent.getStyleClass().add("chat-container");

        // Create sent message bubble with actual content
        VBox bubble = new VBox(2);
        bubble.getStyleClass().addAll("chat-bubble", "chat-bubble-sent");

        // Show actual plaintext content
        String content = row.getPlaintext();
        Label body = new Label(content != null ? content : "(No content)");
        body.setWrapText(true);
        body.setMaxWidth(400);
        body.getStyleClass().add("chat-bubble-body");
        bubble.getChildren().add(body);

        Label time = new Label(row.getDateString());
        time.getStyleClass().add("chat-bubble-time");
        bubble.getChildren().add(time);

        HBox chatRow = new HBox();
        chatRow.getStyleClass().add("chat-row-sent");
        chatRow.getChildren().add(bubble);

        messageContent.getChildren().add(chatRow);
    }

    /**
     * Close the reading pane (called from X button).
     */
    @FXML
    public void onCloseReadingPane() {
        // Hide the reading pane completely
        splitPane.setDividerPositions(1.0);
        readingPane.setVisible(false);
        readingPane.setManaged(false);
        messageTable.getSelectionModel().clearSelection();
    }

    private void showLoading(String message) {
        loadingLabel.setText(message);
        loadingOverlay.setVisible(true);
    }

    private void hideLoading() {
        loadingOverlay.setVisible(false);
    }

    /**
     * Table row model for sent messages.
     */
    public static class SentMessageRow {
        private final String id;
        private final StringProperty to;
        private final StringProperty subject;
        private final StringProperty dateString;
        private final String fullDate;
        private final String plaintext;
        private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM dd HH:mm");
        private static final SimpleDateFormat FULL_DATE_FORMAT = new SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a");

        public SentMessageRow(SentStore.SentMessage sent) {
            this.id = sent.id;
            this.to = new SimpleStringProperty(sent.toEmail != null ? sent.toEmail : "(unknown)");
            this.subject = new SimpleStringProperty(sent.subject != null ? sent.subject : "(no subject)");
            this.plaintext = sent.plaintext;

            Date sentDate = Date.from(Instant.ofEpochSecond(sent.sentAtEpochSec));
            this.dateString = new SimpleStringProperty(DATE_FORMAT.format(sentDate));
            this.fullDate = FULL_DATE_FORMAT.format(sentDate);
        }

        public String getId() { return id; }
        public String getTo() { return to.get(); }
        public StringProperty toProperty() { return to; }
        public String getSubject() { return subject.get(); }
        public StringProperty subjectProperty() { return subject; }
        public String getDateString() { return dateString.get(); }
        public StringProperty dateStringProperty() { return dateString; }
        public String getFullDate() { return fullDate; }
        public String getPlaintext() { return plaintext; }
    }
}
