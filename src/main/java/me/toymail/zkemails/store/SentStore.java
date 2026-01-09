package me.toymail.zkemails.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Folder-based local storage for sent messages.
 *
 * Structure (mirrors InboxStore):
 * ~/.zkemails/{email}/outbox/
 * ├── index.json                     # Thread summaries for fast listing
 * ├── {threadId}/                    # One folder per thread
 * │   ├── thread.json                # Thread metadata
 * │   └── {id}/                      # One folder per message (using UUID, no IMAP UID)
 * │       └── message.json           # Sent message content
 */
public final class SentStore {
    private static final Logger log = LoggerFactory.getLogger(SentStore.class);
    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // ===== Index file (outbox/index.json) =====
    public static final class SentIndex {
        public List<ThreadSummary> threads = new ArrayList<>();
        public long lastUpdatedEpochSec;

        public SentIndex() {}
    }

    public static final class ThreadSummary {
        public String threadId;
        public String subject;
        public String lastTo;              // Most recent recipient
        public long lastSentEpochSec;      // For sorting
        public int messageCount;

        public ThreadSummary() {}

        public ThreadSummary(String threadId, String subject, String lastTo,
                            long lastSentEpochSec, int messageCount) {
            this.threadId = threadId;
            this.subject = subject;
            this.lastTo = lastTo;
            this.lastSentEpochSec = lastSentEpochSec;
            this.messageCount = messageCount;
        }
    }

    // ===== Thread metadata (outbox/{threadId}/thread.json) =====
    public static final class ThreadMeta {
        public String threadId;
        public String baseSubject;          // Normalized (no Re:/Fwd:)
        public List<String> messageIds = new ArrayList<>();  // Using UUID ids

        public ThreadMeta() {}

        public ThreadMeta(String threadId, String baseSubject) {
            this.threadId = threadId;
            this.baseSubject = baseSubject;
        }
    }

    // ===== Message content (outbox/{threadId}/{id}/message.json) =====
    public static final class SentMessage {
        public String id;              // UUID for local reference
        public String messageId;       // SMTP Message-ID header for threading
        public String toEmail;
        public String subject;
        public String plaintext;
        public String inReplyTo;       // For threading
        public String references;      // For threading
        public String threadId;        // Custom ZKE thread ID
        public long sentAtEpochSec;

        public SentMessage() {}

        public SentMessage(String id, String messageId, String toEmail, String subject,
                          String plaintext, String inReplyTo, String references, String threadId, long sentAtEpochSec) {
            this.id = id;
            this.messageId = messageId;
            this.toEmail = toEmail;
            this.subject = subject;
            this.plaintext = plaintext;
            this.inReplyTo = inReplyTo;
            this.references = references;
            this.threadId = threadId;
            this.sentAtEpochSec = sentAtEpochSec;
        }
    }

    private final ZkStore store;
    private final Path outboxPath;
    private final Path legacySentFile;

    public SentStore(ZkStore store) {
        this.store = store;
        this.outboxPath = store.path("outbox");
        this.legacySentFile = store.path("outbox/sent.json");
        // Auto-migrate on construction
        migrateIfNeeded();
    }

    // ===== Migration from old format =====

    /**
     * Migrate from old sent.json flat file to folder structure.
     */
    private synchronized void migrateIfNeeded() {
        if (!Files.exists(legacySentFile)) {
            return;
        }

        // Check if migration already done (index.json exists)
        Path indexFile = outboxPath.resolve("index.json");
        if (Files.exists(indexFile)) {
            return;
        }

        log.info("Migrating sent.json to folder structure...");
        try {
            // Read old format
            byte[] bytes = Files.readAllBytes(legacySentFile);
            if (bytes.length == 0) {
                return;
            }
            List<SentMessage> oldMessages = M.readValue(bytes, new TypeReference<List<SentMessage>>() {});

            // Save each message in new format
            for (SentMessage msg : oldMessages) {
                saveMessageInternal(msg, false); // Don't update index yet
            }

            // Rebuild index from folders
            rebuildIndex();

            // Rename old file as backup
            Path backup = store.path("outbox/sent.json.migrated");
            Files.move(legacySentFile, backup, StandardCopyOption.REPLACE_EXISTING);
            log.info("Migration complete. {} messages migrated. Old file backed up to sent.json.migrated", oldMessages.size());
        } catch (IOException e) {
            log.error("Migration failed: {}", e.getMessage());
        }
    }

