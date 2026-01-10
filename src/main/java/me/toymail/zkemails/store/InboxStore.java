package me.toymail.zkemails.store;

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
 * Folder-based local storage for inbox messages.
 *
 * Structure:
 * ~/.zkemails/{email}/inbox/
 * ├── index.json                     # Thread summaries for fast listing
 * ├── {threadId}/                    # One folder per thread
 * │   ├── thread.json                # Thread metadata
 * │   └── {uid}/                     # One folder per message
 * │       ├── message.json           # Decrypted message content
 * │       └── attachments/           # Future: attachment files
 */
public class InboxStore {
    private static final Logger log = LoggerFactory.getLogger(InboxStore.class);
    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // ===== Index file (inbox/index.json) =====
    public static final class InboxIndex {
        public List<ThreadSummary> threads = new ArrayList<>();
        public long lastSyncEpochSec;

        public InboxIndex() {}
    }

    public static final class ThreadSummary {
        public String threadId;
        public String subject;              // For list display
        public String lastFrom;             // Most recent sender
        public long lastReceivedEpochSec;   // For sorting
        public int messageCount;
        public boolean read;

        public ThreadSummary() {}

        public ThreadSummary(String threadId, String subject, String lastFrom,
                            long lastReceivedEpochSec, int messageCount, boolean read) {
            this.threadId = threadId;
            this.subject = subject;
            this.lastFrom = lastFrom;
            this.lastReceivedEpochSec = lastReceivedEpochSec;
            this.messageCount = messageCount;
            this.read = read;
        }
    }

    // ===== Thread metadata (inbox/{threadId}/thread.json) =====
    public static final class ThreadMeta {
        public String threadId;
        public String baseSubject;          // Normalized (no Re:/Fwd:)
        public List<Long> messageUids = new ArrayList<>();
        public boolean read;
        public long lastReadEpochSec;

        public ThreadMeta() {}

        public ThreadMeta(String threadId, String baseSubject) {
            this.threadId = threadId;
            this.baseSubject = baseSubject;
            this.read = false;
            this.lastReadEpochSec = 0;
        }
    }

    // ===== Message content (inbox/{threadId}/{uid}/message.json) =====
    public static final class InboxMessage {
        public long uid;                    // IMAP UID
        public String messageId;            // SMTP Message-ID
        public String from;
        public String to;
        public String subject;
        public String plaintext;            // Decrypted body
        public String threadId;
        public long receivedEpochSec;
        public long syncedEpochSec;

        // Future: attachment support
        public List<AttachmentMeta> attachments;

        public InboxMessage() {}
    }

    public static final class AttachmentMeta {
        public String filename;
        public String contentType;
        public long size;
        public String localPath;            // Relative path within attachments/

        public AttachmentMeta() {}

        public AttachmentMeta(String filename, String contentType, long size, String localPath) {
            this.filename = filename;
            this.contentType = contentType;
            this.size = size;
            this.localPath = localPath;
        }
    }

    private final Path inboxPath;

    public InboxStore(Path inboxPath) {
        this.inboxPath = inboxPath;
    }

    // ===== Index operations =====

    /**
     * Load the index file.
     */
    public synchronized InboxIndex loadIndex() {
        Path indexFile = inboxPath.resolve("index.json");
        if (!Files.exists(indexFile)) {
            return new InboxIndex();
        }
        try {
            return M.readValue(Files.readAllBytes(indexFile), InboxIndex.class);
        } catch (IOException e) {
            log.warn("Failed to read index.json: {}", e.getMessage());
            return new InboxIndex();
        }
    }

