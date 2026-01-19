package me.toymail.zkemails.service;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.ImapConnectionPool;
import me.toymail.zkemails.crypto.CryptoBox;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.InboxStore;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;

/**
 * Service for syncing inbox messages from IMAP to local storage.
 * Uses checkpoint-based sync with parallel message fetching for performance.
 *
 * Key optimizations:
 * - Checkpoint: Uses lastSyncEpochSec to limit IMAP search window
 * - Parallel fetch: Uses ForkJoinPool to decrypt/store messages concurrently
 * - Non-blocking: Provides async API with callback for UI integration
 */
public class InboxSyncService {
    private static final Logger log = LoggerFactory.getLogger(InboxSyncService.class);

    // Parallelism for message fetching (balances speed vs. IMAP server load)
    private static final int FETCH_PARALLELISM = 4;

    private final StoreContext context;
    private final ForkJoinPool fetchPool;

    public InboxSyncService(StoreContext context) {
        this.context = context;
        this.fetchPool = new ForkJoinPool(FETCH_PARALLELISM);
    }

    /**
     * Listener for async sync operations.
     */
    public interface SyncListener {
        void onSyncComplete(SyncResult result);
        void onSyncError(Exception error);
    }

    public record SyncResult(
        int newMessages,
        int failedMessages,
        List<Long> newUids
    ) {}

    /**
     * Sync inbox from IMAP to local storage (blocking).
     * Uses checkpoint-based filtering and parallel message fetching.
     */
    public SyncResult sync(String password, int limit) throws Exception {
        CompletableFuture<SyncResult> future = new CompletableFuture<>();
        syncAsync(password, limit, new SyncListener() {
            @Override
            public void onSyncComplete(SyncResult result) {
                future.complete(result);
            }
            @Override
            public void onSyncError(Exception error) {
                future.completeExceptionally(error);
            }
        });
        return future.get(); // Block until complete
    }

    /**
     * Sync inbox from IMAP to local storage (non-blocking).
     * Returns immediately and notifies listener on completion.
     *
     * Uses checkpoint-based filtering: only searches IMAP for messages since last sync.
     * Uses parallel fetching: decrypts and stores messages concurrently.
     */
    public void syncAsync(String password, int limit, SyncListener listener) {
        CompletableFuture.runAsync(() -> {
            try {
                SyncResult result = doSync(password, limit);
                listener.onSyncComplete(result);
            } catch (Exception e) {
                listener.onSyncError(e);
            }
        }, fetchPool);
    }

    /**
     * Internal sync implementation with checkpoint and parallel fetch.
     */
    private SyncResult doSync(String password, int limit) throws Exception {
        if (!context.hasActiveProfile()) {
            throw new IllegalStateException("No active profile set");
        }

        Config cfg = context.zkStore().readJson("config.json", Config.class);
        IdentityKeys.KeyBundle myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);

        if (cfg == null || myKeys == null) {
            throw new IllegalStateException("Not initialized");
        }

        ImapClient.ImapConfig imapConfig = new ImapClient.ImapConfig(
                cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, password);

        // Get checkpoint from last sync
        InboxStore.InboxIndex index = context.inboxStore().loadIndex();
        long checkpoint = index.lastSyncEpochSec;

        // Get folder from config (defaults to INBOX for backward compatibility)
        String folder = cfg.imap.folder != null ? cfg.imap.folder : "INBOX";

        System.out.println("\n=== Checkpoint Debug ===");
        System.out.println("IMAP Folder: " + folder);
        System.out.println("lastSyncEpochSec from index: " + checkpoint);
        if (checkpoint > 0) {
            System.out.println("Checkpoint date: " + new java.util.Date(checkpoint * 1000));
            System.out.println("Search will look for messages since: " + new java.util.Date((checkpoint - 86400) * 1000) + " (checkpoint - 1 day)");
        } else {
            System.out.println("No checkpoint set, will use 30-day window");
        }

