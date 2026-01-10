package me.toymail.zkemails.gui.controller;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import me.toymail.zkemails.service.ServiceContext;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.SentStore;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Controller for the Sent messages view.
 * Displays sent threads grouped by threadId with reading pane showing all messages.
 */
public class SentController {
    private final ServiceContext services;
    private final MainController mainController;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM dd HH:mm");
    private static final SimpleDateFormat FULL_DATE_FORMAT = new SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a");

    // Left pane - Thread List
    @FXML private SplitPane splitPane;
    @FXML private TableView<SentThreadRow> messageTable;
    @FXML private TableColumn<SentThreadRow, String> toColumn;
    @FXML private TableColumn<SentThreadRow, String> subjectColumn;
    @FXML private TableColumn<SentThreadRow, String> dateColumn;
    @FXML private Button refreshButton;
    @FXML private Label emptyLabel;
    @FXML private Label messageCountLabel;

    // Right pane - Reading Pane
    @FXML private VBox readingPane;
    @FXML private VBox messageHeader;
    @FXML private Label messageSubject;
    @FXML private Label messageTo;
    @FXML private Label messageCc;
    @FXML private Label messageBcc;
    @FXML private Label messageDate;
    @FXML private ScrollPane messageScrollPane;
    @FXML private VBox messageContent;
    @FXML private VBox noSelectionPane;
    @FXML private Button closeReadingPaneButton;

    // Loading overlay
    @FXML private VBox loadingOverlay;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label loadingLabel;

    private final ObservableList<SentThreadRow> threads = FXCollections.observableArrayList();
    private String currentUserEmail;