    /**
     * Rebuild index.json from folder structure.
     */
    private synchronized void rebuildIndex() throws IOException {
        SentIndex index = new SentIndex();
        index.lastUpdatedEpochSec = System.currentTimeMillis() / 1000;

        List<String> threadIds = listThreadIds();
        for (String threadId : threadIds) {
            List<SentMessage> messages = loadThreadMessagesInternal(threadId);
            if (!messages.isEmpty()) {
                // Find latest message
                SentMessage latest = messages.stream()
                        .max(Comparator.comparingLong(m -> m.sentAtEpochSec))
                        .orElse(messages.get(0));

                ThreadSummary summary = new ThreadSummary(
                        threadId,
                        latest.subject,
                        latest.toEmail,
                        latest.sentAtEpochSec,
                        messages.size()
                );
                index.threads.add(summary);
            }
        }

        saveIndex(index);
    }

    // ===== Index operations =====

    /**
     * Load the index file.
     */
    public synchronized SentIndex loadIndex() {
        Path indexFile = outboxPath.resolve("index.json");
        if (!Files.exists(indexFile)) {
            return new SentIndex();
        }
        try {
            return M.readValue(Files.readAllBytes(indexFile), SentIndex.class);
        } catch (IOException e) {
            log.warn("Failed to read outbox index.json: {}", e.getMessage());
            return new SentIndex();
        }
    }