        ImapClient imap = ImapConnectionPool.getInstance().getConnection(imapConfig, folder);
        try {
            // 1. Search IMAP with checkpoint filter (reduces server-side results)
            List<ImapClient.MailSummary> imapMsgs = imap.searchHeaderEqualsSince(
                    "X-ZKEmails-Type", "msg", checkpoint, limit);
            log.info("IMAP search returned {} messages (checkpoint: {})", imapMsgs.size(), checkpoint);
            System.out.println("IMAP returned " + imapMsgs.size() + " messages");
            for (var msg : imapMsgs) {
                System.out.println("  - UID " + msg.uid() + " received: " + msg.received() + " subject: " + msg.subject());
            }

            Set<Long> imapUids = new HashSet<>();
            Map<Long, ImapClient.MailSummary> imapMsgMap = new HashMap<>();
            for (var msg : imapMsgs) {
                imapUids.add(msg.uid());
                imapMsgMap.put(msg.uid(), msg);
            }

            // 2. Get local UIDs for delta calculation
            Set<Long> localUids = context.inboxStore().getAllUids();

            // 3. Find new UIDs (in IMAP but not local)
            Set<Long> newUids = new HashSet<>(imapUids);
            newUids.removeAll(localUids);

            if (newUids.isEmpty()) {
                log.info("Inbox sync: no new messages");
                updateSyncTimestamp();
                return new SyncResult(0, 0, List.of());
            }

            log.info("Inbox sync: {} new messages to fetch in parallel", newUids.size());
            System.out.println("\n=== Starting parallel fetch of " + newUids.size() + " messages using " + FETCH_PARALLELISM + " threads ===");
            System.out.println("UIDs to fetch: " + newUids);
            long startTime = System.currentTimeMillis();

            // 4. Fetch, decrypt, and save messages in parallel
            List<Long> uidList = new ArrayList<>(newUids);
            final String syncFolder = folder; // Capture for lambda
            List<CompletableFuture<SyncTaskResult>> futures = uidList.stream()
                    .map(uid -> CompletableFuture.supplyAsync(
                            () -> processSingleMessage(uid, imapMsgMap.get(uid), imapConfig, syncFolder, cfg, myKeys),
                            fetchPool))
                    .toList();

            // Wait for all tasks and collect results
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            long elapsedMs = System.currentTimeMillis() - startTime;
            System.out.println("=== Parallel fetch completed in " + elapsedMs + "ms ===\n");

            List<Long> syncedUids = new ArrayList<>();
            int failedCount = 0;

            for (CompletableFuture<SyncTaskResult> future : futures) {
                SyncTaskResult result = future.join();
                if (result.success) {
                    syncedUids.add(result.uid);
                } else {
                    failedCount++;
                }
            }

            updateSyncTimestamp();
            log.info("Inbox sync complete: {} new, {} failed", syncedUids.size(), failedCount);
            return new SyncResult(syncedUids.size(), failedCount, syncedUids);

        } catch (Exception e) {
            ImapConnectionPool.getInstance().invalidateConnection(imap);
            throw e;
        }
    }

    /**
     * Result of processing a single message.
     */
    private record SyncTaskResult(long uid, boolean success, String error) {}

    /**
     * Process a single message: fetch details, decrypt, and store.
     * Each task creates its own IMAP connection for thread safety.
     */
    private SyncTaskResult processSingleMessage(long uid, ImapClient.MailSummary cachedSummary,
                                                  ImapClient.ImapConfig imapConfig, String folder,
                                                  Config cfg, IdentityKeys.KeyBundle myKeys) {
        String threadName = Thread.currentThread().getName();
        log.info("[{}] Starting to process message UID {}", threadName, uid);
        System.out.println("[" + threadName + "] Fetching message UID " + uid);

        ImapClient imap = null;
        try {
            // Create fresh connection for this task (Folder is not thread-safe)
            imap = ImapConnectionPool.getInstance().createFreshConnection(imapConfig, folder);
            System.out.println("[" + threadName + "] IMAP connection established for UID " + uid);

            ImapClient.MailSummary msgSummary = cachedSummary;
            if (msgSummary == null) {
                msgSummary = imap.getMessageByUid(uid);
            }

            if (msgSummary == null) {
                log.warn("Message UID {} not found", uid);
                return new SyncTaskResult(uid, false, "Message not found");
            }

            // Get thread ID from header (never generate random IDs)
            String threadId = imap.getZkeThreadId(uid);
            if (threadId == null || threadId.isEmpty()) {
                String baseSubject = normalizeSubject(msgSummary.subject());
                String sender = extractEmail(msgSummary.from());
                threadId = baseSubject + ":" + (sender != null ? sender : "unknown");
            }

            // Create inbox message
            InboxStore.InboxMessage inboxMsg = new InboxStore.InboxMessage();
            inboxMsg.uid = uid;
            inboxMsg.messageId = imap.getMessageId(uid);
            inboxMsg.from = msgSummary.from();
            inboxMsg.to = cfg.email;
            inboxMsg.subject = msgSummary.subject();
            inboxMsg.threadId = threadId;
            inboxMsg.receivedEpochSec = msgSummary.received() != null
                    ? msgSummary.received().getTime() / 1000
                    : Instant.now().getEpochSecond();
            inboxMsg.syncedEpochSec = Instant.now().getEpochSecond();

            // Check message version and attachments
            Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(uid);
            String version = first(hdrs, "X-ZKEmails-Version");
            boolean hasAttachments = imap.hasAttachments(uid);
            int attachmentCount = imap.getAttachmentCount(uid);

            System.out.println("[" + threadName + "] UID " + uid + " - version=" + version +
                ", hasAttachments=" + hasAttachments + ", attachmentCount=" + attachmentCount);
            log.debug("Processing UID {} - version={}, hasAttachments={}", uid, version, hasAttachments);

            if (hasAttachments) {
                System.out.println("[" + threadName + "] UID " + uid + " - decrypting WITH attachments");
                DecryptResult result = decryptMessageWithAttachments(imap, uid, msgSummary, threadId, cfg, myKeys);
                if (result == null) {
                    System.out.println("[" + threadName + "] UID " + uid + " - FAILED: decryption with attachments returned null");
                    return new SyncTaskResult(uid, false, "Decryption failed (with attachments)");
                }
                inboxMsg.plaintext = result.plaintext;
                inboxMsg.attachments = result.attachments;
                System.out.println("[" + threadName + "] UID " + uid + " - decrypted with " +
                    (result.attachments != null ? result.attachments.size() : 0) + " attachments");
            } else {
                System.out.println("[" + threadName + "] UID " + uid + " - decrypting WITHOUT attachments (version=" + version + ")");
                String plaintext = decryptMessage(imap, msgSummary, cfg, myKeys);
                if (plaintext == null) {
                    System.out.println("[" + threadName + "] UID " + uid + " - FAILED: decryption returned null");
                    return new SyncTaskResult(uid, false, "Decryption failed");
                }
                inboxMsg.plaintext = plaintext;
            }

            // Save message (InboxStore.saveMessage is synchronized)
            context.inboxStore().saveMessage(inboxMsg);
            log.debug("Synced message UID {} from {} (thread {})", uid, inboxMsg.from, threadId);
            System.out.println("[" + threadName + "] Successfully processed UID " + uid + " from " + inboxMsg.from);
            return new SyncTaskResult(uid, true, null);

        } catch (Exception e) {
            log.warn("Failed to sync message UID {}: {}", uid, e.getMessage());
            System.out.println("[" + threadName + "] FAILED to process UID " + uid + ": " + e.getMessage());
            return new SyncTaskResult(uid, false, e.getMessage());
        } finally {
            // Close the fresh connection
            if (imap != null) {
                try {
                    imap.close();
                } catch (Exception e) {
                    log.debug("Error closing IMAP connection: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Update the sync timestamp in the index.
     */
    private void updateSyncTimestamp() {
        try {
            InboxStore.InboxIndex index = context.inboxStore().loadIndex();
            long newTimestamp = Instant.now().getEpochSecond();
            System.out.println("=== Updating checkpoint: " + index.lastSyncEpochSec + " -> " + newTimestamp + " ===");
            System.out.println("New checkpoint date: " + new java.util.Date(newTimestamp * 1000));
            index.lastSyncEpochSec = newTimestamp;
            context.inboxStore().saveIndex(index);
        } catch (IOException e) {
            log.warn("Failed to update sync timestamp: {}", e.getMessage());
        }
    }

    /**
     * Count new messages without fetching content.
     * Uses checkpoint-based search for efficiency.
     */
    public int countNewMessages(String password) throws Exception {
        if (!context.hasActiveProfile()) {
            return 0;
        }

        Config cfg = context.zkStore().readJson("config.json", Config.class);
        if (cfg == null) {
            return 0;
        }

        ImapClient.ImapConfig imapConfig = new ImapClient.ImapConfig(
                cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, password);

        // Get folder from config (defaults to INBOX for backward compatibility)
        String folder = cfg.imap.folder != null ? cfg.imap.folder : "INBOX";

        // Get checkpoint from last sync
        InboxStore.InboxIndex index = context.inboxStore().loadIndex();
        long checkpoint = index.lastSyncEpochSec;

        ImapClient imap = ImapConnectionPool.getInstance().getConnection(imapConfig, folder);
        try {
            List<ImapClient.MailSummary> imapMsgs = imap.searchHeaderEqualsSince(
                    "X-ZKEmails-Type", "msg", checkpoint, 1000);
            Set<Long> imapUids = new HashSet<>();
            for (var msg : imapMsgs) {
                imapUids.add(msg.uid());
            }

            Set<Long> localUids = context.inboxStore().getAllUids();

            imapUids.removeAll(localUids);
            return imapUids.size();

        } catch (Exception e) {
            ImapConnectionPool.getInstance().invalidateConnection(imap);
            throw e;
        }
    }

    /**
     * Shutdown the sync service and release resources.
     */
    public void shutdown() {
        fetchPool.shutdown();
        try {
            if (!fetchPool.awaitTermination(5, TimeUnit.SECONDS)) {
                fetchPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            fetchPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Full refresh: clear local storage and re-sync.
     */
    public SyncResult fullRefresh(String password, int limit) throws Exception {
        // Clear all local inbox data
        for (String threadId : context.inboxStore().listThreadIds()) {
            context.inboxStore().deleteThread(threadId);
        }

        // Re-sync from IMAP
        return sync(password, limit);
    }

    /**
     * Decrypt a message (supports v1 header-based and v2 JSON payload).
     */
    private String decryptMessage(ImapClient imap, ImapClient.MailSummary msg, Config cfg, IdentityKeys.KeyBundle myKeys) {
        try {
            Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(msg.uid());

            String fromEmail = extractEmail(msg.from());
            ContactsStore.Contact contact = context.contacts().get(fromEmail);
            if (contact == null || contact.ed25519PublicB64 == null) {
                log.debug("No contact found for sender: {}", fromEmail);
                return null;
            }

            // Check for v2 multi-recipient message
            String version = first(hdrs, "X-ZKEmails-Version");
            if ("2".equals(version)) {
                return decryptV2Message(imap, msg, cfg, myKeys, contact);
            }

            // V1 header-based decryption
            String ephemX25519PubB64 = first(hdrs, "X-ZKEmails-Ephem-X25519");
            String wrappedKeyB64 = first(hdrs, "X-ZKEmails-WrappedKey");
            String wrappedKeyNonceB64 = first(hdrs, "X-ZKEmails-WrappedKey-Nonce");
            String msgNonceB64 = first(hdrs, "X-ZKEmails-Nonce");
            String ciphertextB64 = first(hdrs, "X-ZKEmails-Ciphertext");
            String sigB64 = first(hdrs, "X-ZKEmails-Sig");
            String recipientFpHex = first(hdrs, "X-ZKEmails-Recipient-Fp");

            CryptoBox.EncryptedPayload payload = new CryptoBox.EncryptedPayload(
                    ephemX25519PubB64, wrappedKeyB64, wrappedKeyNonceB64,
                    msgNonceB64, ciphertextB64, sigB64, recipientFpHex
            );

            return CryptoBox.decryptForRecipient(
                    fromEmail,
                    cfg.email,
                    msg.subject(),
                    payload,
                    myKeys.x25519PrivateB64(),
                    contact.ed25519PublicB64
            );
        } catch (Exception e) {
            log.debug("Decryption failed for message {}: {}", msg.uid(), e.getMessage());
            return null;
        }
    }

    /**
     * Decrypt a v2 multi-recipient message.
     */
    private String decryptV2Message(ImapClient imap, ImapClient.MailSummary msg,
                                     Config cfg, IdentityKeys.KeyBundle myKeys,
                                     ContactsStore.Contact contact) throws Exception {
        // Fetch the JSON payload from MIME multipart
        CryptoBox.EncryptedPayloadV2 payload = imap.fetchJsonPayload(msg.uid());
        if (payload == null) {
            log.debug("Failed to fetch v2 JSON payload for UID {}", msg.uid());
            return null;
        }

        log.debug("Fetched v2 payload with {} recipients for UID {}", payload.recipients().size(), msg.uid());

        // Get my fingerprint from keys
        String myFpHex = myKeys.fingerprintHex();

        // Extract primary To recipient from message headers for AAD binding
        Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(msg.uid());
        String toHeader = first(hdrs, "To");
        String primaryTo = toHeader != null ? extractEmail(toHeader.split(",")[0]) : cfg.email;

        String fromEmail = extractEmail(msg.from());

        return CryptoBox.decryptFromMultipleRecipientMessage(
                fromEmail,
                primaryTo,
                msg.subject(),
                payload,
                myFpHex,
                myKeys.x25519PrivateB64(),
                contact.ed25519PublicB64
        );
    }

    private static String first(Map<String, List<String>> map, String key) {
        List<String> v = map.get(key);
        return (v != null && !v.isEmpty()) ? v.get(0) : null;
    }

    private static String extractEmail(String from) {
        if (from == null) return null;
        int start = from.indexOf('<');
        int end = from.indexOf('>');
        if (start >= 0 && end > start) {
            return from.substring(start + 1, end).trim();
        }
        return from.trim();
    }

    private static String normalizeSubject(String subject) {
        if (subject == null) return "";
        return subject.replaceAll("(?i)^(Re:|Fwd:|Fw:)\\s*", "").trim();
    }

    /**
     * Result of decrypting a message (with optional attachments).
     */
    private record DecryptResult(String plaintext, List<InboxStore.AttachmentMeta> attachments) {}

    /**
     * Decrypt a message that has attachments.
     * Uses decryptWithAttachments which properly verifies signature including attachment hash.
     * Supports both V1 and V2 messages.
     */
    private DecryptResult decryptMessageWithAttachments(ImapClient imap, long uid, ImapClient.MailSummary msgSummary,
                                                         String threadId, Config cfg, IdentityKeys.KeyBundle myKeys) throws Exception {
        System.out.println("  [decrypt] UID " + uid + " - fetching attachment container...");

        // Fetch attachment container
        ImapClient.AttachmentContainer container = imap.fetchAttachmentContainer(uid);
        if (container == null || container.attachments() == null) {
            System.out.println("  [decrypt] UID " + uid + " - FAILED: no attachment container found");
            log.warn("Message {} has attachments header but no attachments found", uid);
            return null;
        }

        System.out.println("  [decrypt] UID " + uid + " - found " + container.attachments().size() + " encrypted attachments");
        log.debug("Decrypting message with {} attachments for UID {}", container.attachments().size(), uid);

        // Get encryption headers to determine version
        Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(uid);
        String version = first(hdrs, "X-ZKEmails-Version");

        String fromEmail = extractEmail(msgSummary.from());
        System.out.println("  [decrypt] UID " + uid + " - from: " + fromEmail + ", version: " + version);

        ContactsStore.Contact contact = context.contacts().get(fromEmail);
        if (contact == null || contact.ed25519PublicB64 == null) {
            System.out.println("  [decrypt] UID " + uid + " - FAILED: no contact/keys for sender: " + fromEmail);
            log.debug("No contact found for sender: {}", fromEmail);
            return null;
        }
        System.out.println("  [decrypt] UID " + uid + " - contact found with fingerprint: " + contact.fingerprintHex);

        // V2 message with attachments
        if ("2".equals(version)) {
            System.out.println("  [decrypt] UID " + uid + " - using V2 decryption with attachments");
            try {
                return decryptV2MessageWithAttachments(imap, uid, msgSummary, threadId, hdrs, cfg, myKeys, contact, container.attachments());
            } catch (Exception e) {
                System.out.println("  [decrypt] UID " + uid + " - V2 decryption EXCEPTION: " + e.getMessage());
                e.printStackTrace();
                throw e;
            }
        }

        // V1 header-based message with attachments
        System.out.println("  [decrypt] UID " + uid + " - using V1 decryption with attachments");
        try {
            return decryptV1MessageWithAttachments(msgSummary, threadId, hdrs, cfg, myKeys, fromEmail, contact, container.attachments());
        } catch (Exception e) {
            System.out.println("  [decrypt] UID " + uid + " - V1 decryption EXCEPTION: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Decrypt a V1 header-based message with attachments.
     */
    private DecryptResult decryptV1MessageWithAttachments(ImapClient.MailSummary msgSummary, String threadId,
                                                           Map<String, List<String>> hdrs, Config cfg,
                                                           IdentityKeys.KeyBundle myKeys, String fromEmail,
                                                           ContactsStore.Contact contact,
                                                           List<CryptoBox.EncryptedAttachment> encryptedAttachments) throws Exception {
        String ephemX25519PubB64 = first(hdrs, "X-ZKEmails-Ephem-X25519");
        String wrappedKeyB64 = first(hdrs, "X-ZKEmails-WrappedKey");
        String wrappedKeyNonceB64 = first(hdrs, "X-ZKEmails-WrappedKey-Nonce");
        String msgNonceB64 = first(hdrs, "X-ZKEmails-Nonce");
        String ciphertextB64 = first(hdrs, "X-ZKEmails-Ciphertext");
        String sigB64 = first(hdrs, "X-ZKEmails-Sig");
        String recipientFpHex = first(hdrs, "X-ZKEmails-Recipient-Fp");

        CryptoBox.EncryptedPayload payload = new CryptoBox.EncryptedPayload(
                ephemX25519PubB64, wrappedKeyB64, wrappedKeyNonceB64,
                msgNonceB64, ciphertextB64, sigB64, recipientFpHex
        );

        // Decrypt with attachments (this verifies signature including attachment hash)
        CryptoBox.DecryptedMessageWithAttachments result = CryptoBox.decryptWithAttachments(
                fromEmail,
                cfg.email,
                msgSummary.subject(),
                payload,
                encryptedAttachments,
                myKeys.x25519PrivateB64(),
                contact.ed25519PublicB64
        );

        return saveDecryptedAttachments(result, threadId, msgSummary.uid());
    }

    /**
     * Decrypt a V2 multi-recipient message with attachments.
     */
    private DecryptResult decryptV2MessageWithAttachments(ImapClient imap, long uid, ImapClient.MailSummary msgSummary,
                                                           String threadId, Map<String, List<String>> hdrs, Config cfg,
                                                           IdentityKeys.KeyBundle myKeys, ContactsStore.Contact contact,
                                                           List<CryptoBox.EncryptedAttachment> encryptedAttachments) throws Exception {
        System.out.println("    [V2] UID " + uid + " - fetching JSON payload...");

        // Fetch the JSON payload from MIME multipart
        CryptoBox.EncryptedPayloadV2 payload = imap.fetchJsonPayload(uid);
        if (payload == null) {
            System.out.println("    [V2] UID " + uid + " - FAILED: no JSON payload found");
            log.debug("Failed to fetch v2 JSON payload for UID {}", uid);
            return null;
        }

        System.out.println("    [V2] UID " + uid + " - payload has " + payload.recipients().size() + " recipients");
        log.debug("Fetched v2 payload with {} recipients for UID {} (with attachments)", payload.recipients().size(), uid);

        // Get my fingerprint from keys
        String myFpHex = myKeys.fingerprintHex();
        System.out.println("    [V2] UID " + uid + " - my fingerprint: " + myFpHex);

        // Check if I'm a recipient
        boolean foundMyKey = payload.recipients().stream()
                .anyMatch(r -> r.fpHex().equals(myFpHex));
        System.out.println("    [V2] UID " + uid + " - am I a recipient? " + foundMyKey);
        for (var rk : payload.recipients()) {
            System.out.println("    [V2] UID " + uid + " - recipient fp: " + rk.fpHex());
        }

        // Extract primary To recipient from message headers for AAD binding
        String toHeader = first(hdrs, "To");
        String primaryTo = toHeader != null ? extractEmail(toHeader.split(",")[0]) : cfg.email;
        System.out.println("    [V2] UID " + uid + " - primaryTo for AAD: " + primaryTo);

        String fromEmail = extractEmail(msgSummary.from());
        System.out.println("    [V2] UID " + uid + " - calling CryptoBox.decryptFromMultipleRecipientMessageWithAttachments...");

        // Decrypt with attachments (this verifies signature including attachment hash)
        CryptoBox.DecryptedMessageWithAttachments result = CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                fromEmail,
                primaryTo,
                msgSummary.subject(),
                payload,
                encryptedAttachments,
                myFpHex,
                myKeys.x25519PrivateB64(),
                contact.ed25519PublicB64
        );

        System.out.println("    [V2] UID " + uid + " - decryption successful!");
        return saveDecryptedAttachments(result, threadId, uid);
    }

    /**
     * Save decrypted attachments to disk and return metadata.
     */
    private DecryptResult saveDecryptedAttachments(CryptoBox.DecryptedMessageWithAttachments result,
                                                    String threadId, long uid) throws IOException {
        List<InboxStore.AttachmentMeta> metas = new ArrayList<>();
        if (result.attachments() != null) {
            for (var decryptedAtt : result.attachments()) {
                try {
                    String localPath = context.inboxStore().saveAttachment(threadId, uid,
                            decryptedAtt.filename(), decryptedAtt.data());
                    metas.add(new InboxStore.AttachmentMeta(
                            decryptedAtt.filename(),
                            decryptedAtt.contentType(),
                            decryptedAtt.originalSize(),
                            localPath
                    ));
                    log.debug("Saved attachment: {} ({} bytes)", decryptedAtt.filename(), decryptedAtt.data().length);
                } catch (IOException e) {
                    log.warn("Failed to save attachment {}: {}", decryptedAtt.filename(), e.getMessage());
                }
            }
        }

        return new DecryptResult(result.plaintext(), metas.isEmpty() ? null : metas);
    }
}
