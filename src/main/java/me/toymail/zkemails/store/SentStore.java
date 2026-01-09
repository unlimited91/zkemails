package me.toymail.zkemails.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Local storage for sent messages.
 * Stores plaintext so sent messages can be displayed in thread views.
 */
public final class SentStore {
    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static final class SentMessage {
        public String id;              // UUID for local reference
        public String messageId;       // SMTP Message-ID header for threading
        public String toEmail;
        public String subject;
        public String plaintext;
        public String inReplyTo;       // For threading
        public String references;      // For threading
        public String threadId;        // Custom ZKE thread ID (survives Gmail header stripping)
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
    private final Path file;

    public SentStore(ZkStore store) {
        this.store = store;
        this.file = store.path("outbox/sent.json");
    }

    /**
     * Save a sent message to local storage.
     */
    public synchronized void save(SentMessage msg) throws IOException {
        store.ensure();
        List<SentMessage> messages = readAll();
        messages.add(msg);
        writeAll(messages);
    }

    /**
     * Get a sent message by its SMTP Message-ID.
     */
    public synchronized SentMessage getByMessageId(String messageId) throws IOException {
        if (messageId == null) return null;
        List<SentMessage> messages = readAll();
        for (SentMessage m : messages) {
            if (messageId.equals(m.messageId)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Search for sent messages that match any of the given thread IDs.
     * Thread IDs can be Message-ID, In-Reply-To, or References values.
     */
    public synchronized List<SentMessage> searchByThreadIds(Set<String> threadIds) throws IOException {
        if (threadIds == null || threadIds.isEmpty()) return List.of();
        List<SentMessage> messages = readAll();
        List<SentMessage> matches = new ArrayList<>();
        for (SentMessage m : messages) {
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
        return matches;
    }

    /**
     * Search for sent messages by subject (case-insensitive, ignores Re: prefix).
     */
    public synchronized List<SentMessage> searchBySubject(String baseSubject) throws IOException {
        if (baseSubject == null || baseSubject.isEmpty()) return List.of();
        String normalized = normalizeSubject(baseSubject);
        List<SentMessage> messages = readAll();
        List<SentMessage> matches = new ArrayList<>();
        for (SentMessage m : messages) {
            if (m.subject != null && normalizeSubject(m.subject).equalsIgnoreCase(normalized)) {
                matches.add(m);
            }
        }
        return matches;
    }

    /**
     * Search for sent messages by custom ZKE thread ID.
     * This is the primary thread correlation method.
     */
    public synchronized List<SentMessage> searchByZkeThreadId(String threadId) throws IOException {
        if (threadId == null || threadId.isEmpty()) return List.of();
        List<SentMessage> messages = readAll();
        List<SentMessage> matches = new ArrayList<>();
        for (SentMessage m : messages) {
            if (threadId.equals(m.threadId)) {
                matches.add(m);
            }
        }
        return matches;
    }

    /**
     * List recent sent messages, sorted by date descending.
     */
    public synchronized List<SentMessage> list(int limit) throws IOException {
        List<SentMessage> messages = readAll();
        // Sort by sent date descending (newest first)
        messages.sort((a, b) -> Long.compare(b.sentAtEpochSec, a.sentAtEpochSec));
        if (limit > 0 && messages.size() > limit) {
            return messages.subList(0, limit);
        }
        return messages;
    }

    /**
     * Get all sent messages.
     */
    public synchronized List<SentMessage> listAll() throws IOException {
        return readAll();
    }

    private List<SentMessage> readAll() throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) return new ArrayList<>();
        return M.readValue(bytes, new TypeReference<List<SentMessage>>() {});
    }

    private void writeAll(List<SentMessage> messages) throws IOException {
        // Ensure parent directory exists
        Files.createDirectories(file.getParent());
        byte[] bytes = M.writeValueAsBytes(messages);
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String normalizeSubject(String subject) {
        if (subject == null) return "";
        // Remove Re: Fwd: prefixes
        return subject.replaceAll("(?i)^(Re:|Fwd:|Fw:)\\s*", "").trim();
    }
}
