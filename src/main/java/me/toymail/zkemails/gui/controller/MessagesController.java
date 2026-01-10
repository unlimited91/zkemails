package me.toymail.zkemails.gui.controller;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import me.toymail.zkemails.gui.cache.MessageCacheService;
import me.toymail.zkemails.service.MessageService;
import me.toymail.zkemails.service.ServiceContext;
import me.toymail.zkemails.store.InboxStore;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Controller for the Outlook-style messages view.
 * Features split-pane layout with message list and reading pane.
 */
public class MessagesController {
    private final ServiceContext services;
    private final MainController mainController;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM dd HH:mm");
    private static final SimpleDateFormat FULL_DATE_FORMAT = new SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a");

    // Left pane - Message List
    @FXML private SplitPane splitPane;
    @FXML private TableView<MessageRow> messageTable;
    @FXML private TableColumn<MessageRow, String> fromColumn;
    @FXML private TableColumn<MessageRow, String> subjectColumn;
    @FXML private TableColumn<MessageRow, String> dateColumn;
    @FXML private Button refreshButton;
    @FXML private Label emptyLabel;
    @FXML private Label messageCountLabel;
    @FXML private Button closeReadingPaneButton;

    // Right pane - Reading Pane
    @FXML private VBox readingPane;
    @FXML private VBox messageHeader;
    @FXML private Label messageSubject;
    @FXML private Label messageFrom;
    @FXML private Label messageDate;
    @FXML private ScrollPane messageScrollPane;
    @FXML private VBox messageContent;
    @FXML private VBox noSelectionPane;

    // Reply section
    @FXML private VBox replySection;
    @FXML private TextArea replyTextArea;
    @FXML private HBox attachmentBar;
    @FXML private Label attachmentListLabel;

    // Reply attachments
    private final java.util.List<java.nio.file.Path> replyAttachments = new java.util.ArrayList<>();
    private static final long MAX_ATTACHMENT_SIZE = 25 * 1024 * 1024; // 25 MB

    // Loading overlay
    @FXML private VBox loadingOverlay;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label loadingLabel;

    private final ObservableList<MessageRow> messages = FXCollections.observableArrayList();
    private Long selectedMessageUid;
    private String selectedThreadId;
    private MessageService.ReplyContext currentReplyContext;
    private String currentUserEmail;

    private MessageCacheService cacheService;
    private Consumer<MessageCacheService.CacheUpdateEvent> cacheUpdateListener;

    public MessagesController(ServiceContext services, MainController mainController) {
        this.services = services;
        this.mainController = mainController;
    }