    public SentController(ServiceContext services, MainController mainController) {
        this.services = services;
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Get current user email
        try {
            Config cfg = services.storeContext().zkStore().readJson("config.json", Config.class);
            if (cfg != null) {
                currentUserEmail = cfg.email;
            }
        } catch (Exception ignored) {}

        // Set up table columns
        setupTableColumns();

        messageTable.setItems(threads);

        // Single-click selection to show thread in reading pane
        messageTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            if (newSel != null) {
                onThreadSelected(newSel);
            }
        });

        // Initial state - hide reading pane, show only thread list
        readingPane.setVisible(false);
        readingPane.setManaged(false);
        splitPane.setDividerPositions(1.0);

        // Load sent threads
        refresh();
    }

    /**
     * Set up table columns with thread count badge for subject.
     */
    private void setupTableColumns() {
        // To column
        toColumn.setCellValueFactory(cellData -> cellData.getValue().toProperty());

        // Subject column with thread count badge
        subjectColumn.setCellValueFactory(cellData -> cellData.getValue().subjectProperty());
        subjectColumn.setCellFactory(col -> new TableCell<SentThreadRow, String>() {
            @Override
            protected void updateItem(String subject, boolean empty) {
                super.updateItem(subject, empty);
                if (empty || subject == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    SentThreadRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    int threadCount = row != null ? row.getMessageCount() : 1;

                    HBox container = new HBox(6);
                    container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

                    Label subjectLabel = new Label(subject);
                    container.getChildren().add(subjectLabel);

                    // Add thread count badge if more than 1 message
                    if (threadCount > 1) {
                        Label badge = new Label(String.valueOf(threadCount));
                        badge.getStyleClass().add("thread-count-badge");
                        container.getChildren().add(badge);
                    }

                    setGraphic(container);
                    setText(null);
                }
            }
        });

        // Date column
        dateColumn.setCellValueFactory(cellData -> cellData.getValue().dateStringProperty());
    }

    @FXML
    public void refresh() {
        // Check if profile is active and sent store exists
        if (!services.storeContext().hasActiveProfile() || services.storeContext().sentStore() == null) {
            threads.clear();
            updateMessageCount(0);
            emptyLabel.setVisible(true);
            messageTable.setVisible(false);
            mainController.setStatus("No profile selected");
            return;
        }

        showLoading("Loading sent threads...");

        CompletableFuture.supplyAsync(() -> {
            try {
                return services.storeContext().sentStore().listThreads(50);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).thenAccept((List<SentStore.ThreadSummary> result) -> Platform.runLater(() -> {
            threads.clear();
            for (var t : result) {
                threads.add(new SentThreadRow(t));
            }
            hideLoading();
            updateMessageCount(result.size());
            emptyLabel.setVisible(result.isEmpty());
            messageTable.setVisible(!result.isEmpty());
            mainController.setStatus("Loaded " + result.size() + " sent threads");
        })).exceptionally(error -> {
            Platform.runLater(() -> {
                hideLoading();
                threads.clear();
                updateMessageCount(0);
                emptyLabel.setVisible(true);
                messageTable.setVisible(false);
                mainController.setStatus("No sent messages found");
            });
            return null;
        });
    }

    private void updateMessageCount(int count) {
        if (count == 0) {
            messageCountLabel.setText("");
        } else if (count == 1) {
            messageCountLabel.setText("1 thread");
        } else {
            messageCountLabel.setText(count + " threads");
        }
    }

    /**
     * Called when a thread is selected in the table.
     */
    private void onThreadSelected(SentThreadRow row) {
        // Show the reading pane if hidden
        if (!readingPane.isVisible()) {
            readingPane.setVisible(true);
            readingPane.setManaged(true);
            splitPane.setDividerPositions(0.35);
        }

        loadThreadContent(row.getThreadId(), row.getSubject(), row.getTo());
    }

    /**
     * Load and display all messages in a thread.
     */
    private void loadThreadContent(String threadId, String subject, String lastTo) {
        showLoading("Loading thread...");

        CompletableFuture.supplyAsync(() -> {
            try {
                return services.storeContext().sentStore().loadThreadMessages(threadId);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).thenAccept((List<SentStore.SentMessage> messages) -> Platform.runLater(() -> {
            hideLoading();
            displayThread(messages, subject, lastTo);
        })).exceptionally(error -> {
            Platform.runLater(() -> {
                hideLoading();
                mainController.showError("Failed to load thread", error.getMessage());
            });
            return null;
        });
    }

    /**
     * Display thread messages in the reading pane as chat bubbles.
     */
    private void displayThread(List<SentStore.SentMessage> messages, String subject, String lastTo) {
        if (messages.isEmpty()) {
            showNoSelection();
            return;
        }

        // Show header and content, hide no-selection
        noSelectionPane.setVisible(false);
        noSelectionPane.setManaged(false);
        messageHeader.setVisible(true);
        messageHeader.setManaged(true);
        messageScrollPane.setVisible(true);
        messageScrollPane.setManaged(true);

        // Set header info
        messageSubject.setText(subject != null ? subject : "(no subject)");

        // Get latest message for recipient info
        SentStore.SentMessage latestMsg = messages.get(messages.size() - 1);

        // Display To recipients
        String toDisplay = latestMsg.getRecipientsDisplay();
        if (toDisplay != null && !toDisplay.isEmpty()) {
            messageTo.setText("To: " + toDisplay);
        } else if (lastTo != null) {
            messageTo.setText("To: " + lastTo);
        } else {
            messageTo.setText("To: (unknown)");
        }

        // Display CC recipients if available
        if (latestMsg.ccEmails != null && !latestMsg.ccEmails.isEmpty()) {
            messageCc.setText("Cc: " + String.join(", ", latestMsg.ccEmails));
            messageCc.setVisible(true);
            messageCc.setManaged(true);
        } else {
            messageCc.setVisible(false);
            messageCc.setManaged(false);
        }

        // Display BCC recipients if available
        if (latestMsg.bccEmails != null && !latestMsg.bccEmails.isEmpty()) {
            messageBcc.setText("Bcc: " + String.join(", ", latestMsg.bccEmails));
            messageBcc.setVisible(true);
            messageBcc.setManaged(true);
        } else {
            messageBcc.setVisible(false);
            messageBcc.setManaged(false);
        }

        // Display date
        Date latestDate = Date.from(Instant.ofEpochSecond(latestMsg.sentAtEpochSec));
        messageDate.setText(FULL_DATE_FORMAT.format(latestDate));

        // Build chat view
        messageContent.getChildren().clear();
        messageContent.getStyleClass().add("chat-container");

        String lastDateStr = null;
        SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE, MMMM d");

        for (var msg : messages) {
            Date sentDate = Date.from(Instant.ofEpochSecond(msg.sentAtEpochSec));

            // Add date separator if day changed
            String dateStr = dayFormat.format(sentDate);
            if (!dateStr.equals(lastDateStr)) {
                HBox dateSeparator = new HBox();
                dateSeparator.getStyleClass().add("chat-date-separator");
                Label dateLabel = new Label(dateStr);
                dateLabel.getStyleClass().add("chat-date-label");
                dateSeparator.getChildren().add(dateLabel);
                messageContent.getChildren().add(dateSeparator);
                lastDateStr = dateStr;
            }

            // Create sent message bubble
            HBox chatBubble = createSentMessageBubble(msg, sentDate);
            messageContent.getChildren().add(chatBubble);
        }

        // Scroll to bottom (latest message)
        Platform.runLater(() -> messageScrollPane.setVvalue(1.0));
    }

    /**
     * Create a chat bubble for a sent message.
     */
    private HBox createSentMessageBubble(SentStore.SentMessage msg, Date sentDate) {
        // Sent messages are always on the right (blue)
        VBox bubble = new VBox(2);
        bubble.getStyleClass().addAll("chat-bubble", "chat-bubble-sent");

        // Recipient info - show all recipients for multi-recipient messages
        String recipientDisplay = msg.getRecipientsDisplay();
        if (recipientDisplay != null && !recipientDisplay.isEmpty()) {
            Label recipient = new Label("To: " + recipientDisplay);
            recipient.getStyleClass().add("chat-bubble-header");
            bubble.getChildren().add(recipient);
        } else if (msg.toEmail != null) {
            Label recipient = new Label("To: " + msg.toEmail);
            recipient.getStyleClass().add("chat-bubble-header");
            bubble.getChildren().add(recipient);
        }

        // Message body
        String messageText = msg.plaintext != null ? msg.plaintext : "(No content)";
        Label body = new Label(messageText);
        body.setWrapText(true);
        body.getStyleClass().add("chat-bubble-body");
        bubble.getChildren().add(body);

        // Attachments
        if (msg.attachments != null && !msg.attachments.isEmpty()) {
            VBox attachmentBox = new VBox(4);
            attachmentBox.getStyleClass().add("chat-attachments");
            for (var att : msg.attachments) {
                java.nio.file.Path fullPath = services.storeContext().sentStore()
                        .getAttachmentFullPath(msg.threadId, msg.id, att.localPath);
                boolean exists = java.nio.file.Files.exists(fullPath);

                Hyperlink link = new Hyperlink(getAttachmentIcon(att.contentType) + " " + att.filename);
                link.getStyleClass().add("attachment-link");
                if (exists) {
                    link.setOnAction(e -> openFile(fullPath));
                } else {
                    link.setDisable(true);
                    link.setText(link.getText() + " (unavailable)");
                }
                attachmentBox.getChildren().add(link);
            }
            bubble.getChildren().add(attachmentBox);
        }

        // Timestamp
        Label time = new Label(DATE_FORMAT.format(sentDate));
        time.getStyleClass().add("chat-bubble-time");
        bubble.getChildren().add(time);

        // Wrap in HBox for alignment (right for sent)
        HBox row = new HBox();
        row.setFillHeight(false);
        row.getStyleClass().add("chat-row-sent");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(spacer, bubble);

        return row;
    }

    /**
     * Get icon for attachment based on content type.
     */
    private String getAttachmentIcon(String contentType) {
        if (contentType == null) return "\uD83D\uDCCE"; // 📎
        if (contentType.startsWith("image/")) return "\uD83D\uDDBC"; // 🖼
        if (contentType.startsWith("video/")) return "\uD83C\uDFA5"; // 🎥
        if (contentType.startsWith("audio/")) return "\uD83C\uDFB5"; // 🎵
        if (contentType.contains("pdf")) return "\uD83D\uDCC4"; // 📄
        if (contentType.contains("zip") || contentType.contains("archive")) return "\uD83D\uDCE6"; // 📦
        return "\uD83D\uDCCE"; // 📎
    }

    /**
     * Open a file with the system's default application.
     */
    private void openFile(java.nio.file.Path path) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(path.toFile());
            } else {
                // Fallback for systems without Desktop support
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("mac")) {
                    Runtime.getRuntime().exec(new String[]{"open", path.toString()});
                } else if (os.contains("linux")) {
                    Runtime.getRuntime().exec(new String[]{"xdg-open", path.toString()});
                } else if (os.contains("win")) {
                    Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "", path.toString()});
                }
            }
        } catch (Exception e) {
            mainController.showError("Cannot open file", e.getMessage());
        }
    }

    /**
     * Show the no-selection placeholder.
     */
    private void showNoSelection() {
        noSelectionPane.setVisible(true);
        noSelectionPane.setManaged(true);
        messageHeader.setVisible(false);
        messageHeader.setManaged(false);
        messageScrollPane.setVisible(false);
        messageScrollPane.setManaged(false);
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
     * Table row model for sent threads.
     */
    public static class SentThreadRow {
        private final String threadId;
        private final StringProperty to;
        private final StringProperty subject;
        private final StringProperty dateString;
        private final int messageCount;
        private static final DateTimeFormatter EPOCH_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM dd HH:mm").withZone(ZoneId.systemDefault());

        public SentThreadRow(SentStore.ThreadSummary summary) {
            this.threadId = summary.threadId;
            this.to = new SimpleStringProperty(summary.lastTo != null ? summary.lastTo : "(unknown)");
            this.subject = new SimpleStringProperty(summary.subject != null ? summary.subject : "(no subject)");
            this.dateString = new SimpleStringProperty(
                summary.lastSentEpochSec > 0
                    ? EPOCH_DATE_FORMAT.format(Instant.ofEpochSecond(summary.lastSentEpochSec))
                    : "");
            this.messageCount = summary.messageCount;
        }

        public String getThreadId() { return threadId; }
        public String getTo() { return to.get(); }
        public StringProperty toProperty() { return to; }
        public String getSubject() { return subject.get(); }
        public StringProperty subjectProperty() { return subject; }
        public String getDateString() { return dateString.get(); }
        public StringProperty dateStringProperty() { return dateString; }
        public int getMessageCount() { return messageCount; }
    }
}
