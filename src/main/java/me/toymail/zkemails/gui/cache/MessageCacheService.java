package me.toymail.zkemails.gui.cache;

import javafx.application.Platform;
import me.toymail.zkemails.ImapConnectionPool;
import me.toymail.zkemails.service.InboxSyncService;
import me.toymail.zkemails.service.MessageService;
import me.toymail.zkemails.service.ServiceContext;
import me.toymail.zkemails.store.InboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Service that manages message caching with background polling.
 * Provides fast access to cached data and automatic refresh.
 */
public class MessageCacheService {
    private static final Logger log = LoggerFactory.getLogger(MessageCacheService.class);

    private static final int POLL_INTERVAL_SECONDS = 120; // 2 minutes
    private static final int MESSAGE_FETCH_LIMIT = 100;

    private final ServiceContext services;
    private final MessageCache cache;
    private final InboxSyncService syncService;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService fetchExecutor;

    private ScheduledFuture<?> pollingTask;
    private String currentPassword;
    private String currentProfile;

    // Listeners for cache updates
    private final List<Consumer<CacheUpdateEvent>> updateListeners =
        new CopyOnWriteArrayList<>();

    // Track current refresh to prevent concurrent execution
    private final AtomicReference<CompletableFuture<Void>> currentRefresh = new AtomicReference<>();