    @FXML
    public void initialize() {
        // Get cache service
        cacheService = mainController.getCacheService();

        // Get current user email for sent message detection
        try {
            currentUserEmail = services.messages().getCurrentUserEmail();
        } catch (Exception e) {
            currentUserEmail = null;
        }

        // Set up table columns with custom cell factories for read/unread styling
        setupTableColumns();

        messageTable.setItems(messages);

        // Single-click selection to show message in reading pane
        messageTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            if (newSel != null) {
                onMessageSelected(newSel);
            }
        });

        // Listen to cache updates
        if (cacheService != null) {
            cacheUpdateListener = this::onCacheUpdate;
            cacheService.addUpdateListener(cacheUpdateListener);
        }

        // Initial state - hide reading pane, show only message list
        readingPane.setVisible(false);
        readingPane.setManaged(false);
        splitPane.setDividerPositions(1.0);

        // Load messages from cache or trigger refresh
        loadFromCacheOrRefresh();
    }

    /**
     * Handle cache update events.
     */
    private void onCacheUpdate(MessageCacheService.CacheUpdateEvent event) {
        Platform.runLater(() -> {
            switch (event.type()) {
                case LOADING_STARTED:
                    // Only show loading modal for user-initiated refreshes (not background sync)
                    if (!event.isBackground()) {
                        showLoading("Loading messages...");
                    }
                    break;
                case LOADING_FINISHED:
                    hideLoading();  // Always hide (safe even if not shown)
                    break;
                case MESSAGES_UPDATED:
                    loadFromCache();
                    break;
                case ERROR:
                    hideLoading();
                    break;
            }
        });
    }

    /**
     * Set up table columns with custom cell factories for read/unread styling and thread count.
     */
    private void setupTableColumns() {
        // From column with read/unread styling (bold for unread)
        fromColumn.setCellValueFactory(cellData -> cellData.getValue().fromProperty());
        fromColumn.setCellFactory(col -> new TableCell<MessageRow, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    MessageRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    boolean isUnread = row != null && !row.isRead();

                    // Create HBox with unread dot and text
                    HBox container = new HBox(6);
                    container.setAlignment(Pos.CENTER_LEFT);

                    if (isUnread) {
                        Circle dot = new Circle(4);
                        dot.setFill(Color.web("#0078d4"));
                        container.getChildren().add(dot);
                    }

                    Label text = new Label(item);
                    if (isUnread) {
                        text.setStyle("-fx-font-weight: bold;");
                    }
                    container.getChildren().add(text);

                    setGraphic(container);
                    setText(null);
                }
            }
        });

        // Subject column with thread count badge
        subjectColumn.setCellValueFactory(cellData -> cellData.getValue().subjectProperty());
        subjectColumn.setCellFactory(col -> new TableCell<MessageRow, String>() {
            @Override
            protected void updateItem(String subject, boolean empty) {
                super.updateItem(subject, empty);
                if (empty || subject == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    MessageRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    boolean isUnread = row != null && !row.isRead();
                    int threadCount = row != null ? row.getMessageCount() : 1;

                    HBox container = new HBox(6);
                    container.setAlignment(Pos.CENTER_LEFT);

                    Label subjectLabel = new Label(subject);
                    if (isUnread) {
                        subjectLabel.setStyle("-fx-font-weight: bold;");
                    }
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

        // Date column with read/unread styling
        dateColumn.setCellValueFactory(cellData -> cellData.getValue().dateStringProperty());
        dateColumn.setCellFactory(col -> new TableCell<MessageRow, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    MessageRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null && !row.isRead()) {
                        setStyle("-fx-font-weight: bold;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });
    }

    /**
     * Load messages from cache if available, otherwise trigger refresh.
     */
    private void loadFromCacheOrRefresh() {
        // Always load from local storage first (instant)
        loadFromCache();
        // Then trigger background refresh
        if (cacheService != null) {
            cacheService.refreshAsync();
        }
    }

    /**
     * Load messages from local storage (instant, no network call).
     */
    private void loadFromCache() {
        if (cacheService == null) {
            return;
        }

        // Use thread summaries from local storage
        List<InboxStore.ThreadSummary> threads = cacheService.getThreadSummaries();
        messages.clear();
        for (var t : threads) {
            messages.add(new MessageRow(t));
        }

        updateMessageCount(threads.size());
        emptyLabel.setVisible(threads.isEmpty());
        messageTable.setVisible(!threads.isEmpty());
    }

    private void updateMessageCount(int count) {
        if (count == 0) {
            messageCountLabel.setText("");
        } else if (count == 1) {
            messageCountLabel.setText("1 message");
        } else {
            messageCountLabel.setText(count + " messages");
        }
    }

    @FXML
    public void refresh() {
        if (cacheService != null) {
            cacheService.refreshAsync();
        } else {
            refreshDirect();
        }
    }

    /**
     * Direct refresh without cache (fallback).
     */
    private void refreshDirect() {
        String password = mainController.getPassword();
        if (password == null) {
            mainController.setStatus("Password required");
            return;
        }

        showLoading("Loading messages...");

        CompletableFuture.supplyAsync(() -> {
            try {
                return services.messages().listEncryptedMessages(password, 50);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }).thenAccept(result -> Platform.runLater(() -> {
            messages.clear();
            for (var m : result) {
                messages.add(new MessageRow(m));
            }
            hideLoading();
            updateMessageCount(result.size());
            emptyLabel.setVisible(result.isEmpty());
            messageTable.setVisible(!result.isEmpty());
        })).exceptionally(error -> {
            Platform.runLater(() -> {
                hideLoading();
                mainController.showError("Failed to load messages", error.getMessage());
            });
            return null;
        });
    }

    /**
     * Called when a message is selected in the table.
     */
    private void onMessageSelected(MessageRow row) {
        selectedMessageUid = row.getUid();
        selectedThreadId = row.getThreadId();
        clearReplyTextArea();

        // Show the reading pane if hidden
        if (!readingPane.isVisible()) {
            readingPane.setVisible(true);
            readingPane.setManaged(true);
            splitPane.setDividerPositions(0.35);
        }

        // Load thread content
        if (selectedThreadId != null) {
            loadThreadContent(selectedThreadId);
        } else {
            loadMessageContent(row.getUid());
        }

        // Mark thread as read
        if (selectedThreadId != null) {
            services.messages().markThreadRead(selectedThreadId);
            row.readProperty().set(true);
            // Refresh the table to update styling
            messageTable.refresh();
        }
    }

    /**
     * Load thread content by thread ID from local storage.
     */
    private void loadThreadContent(String threadId) {
        // Get thread from local storage (instant)
        var thread = cacheService.getThreadByThreadId(threadId);
        if (thread != null) {
            displayThread(thread);
            // Find last inbox message (with valid UID > 0) for reply context
            long lastValidUid = -1;
            for (var msg : thread.messages()) {
                if (msg.uid() > 0) {
                    lastValidUid = msg.uid();
                }
            }
            if (lastValidUid > 0) {
                selectedMessageUid = lastValidUid;
                prepareReplyContext(lastValidUid);
            }
        } else {
            showNoSelection();
        }
    }

    /**
     * Load and display message content in the reading pane.
     */
    private void loadMessageContent(long messageUid) {
        showLoading("Loading message...");

        if (cacheService != null) {
            // Try to get thread from cache for conversation view
            cacheService.getThread(messageUid)
                .thenAccept(thread -> Platform.runLater(() -> {
                    displayThread(thread);
                    hideLoading();
                    prepareReplyContext(messageUid);
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> {
                        hideLoading();
                        mainController.showError("Failed to load message", error.getCause().getMessage());
                    });
                    return null;
                });
        } else {
            // Fallback to direct call
            String password = mainController.getPassword();
            if (password == null) return;

            CompletableFuture.supplyAsync(() -> {
                try {
                    return services.messages().getThread(password, messageUid, 50);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).thenAccept(thread -> Platform.runLater(() -> {
                displayThread(thread);
                hideLoading();
                prepareReplyContext(messageUid);
            })).exceptionally(error -> {
                Platform.runLater(() -> {
                    hideLoading();
                    mainController.showError("Failed to load message", error.getCause().getMessage());
                });
                return null;
            });
        }
    }

    /**
     * Display thread messages in the reading pane as modern chat bubbles.
     */
    private void displayThread(MessageService.ThreadMessages thread) {
        // Show header and content, hide no-selection
        noSelectionPane.setVisible(false);
        noSelectionPane.setManaged(false);
        messageHeader.setVisible(true);
        messageHeader.setManaged(true);
        messageScrollPane.setVisible(true);
        messageScrollPane.setManaged(true);
        replySection.setVisible(true);
        replySection.setManaged(true);

        // Set header info from the latest message
        messageSubject.setText(thread.baseSubject());
        if (!thread.messages().isEmpty()) {
            var latestMsg = thread.messages().get(thread.messages().size() - 1);
            messageFrom.setText(latestMsg.from());
            if (latestMsg.received() != null) {
                messageDate.setText(FULL_DATE_FORMAT.format(latestMsg.received()));
            } else {
                messageDate.setText("");
            }
        }

        // Build modern chat view
        messageContent.getChildren().clear();
        messageContent.getStyleClass().add("chat-container");

        String lastDateStr = null;
        SimpleDateFormat dayFormat = new SimpleDateFormat("EEEE, MMMM d");

        for (var msg : thread.messages()) {
            // Add date separator if day changed
            if (msg.received() != null) {
                String dateStr = dayFormat.format(msg.received());
                if (!dateStr.equals(lastDateStr)) {
                    HBox dateSeparator = new HBox();
                    dateSeparator.getStyleClass().add("chat-date-separator");
                    Label dateLabel = new Label(dateStr);
                    dateLabel.getStyleClass().add("chat-date-label");
                    dateSeparator.getChildren().add(dateLabel);
                    messageContent.getChildren().add(dateSeparator);
                    lastDateStr = dateStr;
                }
            }

            HBox chatBubble = createMessageBubble(msg);
            messageContent.getChildren().add(chatBubble);
        }

        // Scroll to bottom (latest message)
        Platform.runLater(() -> messageScrollPane.setVvalue(1.0));
    }

    /**
     * Create a modern chat bubble for the thread view.
     */
    private HBox createMessageBubble(MessageService.DecryptedMessage msg) {
        // Check if this is a sent message
        boolean isSent = currentUserEmail != null && msg.from() != null &&
                         msg.from().toLowerCase().contains(currentUserEmail.toLowerCase());

        // Create the bubble content
        VBox bubble = new VBox(2);
        bubble.getStyleClass().addAll("chat-bubble", isSent ? "chat-bubble-sent" : "chat-bubble-received");

        // Sender name (only for received messages)
        if (!isSent) {
            Label sender = new Label(extractDisplayName(msg.from()));
            sender.getStyleClass().add("chat-bubble-header");
            bubble.getChildren().add(sender);
        }

        // Message body
        String messageText = msg.decryptionSuccessful() ? msg.plaintext() : "(Could not decrypt)";
        Label body = new Label(messageText);
        body.setWrapText(true);
        body.getStyleClass().add("chat-bubble-body");
        bubble.getChildren().add(body);

        // Attachments (if any)
        if (msg.attachments() != null && !msg.attachments().isEmpty()) {
            VBox attachmentBox = new VBox(4);
            attachmentBox.getStyleClass().add("chat-attachments");
            for (var att : msg.attachments()) {
                Hyperlink link = new Hyperlink(getAttachmentIcon(att.contentType()) + " " + att.filename());
                link.getStyleClass().add("attachment-link");
                if (att.availableLocally() && att.localPath() != null) {
                    link.setOnAction(e -> openFile(att.localPath()));
                } else {
                    link.setDisable(true);
                    link.setText(link.getText() + " (unavailable)");
                }
                attachmentBox.getChildren().add(link);
            }
            bubble.getChildren().add(attachmentBox);
        }

        // Timestamp
        Label time = new Label(msg.received() != null ? DATE_FORMAT.format(msg.received()) : "");
        time.getStyleClass().add("chat-bubble-time");
        bubble.getChildren().add(time);

        // Wrap in HBox for alignment (left for received, right for sent)
        HBox row = new HBox();
        row.setFillHeight(false);
        row.getStyleClass().add(isSent ? "chat-row-sent" : "chat-row-received");

        // Add spacer for alignment
        if (isSent) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.getChildren().addAll(spacer, bubble);
        } else {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.getChildren().addAll(bubble, spacer);
        }

        return row;
    }

    /**
     * Get an icon for the attachment based on content type.
     */
    private String getAttachmentIcon(String contentType) {
        if (contentType == null) return "\uD83D\uDCCE"; // 📎
        if (contentType.startsWith("image/")) return "\uD83D\uDDBC"; // 🖼
        if (contentType.startsWith("video/")) return "\uD83C\uDFA5"; // 🎥
        if (contentType.startsWith("audio/")) return "\uD83C\uDFB5"; // 🎵
        if (contentType.contains("pdf")) return "\uD83D\uDCC4"; // 📄
        if (contentType.contains("zip") || contentType.contains("rar") || contentType.contains("tar")) return "\uD83D\uDCE6"; // 📦
        return "\uD83D\uDCCE"; // 📎
    }

    /**
     * Open a file using the system's default application.
     */
    private void openFile(Path path) {
        if (path == null) return;
        File file = path.toFile();
        if (!file.exists()) {
            mainController.showError("File Not Found", "The attachment file was not found: " + path.getFileName());
            return;
        }

        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file);
            } else {
                // Fallback for systems without Desktop support
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", file.getAbsolutePath());
                } else if (os.contains("win")) {
                    pb = new ProcessBuilder("cmd", "/c", "start", "", file.getAbsolutePath());
                } else {
                    pb = new ProcessBuilder("xdg-open", file.getAbsolutePath());
                }
                pb.start();
            }
        } catch (IOException e) {
            mainController.showError("Cannot Open File", "Failed to open: " + e.getMessage());
        }
    }

    /**
     * Pre-fetch reply context for quick reply.
     */
    private void prepareReplyContext(long messageUid) {
        if (cacheService != null) {
            cacheService.getReplyContext(messageUid)
                .thenAccept(context -> Platform.runLater(() -> {
                    currentReplyContext = context;
                }))
                .exceptionally(error -> {
                    // Silently fail - we'll fetch on demand if needed
                    return null;
                });
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
        replySection.setVisible(false);
        replySection.setManaged(false);
        selectedMessageUid = null;
        selectedThreadId = null;
        currentReplyContext = null;
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
        selectedMessageUid = null;
        selectedThreadId = null;
        currentReplyContext = null;
    }

    /**
     * Show centered loading overlay.
     */
    private void showLoading(String message) {
        loadingLabel.setText(message);
        loadingOverlay.setVisible(true);
    }

    /**
     * Hide loading overlay.
     */
    private void hideLoading() {
        loadingOverlay.setVisible(false);
    }

    // ========== Reply Functions ==========

    private void clearReplyTextArea() {
        replyTextArea.clear();
        clearReplyAttachmentsInternal();
    }

    /**
     * Add attachment to reply (called from Attach button).
     */
    @FXML
    public void addReplyAttachment() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Select Files to Attach");
        java.util.List<java.io.File> files = chooser.showOpenMultipleDialog(
                replyTextArea.getScene().getWindow());

        if (files != null && !files.isEmpty()) {
            for (java.io.File file : files) {
                if (file.length() > MAX_ATTACHMENT_SIZE) {
                    mainController.showError("File too large",
                            file.getName() + " exceeds 25MB limit.");
                    continue;
                }
                java.nio.file.Path path = file.toPath();
                if (!replyAttachments.contains(path)) {
                    replyAttachments.add(path);
                }
            }
            updateAttachmentBar();
        }
    }

    /**
     * Clear all reply attachments (called from Clear button).
     */
    @FXML
    public void clearReplyAttachments() {
        clearReplyAttachmentsInternal();
    }

    private void clearReplyAttachmentsInternal() {
        replyAttachments.clear();
        updateAttachmentBar();
    }

    private void updateAttachmentBar() {
        if (replyAttachments.isEmpty()) {
            attachmentBar.setVisible(false);
            attachmentBar.setManaged(false);
        } else {
            attachmentBar.setVisible(true);
            attachmentBar.setManaged(true);
            String names = replyAttachments.stream()
                    .map(p -> p.getFileName().toString())
                    .collect(java.util.stream.Collectors.joining(", "));
            attachmentListLabel.setText(names + " (" + replyAttachments.size() + ")");
        }
    }

    @FXML
    public void sendInlineReply() {
        String body = replyTextArea.getText();
        if (body == null || body.isBlank()) {
            mainController.showError("Empty Reply", "Please enter a reply message.");
            return;
        }

        if (currentReplyContext == null) {
            // Need to fetch reply context first
            if (selectedMessageUid == null || selectedMessageUid <= 0) {
                mainController.showError("No Message", "No message selected for reply.");
                return;
            }

            showLoading("Preparing reply...");

            if (cacheService != null) {
                cacheService.getReplyContext(selectedMessageUid)
                    .thenAccept(context -> Platform.runLater(() -> {
                        currentReplyContext = context;
                        hideLoading();
                        doSendReply(context, body);
                    }))
                    .exceptionally(error -> {
                        Platform.runLater(() -> {
                            hideLoading();
                            mainController.showError("Failed to prepare reply", error.getCause().getMessage());
                        });
                        return null;
                    });
            } else {
                String password = mainController.getPassword();
                if (password == null) return;

                CompletableFuture.supplyAsync(() -> {
                    try {
                        return services.messages().getReplyContext(password, selectedMessageUid);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }).thenAccept(context -> Platform.runLater(() -> {
                    currentReplyContext = context;
                    hideLoading();
                    doSendReply(context, body);
                })).exceptionally(error -> {
                    Platform.runLater(() -> {
                        hideLoading();
                        mainController.showError("Failed to prepare reply", error.getCause().getMessage());
                    });
                    return null;
                });
            }
        } else {
            doSendReply(currentReplyContext, body);
        }
    }

    private void doSendReply(MessageService.ReplyContext context, String body) {
        // Capture attachments before clearing
        java.util.List<java.nio.file.Path> attachments = new java.util.ArrayList<>(replyAttachments);
        boolean hasAttachments = !attachments.isEmpty();

        // Immediately add the sent message to the chat view (optimistic UI)
        addSentMessageBubble(body, attachments);
        clearReplyTextArea();
        mainController.setStatus("Sending...");

        if (cacheService != null) {
            CompletableFuture<MessageService.SendResult> sendFuture;
            if (hasAttachments) {
                sendFuture = cacheService.sendMessageWithAttachments(
                        context.toEmail(), context.subject(), body, attachments,
                        context.originalMessageId(), context.references(), context.threadId());
            } else {
                sendFuture = cacheService.sendMessage(context.toEmail(), context.subject(), body,
                        context.originalMessageId(), context.references(), context.threadId());
            }
            sendFuture.thenAccept(result -> Platform.runLater(() -> {
                    if (result.success()) {
                        mainController.setStatus("Reply sent!");
                    } else {
                        mainController.showError("Send Failed", result.message());
                    }
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> {
                        mainController.showError("Failed to send reply", error.getCause().getMessage());
                    });
                    return null;
                });
        } else {
            String password = mainController.getPassword();
            if (password == null) return;

            CompletableFuture.supplyAsync(() -> {
                try {
                    if (hasAttachments) {
                        return services.messages().sendMessageWithAttachments(password, context.toEmail(),
                                context.subject(), body, attachments,
                                context.originalMessageId(), context.references(), context.threadId());
                    } else {
                        return services.messages().sendMessage(password, context.toEmail(), context.subject(), body,
                                context.originalMessageId(), context.references(), context.threadId());
                    }
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).thenAccept(result -> Platform.runLater(() -> {
                if (result.success()) {
                    mainController.setStatus("Reply sent!");
                } else {
                    mainController.showError("Send Failed", result.message());
                }
            })).exceptionally(error -> {
                Platform.runLater(() -> {
                    mainController.showError("Failed to send reply", error.getCause().getMessage());
                });
                return null;
            });
        }
    }

    /**
     * Add a sent message bubble to the chat view immediately.
     */
    private void addSentMessageBubble(String body, java.util.List<java.nio.file.Path> attachmentPaths) {
        // Convert paths to AttachmentInfo for display
        java.util.List<MessageService.AttachmentInfo> attachments = new java.util.ArrayList<>();
        if (attachmentPaths != null) {
            for (java.nio.file.Path p : attachmentPaths) {
                try {
                    String contentType = java.nio.file.Files.probeContentType(p);
                    attachments.add(new MessageService.AttachmentInfo(
                            p.getFileName().toString(),
                            contentType != null ? contentType : "application/octet-stream",
                            java.nio.file.Files.size(p),
                            true,
                            p
                    ));
                } catch (java.io.IOException ignored) {}
            }
        }

        // Create a sent message bubble
        MessageService.DecryptedMessage sentMsg = new MessageService.DecryptedMessage(
            -1,
            currentUserEmail != null ? currentUserEmail : "me",
            null,
            new java.util.Date(),
            body,
            true,
            attachments
        );
        HBox bubble = createMessageBubble(sentMsg);
        messageContent.getChildren().add(bubble);

        // Scroll to bottom after layout update
        Platform.runLater(() -> {
            messageContent.layout();
            messageScrollPane.layout();
            messageScrollPane.setVvalue(1.0);
        });
    }

    // ========== Helper methods ==========

    private String extractDisplayName(String from) {
        if (from == null) return "";
        int lt = from.indexOf('<');
        if (lt > 0) {
            return from.substring(0, lt).trim();
        }
        return from;
    }

    /**
     * Table row model for messages/threads.
     */
    public static class MessageRow {
        private final long uid;
        private final String threadId;
        private final StringProperty from;
        private final StringProperty subject;
        private final StringProperty dateString;
        private final BooleanProperty read;
        private final IntegerProperty messageCount;
        private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM dd HH:mm");
        private static final DateTimeFormatter EPOCH_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM dd HH:mm").withZone(ZoneId.systemDefault());

        // Constructor for ThreadSummary (from local storage)
        public MessageRow(InboxStore.ThreadSummary summary) {
            this.uid = 0; // Not used for thread-based view
            this.threadId = summary.threadId;
            this.from = new SimpleStringProperty(extractDisplayName(summary.lastFrom));
            this.subject = new SimpleStringProperty(summary.subject != null ? summary.subject : "(no subject)");
            this.dateString = new SimpleStringProperty(
                summary.lastReceivedEpochSec > 0
                    ? EPOCH_DATE_FORMAT.format(Instant.ofEpochSecond(summary.lastReceivedEpochSec))
                    : "");
            this.read = new SimpleBooleanProperty(summary.read);
            this.messageCount = new SimpleIntegerProperty(summary.messageCount);
        }

        // Constructor for MessageSummary (legacy/fallback)
        public MessageRow(MessageService.MessageSummary summary) {
            this.uid = summary.uid();
            this.threadId = null;
            this.from = new SimpleStringProperty(extractDisplayName(summary.from()));
            this.subject = new SimpleStringProperty(summary.subject() != null ? summary.subject() : "(no subject)");
            this.dateString = new SimpleStringProperty(
                summary.received() != null ? DATE_FORMAT.format(summary.received()) : "");
            this.read = new SimpleBooleanProperty(summary.seen());
            this.messageCount = new SimpleIntegerProperty(1);
        }

        private String extractDisplayName(String from) {
            if (from == null) return "";
            int lt = from.indexOf('<');
            if (lt > 0) {
                return from.substring(0, lt).trim();
            }
            return from;
        }

        public long getUid() { return uid; }
        public String getThreadId() { return threadId; }
        public String getFrom() { return from.get(); }
        public StringProperty fromProperty() { return from; }
        public String getSubject() { return subject.get(); }
        public StringProperty subjectProperty() { return subject; }
        public String getDateString() { return dateString.get(); }
        public StringProperty dateStringProperty() { return dateString; }
        public boolean isRead() { return read.get(); }
        public BooleanProperty readProperty() { return read; }
        public int getMessageCount() { return messageCount.get(); }
        public IntegerProperty messageCountProperty() { return messageCount; }
    }
}
