package me.toymail.zkemails.gui.cache;

import me.toymail.zkemails.service.MessageService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache for encrypted messages.
 * Thread-safe for concurrent access from UI and background threads.
 */
public class MessageCache {

    // Cached message summaries (not decrypted)
    private final List<MessageService.MessageSummary> messageSummaries =
        Collections.synchronizedList(new ArrayList<>());

    // Cached decrypted messages by UID
    private final Map<Long, MessageService.DecryptedMessage> decryptedMessages =
        new ConcurrentHashMap<>();

    // Cached thread data by any message UID in the thread
    private final Map<Long, MessageService.ThreadMessages> threadCache =
        new ConcurrentHashMap<>();

    // Cached reply contexts by message UID
    private final Map<Long, MessageService.ReplyContext> replyContextCache =
        new ConcurrentHashMap<>();

    // Last refresh timestamp
    private volatile long lastRefreshTime = 0;

    // Cache status
    private volatile boolean loading = false;
    private volatile String lastError = null;

    /**
     * Update the message summaries cache.
     */
    public void updateMessageSummaries(List<MessageService.MessageSummary> summaries) {
        synchronized (messageSummaries) {
            messageSummaries.clear();
            messageSummaries.addAll(summaries);
        }
        lastRefreshTime = System.currentTimeMillis();
        lastError = null;
    }

    /**
     * Get cached message summaries.
     */
    public List<MessageService.MessageSummary> getMessageSummaries() {
        synchronized (messageSummaries) {
            return new ArrayList<>(messageSummaries);
        }
    }

    /**
     * Check if summaries are cached.
     */
    public boolean hasMessageSummaries() {
        synchronized (messageSummaries) {
            return !messageSummaries.isEmpty();
        }
    }

    /**
     * Get a message summary by UID.
     */
    public Optional<MessageService.MessageSummary> getMessageSummary(long uid) {
        synchronized (messageSummaries) {
            return messageSummaries.stream()
                .filter(m -> m.uid() == uid)
                .findFirst();
        }
    }

    /**
     * Cache a decrypted message.
     */
    public void cacheDecryptedMessage(MessageService.DecryptedMessage message) {
        decryptedMessages.put(message.uid(), message);
    }

    /**
     * Get a cached decrypted message.
     */
    public Optional<MessageService.DecryptedMessage> getDecryptedMessage(long uid) {
        return Optional.ofNullable(decryptedMessages.get(uid));
    }

    /**
     * Check if a decrypted message is cached.
     */
    public boolean hasDecryptedMessage(long uid) {
        return decryptedMessages.containsKey(uid);
    }

    /**
     * Cache thread messages.
     */
    public void cacheThread(long messageUid, MessageService.ThreadMessages thread) {
        threadCache.put(messageUid, thread);
        // Also cache individual decrypted messages from the thread
        for (var msg : thread.messages()) {
            decryptedMessages.put(msg.uid(), msg);
        }
    }

    /**
     * Get cached thread.
     */
    public Optional<MessageService.ThreadMessages> getThread(long messageUid) {
        return Optional.ofNullable(threadCache.get(messageUid));
    }

    /**
     * Cache reply context.
     */
    public void cacheReplyContext(long messageUid, MessageService.ReplyContext context) {
        replyContextCache.put(messageUid, context);
    }

    /**
     * Get cached reply context.
     */
    public Optional<MessageService.ReplyContext> getReplyContext(long messageUid) {
        return Optional.ofNullable(replyContextCache.get(messageUid));
    }

    /**
     * Build reply context from cached data.
     */
    public Optional<MessageService.ReplyContext> buildReplyContextFromCache(long messageUid) {
        // First check if we have it cached
        var cached = getReplyContext(messageUid);
        if (cached.isPresent()) {
            return cached;
        }

        // Try to build from decrypted message
        var decrypted = getDecryptedMessage(messageUid);
        if (decrypted.isEmpty()) {
            return Optional.empty();
        }

        var msg = decrypted.get();
        String toEmail = extractEmail(msg.from());
        if (toEmail == null) {
            return Optional.empty();
        }

        String replySubject = msg.subject();
        if (replySubject != null && !replySubject.toLowerCase().startsWith("re:")) {
            replySubject = "Re: " + replySubject;
        }

        String quotedBody = quoteText(msg.plaintext());

        // We don't have Message-ID, References, or threadId from cache, but we can still create a basic context
        var context = new MessageService.ReplyContext(toEmail, replySubject, quotedBody, null, null, null);
        cacheReplyContext(messageUid, context);
        return Optional.of(context);
    }

    /**
     * Get last refresh time.
     */
    public long getLastRefreshTime() {
        return lastRefreshTime;
    }

    /**
     * Check if cache is stale (older than specified milliseconds).
     */
    public boolean isStale(long maxAgeMs) {
        return System.currentTimeMillis() - lastRefreshTime > maxAgeMs;
    }

    /**
     * Get loading status.
     */
    public boolean isLoading() {
        return loading;
    }

    /**
     * Set loading status.
     */
    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    /**
     * Get last error.
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Set last error.
     */
    public void setLastError(String error) {
        this.lastError = error;
    }

    /**
     * Clear all caches.
     */
    public void clear() {
        synchronized (messageSummaries) {
            messageSummaries.clear();
        }
        decryptedMessages.clear();
        threadCache.clear();
        replyContextCache.clear();
        lastRefreshTime = 0;
        lastError = null;
    }

    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        return new CacheStats(
            messageSummaries.size(),
            decryptedMessages.size(),
            threadCache.size(),
            replyContextCache.size(),
            lastRefreshTime
        );
    }

    public record CacheStats(
        int summaryCount,
        int decryptedCount,
        int threadCount,
        int replyContextCount,
        long lastRefreshTime
    ) {}

    private String extractEmail(String from) {
        if (from == null) return null;
        int start = from.indexOf('<');
        int end = from.indexOf('>');
        if (start >= 0 && end > start) {
            return from.substring(start + 1, end).trim();
        }
        return from.trim();
    }

    private String quoteText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append("> ").append(line).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }
}