    public MessageCacheService(ServiceContext services) {
        this.services = services;
        this.cache = new MessageCache();
        this.syncService = new InboxSyncService(services.storeContext());

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zke-cache-scheduler");
            t.setDaemon(true);
            return t;
        });

        this.fetchExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "zke-cache-fetch");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the cache service with initial fetch and polling.
     */
    public void start(String password) {
        this.currentPassword = password;
        try {
            this.currentProfile = services.profiles().getActiveProfile();
        } catch (Exception e) {
            log.error("Failed to get active profile", e);
            return;
        }

        if (currentProfile == null) {
            log.warn("No active profile, cache service not starting");
            return;
        }

        log.info("Starting message cache service for profile: {}", currentProfile);

        // Immediately notify with local data (instant load)
        notifyListeners(new CacheUpdateEvent(CacheUpdateType.MESSAGES_UPDATED, null));

        // Background: sync from IMAP
        refreshAsync();

        // Start polling
        startPolling();
    }

    /**
     * Stop the cache service and polling.
     */
    public void stop() {
        log.info("Stopping message cache service");
        stopPolling();
        cache.clear();
        currentPassword = null;
        currentProfile = null;
    }

    /**
     * Start background polling.
     */
    private void startPolling() {
        stopPolling(); // Stop any existing polling

        pollingTask = scheduler.scheduleAtFixedRate(
            this::pollForUpdates,
            POLL_INTERVAL_SECONDS,
            POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );

        log.info("Started polling every {} seconds", POLL_INTERVAL_SECONDS);
    }

    /**
     * Stop background polling.
     */
    private void stopPolling() {
        if (pollingTask != null) {
            pollingTask.cancel(false);
            pollingTask = null;
        }
    }

    /**
     * Poll for updates (called by scheduler).
     * Uses background flag to avoid showing loading modal.
     */
    private void pollForUpdates() {
        if (currentPassword == null || currentProfile == null) {
            return;
        }

        log.debug("Polling for message updates...");
        refreshAsyncInternal(true);  // true = background, silent
    }

    /**
     * Refresh by syncing from IMAP to local storage.
     * User-initiated refresh - shows loading modal.
     */
    public CompletableFuture<Void> refreshAsync() {
        return refreshAsyncInternal(false);
    }

    /**
     * Internal refresh with concurrency control and background flag.
     * @param isBackground true for background sync (silent), false for user-initiated (shows modal)
     */
    private CompletableFuture<Void> refreshAsyncInternal(boolean isBackground) {
        if (currentPassword == null) {
            return CompletableFuture.completedFuture(null);
        }

        // Check if refresh already in progress
        CompletableFuture<Void> existing = currentRefresh.get();
        if (existing != null && !existing.isDone()) {
            if (isBackground) {
                // Background sync skips if refresh already in progress
                log.debug("Skipping background sync - refresh already in progress");
                return existing;
            }
            // User-initiated: return existing future to avoid duplicate work
            log.debug("Refresh already in progress, returning existing future");
            return existing;
        }

        cache.setLoading(true);
        notifyListeners(new CacheUpdateEvent(CacheUpdateType.LOADING_STARTED, null, isBackground));

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                // Sync from IMAP to local storage
                var result = syncService.sync(currentPassword, MESSAGE_FETCH_LIMIT);

                cache.setLastError(null);

                if (result.newMessages() > 0) {
                    log.info("Synced {} new messages to local storage", result.newMessages());
                    notifyListeners(new CacheUpdateEvent(CacheUpdateType.MESSAGES_UPDATED, result.newMessages(), isBackground));
                } else {
                    log.debug("No new messages to sync");
                    // Still notify so UI can update status
                    notifyListeners(new CacheUpdateEvent(CacheUpdateType.MESSAGES_UPDATED, 0, isBackground));
                }

            } catch (Exception e) {
                log.error("Failed to sync: {}", e.getMessage());
                cache.setLastError(e.getMessage());
                notifyListeners(new CacheUpdateEvent(CacheUpdateType.ERROR, e.getMessage(), isBackground));
            } finally {
                cache.setLoading(false);
                currentRefresh.set(null);  // Clear tracking
                notifyListeners(new CacheUpdateEvent(CacheUpdateType.LOADING_FINISHED, null, isBackground));
            }
        }, fetchExecutor);

        currentRefresh.set(future);
        return future;
    }

    /**
     * Get message summaries (from cache - for backwards compatibility).
     */
    public List<MessageService.MessageSummary> getMessageSummaries() {
        return cache.getMessageSummaries();
    }

    /**
     * Get thread summaries from local storage (fast, uses index.json).
     */
    public List<InboxStore.ThreadSummary> getThreadSummaries() {
        return services.messages().listThreadsFromLocal(MESSAGE_FETCH_LIMIT);
    }

    /**
     * Get a decrypted message (from cache or fetch).
     */
    public CompletableFuture<MessageService.DecryptedMessage> getDecryptedMessage(long uid) {
        // Check cache first
        var cached = cache.getDecryptedMessage(uid);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(cached.get());
        }

        // Fetch and cache
        return CompletableFuture.supplyAsync(() -> {
            try {
                var decrypted = services.messages().decryptMessage(currentPassword, uid);
                cache.cacheDecryptedMessage(decrypted);
                return decrypted;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, fetchExecutor);
    }

    /**
     * Get thread messages (from local storage or IMAP).
     */
    public CompletableFuture<MessageService.ThreadMessages> getThread(long messageUid) {
        // First, find the thread ID for this message
        String threadId = services.messages().findThreadIdByUid(messageUid);

        if (threadId != null) {
            // Try local storage first
            var local = services.messages().getThreadFromLocal(threadId);
            if (local != null && !local.messages().isEmpty()) {
                log.debug("Returning thread from local storage for UID {} (threadId: {})", messageUid, threadId);
                return CompletableFuture.completedFuture(local);
            }
        }

        // Check in-memory cache
        var cached = cache.getThread(messageUid);
        if (cached.isPresent()) {
            log.debug("Returning cached thread for UID {}", messageUid);
            return CompletableFuture.completedFuture(cached.get());
        }

        // Fallback to IMAP fetch
        log.debug("Fetching thread from IMAP for UID {}", messageUid);
        return CompletableFuture.supplyAsync(() -> {
            try {
                var thread = services.messages().getThread(currentPassword, messageUid, 50);
                cache.cacheThread(messageUid, thread);
                return thread;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, fetchExecutor);
    }

    /**
     * Get thread messages by thread ID (from local storage).
     */
    public MessageService.ThreadMessages getThreadByThreadId(String threadId) {
        return services.messages().getThreadFromLocal(threadId);
    }

    /**
     * Get reply context (from cache or build from cached data).
     */
    public CompletableFuture<MessageService.ReplyContext> getReplyContext(long messageUid) {
        // Try to build from cache first
        var cached = cache.buildReplyContextFromCache(messageUid);
        if (cached.isPresent()) {
            log.debug("Using cached reply context for message {}", messageUid);
            return CompletableFuture.completedFuture(cached.get());
        }

        // Fetch from server
        return CompletableFuture.supplyAsync(() -> {
            try {
                var context = services.messages().getReplyContext(currentPassword, messageUid);
                cache.cacheReplyContext(messageUid, context);
                return context;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, fetchExecutor);
    }

    /**
     * Send a message and refresh cache.
     */
    public CompletableFuture<MessageService.SendResult> sendMessage(
            String toEmail, String subject, String body,
            String inReplyTo, String references, String threadId) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                var result = services.messages().sendMessage(
                    currentPassword, toEmail, subject, body, inReplyTo, references, threadId);

                // Refresh cache after sending
                if (result.success()) {
                    refreshAsync();
                }

                return result;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, fetchExecutor);
    }

    /**
     * Send a message with attachments and refresh cache.
     */
    public CompletableFuture<MessageService.SendResult> sendMessageWithAttachments(
            String toEmail, String subject, String body,
            java.util.List<java.nio.file.Path> attachmentPaths,
            String inReplyTo, String references, String threadId) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                var result = services.messages().sendMessageWithAttachments(
                    currentPassword, toEmail, subject, body, attachmentPaths,
                    inReplyTo, references, threadId);

                // Refresh cache after sending
                if (result.success()) {
                    refreshAsync();
                }

                return result;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, fetchExecutor);
    }

    /**
     * Check if cache has data.
     */
    public boolean hasCachedData() {
        return cache.hasMessageSummaries();
    }

    /**
     * Check if cache is loading.
     */
    public boolean isLoading() {
        return cache.isLoading();
    }

    /**
     * Get cache statistics.
     */
    public MessageCache.CacheStats getCacheStats() {
        return cache.getStats();
    }

    /**
     * Add a listener for cache updates.
     */
    public void addUpdateListener(Consumer<CacheUpdateEvent> listener) {
        updateListeners.add(listener);
    }

    /**
     * Remove an update listener.
     */
    public void removeUpdateListener(Consumer<CacheUpdateEvent> listener) {
        updateListeners.remove(listener);
    }

    /**
     * Notify listeners of cache update (on JavaFX thread).
     */
    private void notifyListeners(CacheUpdateEvent event) {
        Platform.runLater(() -> {
            for (var listener : updateListeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    log.error("Error in cache update listener", e);
                }
            }
        });
    }

    /**
     * Update password (e.g., after re-authentication).
     */
    public void updatePassword(String password) {
        this.currentPassword = password;
    }

    /**
     * Handle profile switch.
     */
    public void onProfileSwitch(String newProfile, String password) {
        log.info("Profile switched to: {}", newProfile);

        // Close IMAP connections for the old profile
        if (currentProfile != null && !currentProfile.equals(newProfile)) {
            ImapConnectionPool.getInstance().closeConnectionsForUser(currentProfile);
        }

        cache.clear();
        this.currentProfile = newProfile;
        this.currentPassword = password;
        refreshAsync();
    }

    /**
     * Shutdown the service.
     */
    public void shutdown() {
        stop();
        scheduler.shutdownNow();
        fetchExecutor.shutdownNow();
    }

    /**
     * Get the underlying cache (for direct access if needed).
     */
    public MessageCache getCache() {
        return cache;
    }

    // Event types for cache updates
    public enum CacheUpdateType {
        LOADING_STARTED,
        LOADING_FINISHED,
        MESSAGES_UPDATED,
        ERROR
    }

    public record CacheUpdateEvent(CacheUpdateType type, Object data, boolean isBackground) {
        // Convenience constructor for backward compatibility
        public CacheUpdateEvent(CacheUpdateType type, Object data) {
            this(type, data, false);
        }
    }
}