    /**
     * Save the index file.
     */
    public synchronized void saveIndex(InboxIndex index) throws IOException {
        Files.createDirectories(inboxPath);
        Path indexFile = inboxPath.resolve("index.json");
        Files.write(indexFile, M.writeValueAsBytes(index),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * List threads from index, sorted by date (newest first).
     */
    public synchronized List<ThreadSummary> listThreads(int limit) {
        InboxIndex index = loadIndex();
        List<ThreadSummary> sorted = new ArrayList<>(index.threads);
        sorted.sort((a, b) -> Long.compare(b.lastReceivedEpochSec, a.lastReceivedEpochSec));
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
        Path threadFile = inboxPath.resolve(threadId).resolve("thread.json");
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
        Path threadDir = inboxPath.resolve(meta.threadId);
        Files.createDirectories(threadDir);
        Path threadFile = threadDir.resolve("thread.json");
        Files.write(threadFile, M.writeValueAsBytes(meta),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * List all thread IDs.
     */
    public synchronized List<String> listThreadIds() {
        if (!Files.exists(inboxPath)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(inboxPath)) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.equals("index.json"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Failed to list thread IDs: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Delete a thread folder.
     */
    public synchronized void deleteThread(String threadId) throws IOException {
        Path threadDir = inboxPath.resolve(threadId);
        if (Files.exists(threadDir)) {
            deleteRecursively(threadDir);
        }
        // Update index
        InboxIndex index = loadIndex();
        index.threads.removeIf(t -> threadId.equals(t.threadId));
        saveIndex(index);
    }

    // ===== Message operations =====

    /**
     * Load a single message.
     */
    public synchronized InboxMessage loadMessage(String threadId, long uid) {
        Path msgFile = inboxPath.resolve(threadId).resolve(String.valueOf(uid)).resolve("message.json");
        if (!Files.exists(msgFile)) {
            return null;
        }
        try {
            return M.readValue(Files.readAllBytes(msgFile), InboxMessage.class);
        } catch (IOException e) {
            log.warn("Failed to read message.json for {}/{}: {}", threadId, uid, e.getMessage());
            return null;
        }
    }

    /**
     * Save a message (creates folder structure, updates thread and index).
     */
    public synchronized void saveMessage(InboxMessage msg) throws IOException {
        // 1. Create folder: inbox/{threadId}/{uid}/
        Path msgDir = inboxPath.resolve(msg.threadId).resolve(String.valueOf(msg.uid));
        Files.createDirectories(msgDir);

        // 2. Write message.json
        Path msgFile = msgDir.resolve("message.json");
        Files.write(msgFile, M.writeValueAsBytes(msg),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // 3. Create attachments/ folder (empty for now)
        Files.createDirectories(msgDir.resolve("attachments"));

        // 4. Update thread.json
        ThreadMeta thread = loadThread(msg.threadId);
        if (thread == null) {
            thread = new ThreadMeta(msg.threadId, normalizeSubject(msg.subject));
        }
        if (!thread.messageUids.contains(msg.uid)) {
            thread.messageUids.add(msg.uid);
        }
        saveThread(thread);

        // 5. Update index.json
        updateIndexForThread(msg.threadId, msg);
    }

    /**
     * Load all messages in a thread.
     */
    public synchronized List<InboxMessage> loadThreadMessages(String threadId) {
        ThreadMeta meta = loadThread(threadId);
        if (meta == null) {
            return List.of();
        }

        List<InboxMessage> messages = new ArrayList<>();
        for (long uid : meta.messageUids) {
            InboxMessage msg = loadMessage(threadId, uid);
            if (msg != null) {
                messages.add(msg);
            }
        }
        // Sort by received date
        messages.sort(Comparator.comparingLong(m -> m.receivedEpochSec));
        return messages;
    }

    /**
     * Get all UIDs across all threads (for sync comparison).
     */
    public synchronized Set<Long> getAllUids() {
        Set<Long> uids = new HashSet<>();
        if (!Files.exists(inboxPath)) {
            return uids;
        }

        try (Stream<Path> threadDirs = Files.list(inboxPath)) {
            threadDirs.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().equals("index.json"))
                    .forEach(threadDir -> {
                        try (Stream<Path> msgDirs = Files.list(threadDir)) {
                            msgDirs.filter(Files::isDirectory)
                                    .forEach(msgDir -> {
                                        String name = msgDir.getFileName().toString();
                                        if (!name.equals("attachments")) {
                                            try {
                                                uids.add(Long.parseLong(name));
                                            } catch (NumberFormatException ignored) {}
                                        }
                                    });
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.warn("Failed to scan UIDs: {}", e.getMessage());
        }
        return uids;
    }

    /**
     * Find thread ID by message UID.
     */
    public synchronized String findThreadIdByUid(long uid) {
        if (!Files.exists(inboxPath)) {
            return null;
        }

        try (Stream<Path> threadDirs = Files.list(inboxPath)) {
            for (Path threadDir : threadDirs.filter(Files::isDirectory).toList()) {
                String threadId = threadDir.getFileName().toString();
                if (threadId.equals("index.json")) continue;

                Path msgDir = threadDir.resolve(String.valueOf(uid));
                if (Files.exists(msgDir)) {
                    return threadId;
                }
            }
        } catch (IOException e) {
            log.warn("Failed to find thread for UID {}: {}", uid, e.getMessage());
        }
        return null;
    }

    // ===== Read status =====

    /**
     * Mark a thread as read.
     */
    public synchronized void markThreadRead(String threadId) {
        try {
            ThreadMeta meta = loadThread(threadId);
            if (meta != null) {
                meta.read = true;
                meta.lastReadEpochSec = System.currentTimeMillis() / 1000;
                saveThread(meta);

                // Update index
                InboxIndex index = loadIndex();
                for (ThreadSummary ts : index.threads) {
                    if (threadId.equals(ts.threadId)) {
                        ts.read = true;
                        break;
                    }
                }
                saveIndex(index);
            }
        } catch (IOException e) {
            log.warn("Failed to mark thread {} as read: {}", threadId, e.getMessage());
        }
    }

    /**
     * Check if a thread is read.
     */
    public synchronized boolean isThreadRead(String threadId) {
        ThreadMeta meta = loadThread(threadId);
        return meta != null && meta.read;
    }

    // ===== Path helpers =====

    public Path getMessagePath(String threadId, long uid) {
        return inboxPath.resolve(threadId).resolve(String.valueOf(uid));
    }

    public Path getAttachmentsPath(String threadId, long uid) {
        return getMessagePath(threadId, uid).resolve("attachments");
    }

    // ===== Attachment operations =====

    /**
     * Save an attachment to disk.
     * @return the relative path within the attachments folder (stored in AttachmentMeta.localPath)
     */
    public synchronized String saveAttachment(String threadId, long uid, String filename, byte[] data) throws IOException {
        Path attachDir = getAttachmentsPath(threadId, uid);
        Files.createDirectories(attachDir);

        // Sanitize filename and handle collisions
        String safeName = sanitizeFilename(filename);
        Path targetPath = resolveUniqueFilename(attachDir, safeName);

        Files.write(targetPath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.debug("Saved attachment: {}", targetPath);

        // Return the filename as stored (may include collision suffix)
        return targetPath.getFileName().toString();
    }

    /**
     * Get the full path to an attachment.
     */
    public Path getAttachmentFullPath(String threadId, long uid, String localPath) {
        return getAttachmentsPath(threadId, uid).resolve(localPath);
    }

    /**
     * Read an attachment from disk.
     */
    public byte[] readAttachment(String threadId, long uid, String localPath) throws IOException {
        Path path = getAttachmentFullPath(threadId, uid, localPath);
        if (!Files.exists(path)) {
            throw new IOException("Attachment not found: " + path);
        }
        return Files.readAllBytes(path);
    }

    /**
     * Check if an attachment exists on disk.
     */
    public boolean attachmentExists(String threadId, long uid, String localPath) {
        Path path = getAttachmentFullPath(threadId, uid, localPath);
        return Files.exists(path);
    }

    /**
     * Sanitize filename to remove unsafe characters.
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "attachment";
        }
        // Replace unsafe characters with underscore
        String safe = filename.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "_");
        // Remove leading/trailing dots and spaces
        safe = safe.replaceAll("^[.\\s]+|[.\\s]+$", "");
        // Limit length
        if (safe.length() > 200) {
            int extIdx = safe.lastIndexOf('.');
            if (extIdx > 0 && safe.length() - extIdx < 20) {
                String ext = safe.substring(extIdx);
                safe = safe.substring(0, 200 - ext.length()) + ext;
            } else {
                safe = safe.substring(0, 200);
            }
        }
        return safe.isEmpty() ? "attachment" : safe;
    }

    /**
     * Resolve unique filename by appending (1), (2), etc. if collision.
     */
    private Path resolveUniqueFilename(Path dir, String filename) {
        Path target = dir.resolve(filename);
        if (!Files.exists(target)) {
            return target;
        }

        // Extract base and extension
        int dotIdx = filename.lastIndexOf('.');
        String base = dotIdx > 0 ? filename.substring(0, dotIdx) : filename;
        String ext = dotIdx > 0 ? filename.substring(dotIdx) : "";

        int counter = 1;
        while (Files.exists(target)) {
            target = dir.resolve(base + "(" + counter + ")" + ext);
            counter++;
            if (counter > 1000) {
                // Safety limit
                target = dir.resolve(base + "_" + System.currentTimeMillis() + ext);
                break;
            }
        }
        return target;
    }

    // ===== Private helpers =====

    private void updateIndexForThread(String threadId, InboxMessage latestMsg) throws IOException {
        InboxIndex index = loadIndex();

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
            summary.read = false;
            index.threads.add(summary);
        }

        // Update summary with latest message info
        summary.subject = latestMsg.subject;
        summary.lastFrom = latestMsg.from;
        if (latestMsg.receivedEpochSec > summary.lastReceivedEpochSec) {
            summary.lastReceivedEpochSec = latestMsg.receivedEpochSec;
        }

        // Count messages in thread
        ThreadMeta meta = loadThread(threadId);
        summary.messageCount = meta != null ? meta.messageUids.size() : 1;

        index.lastSyncEpochSec = System.currentTimeMillis() / 1000;
        saveIndex(index);
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> children = Files.list(path)) {
                for (Path child : children.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.delete(path);
    }

    private static String normalizeSubject(String subject) {
        if (subject == null) return "";
        return subject.replaceAll("(?i)^(Re:|Fwd:|Fw:)\\s*", "").trim();
    }
}