    /**
     * Save the index file.
     */
    public synchronized void saveIndex(SentIndex index) throws IOException {
        Files.createDirectories(outboxPath);
        Path indexFile = outboxPath.resolve("index.json");
        Files.write(indexFile, M.writeValueAsBytes(index),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * List threads from index, sorted by date (newest first).
     */
    public synchronized List<ThreadSummary> listThreads(int limit) {
        SentIndex index = loadIndex();
        List<ThreadSummary> sorted = new ArrayList<>(index.threads);
        sorted.sort((a, b) -> Long.compare(b.lastSentEpochSec, a.lastSentEpochSec));
        if (limit > 0 && sorted.size() > limit) {
            return sorted.subList(0, limit);
        }
        return sorted;
    }

    // ===== Thread operations =====

    /**
     * Load thread metadata.
     */
    public synchronized ThreadMeta loadThread(String threadId) {
        Path threadFile = outboxPath.resolve(threadId).resolve("thread.json");
        if (!Files.exists(threadFile)) {
            return null;
        }
        try {
            return M.readValue(Files.readAllBytes(threadFile), ThreadMeta.class);
        } catch (IOException e) {
            log.warn("Failed to read thread.json for {}: {}", threadId, e.getMessage());
            return null;
        }
    }

    /**
     * Save thread metadata.
     */
    public synchronized void saveThread(ThreadMeta meta) throws IOException {
        Path threadDir = outboxPath.resolve(meta.threadId);
        Files.createDirectories(threadDir);
        Path threadFile = threadDir.resolve("thread.json");
        Files.write(threadFile, M.writeValueAsBytes(meta),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * List all thread IDs.
     */
    public synchronized List<String> listThreadIds() {
        if (!Files.exists(outboxPath)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(outboxPath)) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Failed to list sent thread IDs: {}", e.getMessage());
            return List.of();
        }
    }

    // ===== Message operations =====

    /**
     * Load a single message.
     */
    public synchronized SentMessage loadMessage(String threadId, String id) {
        Path msgFile = outboxPath.resolve(threadId).resolve(id).resolve("message.json");
        if (!Files.exists(msgFile)) {
            return null;
        }
        try {
            return M.readValue(Files.readAllBytes(msgFile), SentMessage.class);
        } catch (IOException e) {
            log.warn("Failed to read message.json for {}/{}: {}", threadId, id, e.getMessage());
            return null;
        }
    }

    /**
     * Save a sent message (creates folder structure, updates thread and index).
     */
    public synchronized void save(SentMessage msg) throws IOException {
        store.ensure();
        saveMessageInternal(msg, true);
    }

    private synchronized void saveMessageInternal(SentMessage msg, boolean updateIndex) throws IOException {
        // Determine threadId - use the message's threadId or generate one
        String threadId = msg.threadId;
        if (threadId == null || threadId.isEmpty()) {
            // Fallback: use normalized subject as threadId
            threadId = normalizeSubject(msg.subject);
            if (threadId.isEmpty()) {
                threadId = "no-subject";
            }
            msg.threadId = threadId;
        }

        // 1. Create folder: outbox/{threadId}/{id}/
        Path msgDir = outboxPath.resolve(threadId).resolve(msg.id);
        Files.createDirectories(msgDir);

        // 2. Write message.json
        Path msgFile = msgDir.resolve("message.json");
        Files.write(msgFile, M.writeValueAsBytes(msg),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // 3. Update thread.json
        ThreadMeta thread = loadThread(threadId);
        if (thread == null) {
            thread = new ThreadMeta(threadId, normalizeSubject(msg.subject));
        }
        if (!thread.messageIds.contains(msg.id)) {
            thread.messageIds.add(msg.id);
        }
        saveThread(thread);

        // 4. Update index.json
        if (updateIndex) {
            updateIndexForThread(threadId, msg);
        }
    }

    /**
     * Load all messages in a thread - O(1) direct folder lookup.
     */
    public synchronized List<SentMessage> loadThreadMessages(String threadId) {
        return loadThreadMessagesInternal(threadId);
    }

    private synchronized List<SentMessage> loadThreadMessagesInternal(String threadId) {
        ThreadMeta meta = loadThread(threadId);
        if (meta == null) {
            // Try scanning folder directly if thread.json doesn't exist
            return scanThreadFolder(threadId);
        }

        List<SentMessage> messages = new ArrayList<>();
        for (String id : meta.messageIds) {
            SentMessage msg = loadMessage(threadId, id);
            if (msg != null) {
                messages.add(msg);
            }
        }
        // Sort by sent date
        messages.sort(Comparator.comparingLong(m -> m.sentAtEpochSec));
        return messages;
    }

    private synchronized List<SentMessage> scanThreadFolder(String threadId) {
        Path threadDir = outboxPath.resolve(threadId);
        if (!Files.exists(threadDir)) {
            return List.of();
        }

        List<SentMessage> messages = new ArrayList<>();
        try (Stream<Path> msgDirs = Files.list(threadDir)) {
            for (Path msgDir : msgDirs.filter(Files::isDirectory).toList()) {
                Path msgFile = msgDir.resolve("message.json");
                if (Files.exists(msgFile)) {
                    try {
                        SentMessage msg = M.readValue(Files.readAllBytes(msgFile), SentMessage.class);
                        messages.add(msg);
                    } catch (IOException ignored) {}
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan thread folder {}: {}", threadId, e.getMessage());
        }
        messages.sort(Comparator.comparingLong(m -> m.sentAtEpochSec));
        return messages;
    }

    // ===== Legacy API methods (for backward compatibility) =====

    /**
     * Get a sent message by its SMTP Message-ID.
     */
    public synchronized SentMessage getByMessageId(String messageId) {
        if (messageId == null) return null;

        // Search through all threads
        for (String threadId : listThreadIds()) {
            for (SentMessage msg : loadThreadMessages(threadId)) {
                if (messageId.equals(msg.messageId)) {
                    return msg;
                }
            }
        }
        return null;
    }

    /**
     * Search for sent messages by custom ZKE thread ID - O(1) direct folder lookup.
     */
    public synchronized List<SentMessage> searchByZkeThreadId(String threadId) {
        if (threadId == null || threadId.isEmpty()) return List.of();
        return loadThreadMessages(threadId);
    }

    /**
     * Search for sent messages that match any of the given thread IDs.
     */
    public synchronized List<SentMessage> searchByThreadIds(Set<String> threadIds) {
        if (threadIds == null || threadIds.isEmpty()) return List.of();

        List<SentMessage> matches = new ArrayList<>();
        for (String threadId : listThreadIds()) {
            for (SentMessage m : loadThreadMessages(threadId)) {
                // Check if message's Message-ID is in thread IDs
                if (m.messageId != null && threadIds.contains(m.messageId)) {
                    matches.add(m);
                    continue;
                }
                // Check if message's In-Reply-To matches
                if (m.inReplyTo != null && threadIds.contains(m.inReplyTo)) {
                    matches.add(m);
                    continue;
                }
                // Check if any of the References match
                if (m.references != null) {
                    for (String ref : m.references.split("\\s+")) {
                        if (threadIds.contains(ref.trim())) {
                            matches.add(m);
                            break;
                        }
                    }
                }
            }
        }
        return matches;
    }

    /**
     * Search for sent messages by subject (case-insensitive, ignores Re: prefix).
     */
    public synchronized List<SentMessage> searchBySubject(String baseSubject) {
        if (baseSubject == null || baseSubject.isEmpty()) return List.of();
        String normalized = normalizeSubject(baseSubject);

        List<SentMessage> matches = new ArrayList<>();
        for (String threadId : listThreadIds()) {
            for (SentMessage m : loadThreadMessages(threadId)) {
                if (m.subject != null && normalizeSubject(m.subject).equalsIgnoreCase(normalized)) {
                    matches.add(m);
                }
            }
        }
        return matches;
    }

    /**
     * List recent sent messages from all threads, sorted by date descending.
     * Used by SentController for backward compatibility.
     */
    public synchronized List<SentMessage> list(int limit) {
        List<SentMessage> all = new ArrayList<>();
        for (String threadId : listThreadIds()) {
            all.addAll(loadThreadMessages(threadId));
        }
        // Sort by sent date descending (newest first)
        all.sort((a, b) -> Long.compare(b.sentAtEpochSec, a.sentAtEpochSec));
        if (limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    /**
     * Get all sent messages.
     */
    public synchronized List<SentMessage> listAll() {
        return list(0);
    }

    // ===== Private helpers =====

    private void updateIndexForThread(String threadId, SentMessage latestMsg) throws IOException {
        SentIndex index = loadIndex();

        // Find or create thread summary
        ThreadSummary summary = null;
        for (ThreadSummary ts : index.threads) {
            if (threadId.equals(ts.threadId)) {
                summary = ts;
                break;
            }
        }

        if (summary == null) {
            summary = new ThreadSummary();
            summary.threadId = threadId;
            index.threads.add(summary);
        }

        // Update summary with latest message info
        summary.subject = latestMsg.subject;
        summary.lastTo = latestMsg.toEmail;
        if (latestMsg.sentAtEpochSec > summary.lastSentEpochSec) {
            summary.lastSentEpochSec = latestMsg.sentAtEpochSec;
        }

        // Count messages in thread
        ThreadMeta meta = loadThread(threadId);
        summary.messageCount = meta != null ? meta.messageIds.size() : 1;

        index.lastUpdatedEpochSec = System.currentTimeMillis() / 1000;
        saveIndex(index);
    }

    private static String normalizeSubject(String subject) {
        if (subject == null) return "";
        return subject.replaceAll("(?i)^(Re:|Fwd:|Fw:)\\s*", "").trim();
    }
}
