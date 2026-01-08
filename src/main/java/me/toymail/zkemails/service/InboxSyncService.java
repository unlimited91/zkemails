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

/**
 * Service for syncing inbox messages from IMAP to local storage.
 * Uses delta-based sync: only fetches messages not already stored locally.
 */
public class InboxSyncService {
    private static final Logger log = LoggerFactory.getLogger(InboxSyncService.class);

    private final StoreContext context;

    public InboxSyncService(StoreContext context) {
        this.context = context;
    }

    public record SyncResult(
        int newMessages,
        int failedMessages,
        List<Long> newUids
    ) {}

    /**
     * Sync inbox from IMAP to local storage.
     * Only fetches and stores messages not already in local storage.
     */
    public SyncResult sync(String password, int limit) throws Exception {
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

        ImapClient imap = ImapConnectionPool.getInstance().getConnection(imapConfig);
        try {
            // 1. Get all message UIDs from IMAP
            List<ImapClient.MailSummary> imapMsgs = imap.searchHeaderEquals("X-ZKEmails-Type", "msg", limit);
            Set<Long> imapUids = new HashSet<>();
            Map<Long, ImapClient.MailSummary> imapMsgMap = new HashMap<>();
            for (var msg : imapMsgs) {
                imapUids.add(msg.uid());
                imapMsgMap.put(msg.uid(), msg);
            }

            // 2. Get local UIDs
            Set<Long> localUids = context.inboxStore().getAllUids();

            // 3. Find new UIDs (in IMAP but not local)
            Set<Long> newUids = new HashSet<>(imapUids);
            newUids.removeAll(localUids);

            if (newUids.isEmpty()) {
                log.info("Inbox sync: no new messages");
                return new SyncResult(0, 0, List.of());
            }

            log.info("Inbox sync: {} new messages to fetch", newUids.size());

            // 4. Fetch, decrypt, and save each new message
            List<Long> syncedUids = new ArrayList<>();
            int failedCount = 0;

            for (long uid : newUids) {
                try {
                    ImapClient.MailSummary msgSummary = imapMsgMap.get(uid);
                    if (msgSummary == null) {
                        msgSummary = imap.getMessageByUid(uid);
                    }

                    if (msgSummary == null) {
                        log.warn("Message UID {} not found", uid);
                        failedCount++;
                        continue;
                    }

                    // Decrypt the message
                    String plaintext = decryptMessage(imap, msgSummary, cfg, myKeys);
                    if (plaintext == null) {
                        log.warn("Failed to decrypt message UID {}", uid);
                        failedCount++;
                        continue;
                    }

                    // Get thread ID from header (never generate random IDs)
                    String threadId = imap.getZkeThreadId(uid);
                    if (threadId == null || threadId.isEmpty()) {
                        // Use normalized subject + sender as deterministic fallback
                        String baseSubject = normalizeSubject(msgSummary.subject());
                        String sender = extractEmail(msgSummary.from());
                        threadId = baseSubject + ":" + (sender != null ? sender : "unknown");
                    }

                    // Create and save inbox message
                    InboxStore.InboxMessage inboxMsg = new InboxStore.InboxMessage();
                    inboxMsg.uid = uid;
                    inboxMsg.messageId = imap.getMessageId(uid);
                    inboxMsg.from = msgSummary.from();
                    inboxMsg.to = cfg.email;
                    inboxMsg.subject = msgSummary.subject();
                    inboxMsg.plaintext = plaintext;
                    inboxMsg.threadId = threadId;
                    inboxMsg.receivedEpochSec = msgSummary.received() != null
                            ? msgSummary.received().getTime() / 1000
                            : Instant.now().getEpochSecond();
                    inboxMsg.syncedEpochSec = Instant.now().getEpochSecond();

                    context.inboxStore().saveMessage(inboxMsg);
                    syncedUids.add(uid);

                    log.debug("Synced message UID {} from {} (thread {})", uid, inboxMsg.from, threadId);

                } catch (Exception e) {
                    log.warn("Failed to sync message UID {}: {}", uid, e.getMessage());
                    failedCount++;
                }
            }

            log.info("Inbox sync complete: {} new, {} failed", syncedUids.size(), failedCount);
            return new SyncResult(syncedUids.size(), failedCount, syncedUids);

        } catch (Exception e) {
            ImapConnectionPool.getInstance().invalidateConnection(imap);
            throw e;
        }
    }

    /**
     * Count new messages without fetching content.
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

        ImapClient imap = ImapConnectionPool.getInstance().getConnection(imapConfig);
        try {
            List<ImapClient.MailSummary> imapMsgs = imap.searchHeaderEquals("X-ZKEmails-Type", "msg", 1000);
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
     * Decrypt a message (reused from MessageService pattern).
     */
    private String decryptMessage(ImapClient imap, ImapClient.MailSummary msg, Config cfg, IdentityKeys.KeyBundle myKeys) {
        try {
            Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(msg.uid());

            String ephemX25519PubB64 = first(hdrs, "X-ZKEmails-Ephem-X25519");
            String wrappedKeyB64 = first(hdrs, "X-ZKEmails-WrappedKey");
            String wrappedKeyNonceB64 = first(hdrs, "X-ZKEmails-WrappedKey-Nonce");
            String msgNonceB64 = first(hdrs, "X-ZKEmails-Nonce");
            String ciphertextB64 = first(hdrs, "X-ZKEmails-Ciphertext");
            String sigB64 = first(hdrs, "X-ZKEmails-Sig");
            String recipientFpHex = first(hdrs, "X-ZKEmails-Recipient-Fp");

            String fromEmail = extractEmail(msg.from());
            ContactsStore.Contact contact = context.contacts().get(fromEmail);
            if (contact == null || contact.ed25519PublicB64 == null) {
                log.debug("No contact found for sender: {}", fromEmail);
                return null;
            }

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
}
