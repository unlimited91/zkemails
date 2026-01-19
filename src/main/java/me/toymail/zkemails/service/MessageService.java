package me.toymail.zkemails.service;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.ImapConnectionPool;
import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.CryptoBox;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.InboxStore;
import me.toymail.zkemails.store.SentStore;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Service for encrypted message operations.
 */
public final class MessageService {
    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private final StoreContext context;

    public MessageService(StoreContext context) {
        this.context = context;
    }

    /**
     * Decrypted message record for GUI display.
     */
    public record DecryptedMessage(
        long uid,
        String from,
        String to,              // To recipients (v2)
        String cc,              // CC recipients (v2)
        String subject,
        Date received,
        String plaintext,
        boolean decryptionSuccessful,
        List<AttachmentInfo> attachments
    ) {
        public DecryptedMessage(long uid, String from, String subject, Date received,
                               String plaintext, boolean decryptionSuccessful) {
            this(uid, from, null, null, subject, received, plaintext, decryptionSuccessful, List.of());
        }

        public DecryptedMessage(long uid, String from, String subject, Date received,
                               String plaintext, boolean decryptionSuccessful, List<AttachmentInfo> attachments) {
            this(uid, from, null, null, subject, received, plaintext, decryptionSuccessful, attachments);
        }
    }

    /**
     * Attachment info for GUI display.
     */
    public record AttachmentInfo(
        String filename,
        String contentType,
        long size,
        boolean availableLocally,
        Path localPath
    ) {}

    /**
     * Result of sending a message.
     */
    public record SendResult(boolean success, String message) {}

    // ==================== V2 Multi-Recipient Support ====================

    /**
     * Input for multi-recipient message sending.
     */
    public record MultiRecipientInput(
        List<String> toEmails,
        List<String> ccEmails,
        List<String> bccEmails
    ) {
        public List<String> visibleRecipients() {
            List<String> result = new ArrayList<>();
            if (toEmails != null) result.addAll(toEmails);
            if (ccEmails != null) result.addAll(ccEmails);
            return result;
        }

        public List<String> allRecipients() {
            List<String> result = new ArrayList<>();
            if (toEmails != null) result.addAll(toEmails);
            if (ccEmails != null) result.addAll(ccEmails);
            if (bccEmails != null) result.addAll(bccEmails);
            return result;
        }
    }

    /**
     * Result of sending a multi-recipient message.
     */
    public record MultiSendResult(
        boolean success,
        String message,
        String mainMessageId,
        List<String> bccMessageIds,
        List<String> failedRecipients
    ) {}

    /**
     * Thread messages with metadata.
     */
    public record ThreadMessages(
        String baseSubject,
        List<DecryptedMessage> messages
    ) {}

    /**
     * Message summary (not yet decrypted).
     */
    public record MessageSummary(
        long uid,
        String from,
        String subject,
        Date received,
        boolean seen
    ) {
        public static MessageSummary from(ImapClient.MailSummary m) {
            return new MessageSummary(m.uid(), m.from(), m.subject(), m.received(), m.seen());
        }
    }

    /**
     * List encrypted message summaries from IMAP inbox.
     * @param password the app password
     * @param limit maximum messages to fetch
     * @return list of message summaries
     */
    public List<MessageSummary> listEncryptedMessages(String password, int limit) throws Exception {
        if (!context.hasActiveProfile()) {
            throw new IllegalStateException("No active profile set");
        }

        Config cfg = context.zkStore().readJson("config.json", Config.class);
        if (cfg == null) {
            throw new IllegalStateException("Not initialized");
        }

        ImapClient.ImapConfig imapConfig = new ImapClient.ImapConfig(
                cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, password);

        ImapClient imap = ImapConnectionPool.getInstance().getConnection(imapConfig);
        try {
            List<ImapClient.MailSummary> msgs = imap.searchHeaderEquals("X-ZKEmails-Type", "msg", limit);
            return msgs.stream().map(MessageSummary::from).toList();
        } catch (Exception e) {
            // Invalidate connection on error so it will be recreated
            ImapConnectionPool.getInstance().invalidateConnection(imap);
            throw e;
        }
    }

    /**
     * List sent encrypted messages from IMAP sent folder.
     * @param password the app password
     * @param limit maximum messages to fetch
     * @return list of sent message summaries
     */
    public List<MessageSummary> listSentMessages(String password, int limit) throws Exception {
        if (!context.hasActiveProfile()) {
            throw new IllegalStateException("No active profile set");
        }

        Config cfg = context.zkStore().readJson("config.json", Config.class);
        if (cfg == null) {
            throw new IllegalStateException("Not initialized");
        }

        ImapClient.ImapConfig imapConfig = new ImapClient.ImapConfig(
                cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, password);

        ImapClient imap = ImapConnectionPool.getInstance().getSentConnection(imapConfig);
        try {
            List<ImapClient.MailSummary> msgs = imap.searchHeaderEquals("X-ZKEmails-Type", "msg", limit);
            return msgs.stream().map(MessageSummary::from).toList();
        } catch (Exception e) {
            ImapConnectionPool.getInstance().invalidateConnection(imap);
            throw e;
        }
    }

    // ===== Local Storage Methods =====

    /**
     * List threads from local storage (fast, uses index.json).
     */
    public List<InboxStore.ThreadSummary> listThreadsFromLocal(int limit) {
        if (!context.hasActiveProfile()) {
            return List.of();
        }
        return context.inboxStore().listThreads(limit);
    }

    /**
     * Get thread messages from local storage.
     */
    public ThreadMessages getThreadFromLocal(String threadId) {
        if (!context.hasActiveProfile() || threadId == null) {
            return null;
        }

        // Get current user email for sent message attribution
        String currentUserEmail = "me";
        try {
            Config cfg = context.zkStore().readJson("config.json", Config.class);
            if (cfg != null && cfg.email != null) {
                currentUserEmail = cfg.email;
            }
        } catch (Exception ignored) {
        }

        // Load all messages from inbox/{threadId}/*/message.json
        List<InboxStore.InboxMessage> inboxMsgs = context.inboxStore().loadThreadMessages(threadId);

        // Get base subject and participant for fallback search
        String baseSubject = "";
        String participant = null;
        if (!inboxMsgs.isEmpty()) {
            InboxStore.InboxMessage firstMsg = inboxMsgs.get(0);
            if (firstMsg.subject != null) {
                baseSubject = firstMsg.subject.replaceAll("(?i)^(Re:|Fwd:|Fw:)\\s*", "").trim();
            }
            participant = extractEmail(firstMsg.from);
        }

        // Search for sent messages: first by threadId, then fallback to subject+participant
        List<SentStore.SentMessage> sentMsgs = new ArrayList<>();
        try {
            // 1. Try ZKE thread ID first
            sentMsgs = context.sentStore().searchByZkeThreadId(threadId);

            // 2. Fallback: subject + filter by recipient matching participant
            if (sentMsgs.isEmpty() && !baseSubject.isEmpty() && participant != null) {
                List<SentStore.SentMessage> subjectMatches = context.sentStore().searchBySubject(baseSubject);
                for (var m : subjectMatches) {
                    if (m.toEmail != null && m.toEmail.equals(participant)) {
                        sentMsgs.add(m);
                    }
                }
            }
        } catch (Exception e) {
            sentMsgs = List.of();
        }

        // Merge and convert to DecryptedMessage
        List<DecryptedMessage> all = new ArrayList<>();
        for (var m : inboxMsgs) {
            List<AttachmentInfo> attachments = convertInboxAttachments(threadId, m.uid, m.attachments);
            all.add(new DecryptedMessage(
                m.uid,
                m.from,
                m.to,      // To recipients
                m.cc,      // CC recipients
                m.subject,
                new Date(m.receivedEpochSec * 1000),
                m.plaintext,
                true,
                attachments
            ));
        }
        for (var m : sentMsgs) {
            // Use the sent message's own threadId for attachment paths
            String sentThreadId = m.threadId != null ? m.threadId : threadId;
            List<AttachmentInfo> attachments = convertSentAttachments(sentThreadId, m.id, m.attachments);
            all.add(new DecryptedMessage(
                -1,  // No UID for local sent messages
                currentUserEmail,
                m.getRecipientsDisplay(),  // To: all visible recipients
                null,                      // CC: included in recipients display
                m.subject,
                new Date(m.sentAtEpochSec * 1000),
                m.plaintext,
                true,
                attachments
            ));
        }

        // Sort by date
        all.sort(Comparator.comparing(DecryptedMessage::received));

        return new ThreadMessages(baseSubject, all);
    }

    /**
     * Mark thread as read in local storage.
     */
    public void markThreadRead(String threadId) {
        if (context.hasActiveProfile() && threadId != null) {
            context.inboxStore().markThreadRead(threadId);
        }
    }

    /**
     * Check if thread is read in local storage.
     */
    public boolean isThreadRead(String threadId) {
        if (!context.hasActiveProfile() || threadId == null) {
            return true;  // Default to read if unknown
        }
        return context.inboxStore().isThreadRead(threadId);
    }

    /**
     * Find thread ID by message UID in local storage.
     */
    public String findThreadIdByUid(long uid) {
        if (!context.hasActiveProfile()) {
            return null;
        }
        return context.inboxStore().findThreadIdByUid(uid);
    }

    /**
     * Decrypt a single message.
     * @param password the app password
     * @param messageUid the message UID
     * @return decrypted message, or message with decryptionSuccessful=false if decryption failed
     */
    public DecryptedMessage decryptMessage(String password, long messageUid) throws Exception {
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
            ImapClient.MailSummary msg = imap.getMessageByUid(messageUid);
            if (msg == null) {
                throw new IllegalArgumentException("No message found with ID=" + messageUid);
            }

            String plaintext = decryptMessageInternal(imap, msg, cfg, myKeys);
            return new DecryptedMessage(
                msg.uid(),
                msg.from(),
                msg.subject(),
                msg.received(),
                plaintext,
                plaintext != null
            );
        } catch (Exception e) {
            ImapConnectionPool.getInstance().invalidateConnection(imap);
            throw e;
        }
    }

    /**
     * Get full thread with decrypted messages.
     * Uses two-layer correlation:
     * 1. Primary: X-ZKEmails-Thread-Id header (survives Gmail)
     * 2. Fallback: Subject + Participant filtering (for old messages)
     *
     * @param password the app password
     * @param messageUid the UID of any message in the thread
     * @param limit maximum messages to fetch
     * @return thread messages
     */
    public ThreadMessages getThread(String password, long messageUid, int limit) throws Exception {
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

        ImapClient inboxClient = ImapConnectionPool.getInstance().getConnection(imapConfig);

        // Get clicked message details for correlation
        ImapClient.MailSummary clickedMsg;
        String zkeThreadId;
        try {
            clickedMsg = inboxClient.getMessageByUid(messageUid);
            zkeThreadId = inboxClient.getZkeThreadId(messageUid);
            System.out.println("=== THREAD DEBUG: Clicked UID " + messageUid + ", ZKE-Thread-Id: " + zkeThreadId);
        } catch (Exception e) {
            ImapConnectionPool.getInstance().invalidateConnection(inboxClient);
            throw e;
        }

        if (clickedMsg == null) {
            return new ThreadMessages("", List.of());
        }

        // Get base subject and participant for fallback correlation
        String baseSubject = clickedMsg.subject();
        if (baseSubject != null) {
            baseSubject = baseSubject.replaceAll("(?i)^(Re:\\s*)+", "").trim();
        }
        String participant = extractEmail(clickedMsg.from());  // The other person in conversation
        System.out.println("=== THREAD DEBUG: Base subject: " + baseSubject + ", Participant: " + participant);

        List<ImapClient.MailSummary> inboxMsgs = new ArrayList<>();

        // 1. PRIMARY: Try custom ZKE thread ID (survives Gmail header stripping)
        if (zkeThreadId != null && !zkeThreadId.isEmpty()) {
            try {
                inboxMsgs = new ArrayList<>(inboxClient.searchByZkeThreadId(zkeThreadId, limit));
                System.out.println("=== THREAD DEBUG: Found " + inboxMsgs.size() + " messages via X-ZKEmails-Thread-Id");
            } catch (Exception e) {
                System.out.println("=== THREAD DEBUG: Failed to search by ZKE thread ID: " + e.getMessage());
            }
        }

        // 2. FALLBACK: Subject + Participant filtering (for old messages without thread ID)
        if (inboxMsgs.size() <= 1 && baseSubject != null && !baseSubject.isEmpty()) {
            try {
                List<ImapClient.MailSummary> subjectMatches = inboxClient.searchBySubject(baseSubject, limit);
                System.out.println("=== THREAD DEBUG: Found " + subjectMatches.size() + " messages via subject search");

                // Filter by participant to avoid subject collisions
                Set<Long> existingUids = new HashSet<>();
                for (var m : inboxMsgs) {
                    existingUids.add(m.uid());
                }

                for (var m : subjectMatches) {
                    if (existingUids.contains(m.uid())) continue;

                    String msgFrom = extractEmail(m.from());
                    // Include if:
                    // - Message is from the same participant (they sent to me), OR
                    // - Message is from me (viewing received messages I sent)
                    if (msgFrom.equals(participant) || msgFrom.equals(cfg.email)) {
                        inboxMsgs.add(m);
                        existingUids.add(m.uid());
                    }
                }
                System.out.println("=== THREAD DEBUG: " + inboxMsgs.size() + " messages after Subject+Participant filtering");
            } catch (Exception e) {
                System.out.println("=== THREAD DEBUG: Failed to search by subject: " + e.getMessage());
            }
        }

        // Ensure clicked message is in the list
        boolean hasClickedMsg = inboxMsgs.stream().anyMatch(m -> m.uid() == messageUid);
        if (!hasClickedMsg) {
            inboxMsgs.add(clickedMsg);
        }

        // Search local storage for sent messages
        List<SentStore.SentMessage> localSentMsgs = new ArrayList<>();
        try {
            // 1. Try ZKE thread ID first
            if (zkeThreadId != null && !zkeThreadId.isEmpty()) {
                localSentMsgs = context.sentStore().searchByZkeThreadId(zkeThreadId);
                System.out.println("=== THREAD DEBUG: Found " + localSentMsgs.size() + " local sent via ZKE-Thread-Id");
            }

            // 2. Fallback: subject + filter by recipient matching participant
            if (localSentMsgs.isEmpty() && baseSubject != null && !baseSubject.isEmpty()) {
                List<SentStore.SentMessage> subjectMatches = context.sentStore().searchBySubject(baseSubject);
                for (var m : subjectMatches) {
                    // Only include sent messages TO the same participant
                    if (m.toEmail != null && m.toEmail.equals(participant)) {
                        localSentMsgs.add(m);
                    }
                }
                System.out.println("=== THREAD DEBUG: Found " + localSentMsgs.size() + " local sent via Subject+Participant");
            }
        } catch (Exception e) {
            System.out.println("=== THREAD DEBUG: Failed to search local sent: " + e.getMessage());
        }

        // Decrypt inbox messages
        List<DecryptedMessage> decrypted = new ArrayList<>();
        Set<String> processedMessageIds = new HashSet<>();

        for (var m : inboxMsgs) {
            String msgId = getMessageIdSafe(inboxClient, m.uid());
            if (msgId != null) {
                processedMessageIds.add(msgId);
            }

            String plaintext = decryptMessageInternal(inboxClient, m, cfg, myKeys);
            decrypted.add(new DecryptedMessage(
                m.uid(),
                m.from(),
                m.subject(),
                m.received(),
                plaintext,
                plaintext != null
            ));
        }

        // Add local sent messages (already have plaintext)
        for (var sent : localSentMsgs) {
            // Skip if we already processed this message
            if (sent.messageId != null && processedMessageIds.contains(sent.messageId)) {
                continue;
            }

            decrypted.add(new DecryptedMessage(
                0,  // No UID for local messages
                cfg.email,  // From is current user
                sent.subject,
                Date.from(Instant.ofEpochSecond(sent.sentAtEpochSec)),
                sent.plaintext,  // Already have plaintext
                true
            ));
        }

        // Sort by date ascending (oldest first)
        decrypted.sort(Comparator.comparing(m -> m.received() != null ? m.received() : new Date(0)));

        if (decrypted.isEmpty()) {
            return new ThreadMessages("", List.of());
        }

        // Get base subject from first message
        String finalSubject = decrypted.get(0).subject();
        if (finalSubject != null) {
            finalSubject = finalSubject.replaceAll("(?i)^(Re:\\s*)+", "").trim();
        } else {
            finalSubject = "";
        }

        return new ThreadMessages(finalSubject, decrypted);
    }

    /**
     * Safely get Message-ID without throwing exceptions.
     */
    private String getMessageIdSafe(ImapClient client, long uid) {
        try {
            return client.getMessageId(uid);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Send an encrypted message.
     * @param password the app password
     * @param toEmail the recipient email
     * @param subject the message subject
     * @param body the message body
     * @return send result
     */
    public SendResult sendMessage(String password, String toEmail, String subject, String body) throws Exception {
        return sendMessage(password, toEmail, subject, body, null, null, null);
    }

    /**
     * Send an encrypted message with threading headers.
     * @param password the app password
     * @param toEmail the recipient email
     * @param subject the message subject
     * @param body the message body
     * @param inReplyTo the Message-ID being replied to (for threading)
     * @param references the References header (for threading)
     * @param threadId the custom thread ID (null to generate a new one for new threads)
     * @return send result
     */
    public SendResult sendMessage(String password, String toEmail, String subject, String body,
                                   String inReplyTo, String references, String threadId) throws Exception {
        if (!context.hasActiveProfile()) {
            return new SendResult(false, "No active profile set");
        }

        Config cfg = context.zkStore().readJson("config.json", Config.class);
        IdentityKeys.KeyBundle myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);

        if (cfg == null) {
            return new SendResult(false, "Not initialized. Run: zke init --email <your-email>");
        }
        if (myKeys == null) {
            return new SendResult(false, "Missing keys.json. Re-run init.");
        }

        if (toEmail == null || toEmail.isBlank()) {
            return new SendResult(false, "Recipient is required");
        }
        if (subject == null || subject.isBlank()) {
            return new SendResult(false, "Subject is required");
        }
        if (body == null || body.isBlank()) {
            return new SendResult(false, "Cannot send empty message");
        }

        ContactsStore.Contact c = context.contacts().get(toEmail);
        if (c == null || c.x25519PublicB64 == null || c.fingerprintHex == null) {
            return new SendResult(false, "No pinned X25519 key for contact: " + toEmail +
                    ". Run: zke sync-ack (or ack invi if they invited you).");
        }

        // Generate thread ID for new messages, use provided one for replies
        String effectiveThreadId = threadId;
        if (effectiveThreadId == null || effectiveThreadId.isBlank()) {
            effectiveThreadId = UUID.randomUUID().toString();
        }

        String messageId;
        try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(
                cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, password))) {
            messageId = smtp.sendEncryptedMessage(cfg.email, toEmail, subject, body, myKeys,
                    c.fingerprintHex, c.x25519PublicB64, inReplyTo, references, effectiveThreadId);
        }

        // Save sent message locally for thread view display
        try {
            SentStore.SentMessage sentMsg = new SentStore.SentMessage(
                    UUID.randomUUID().toString(),
                    messageId,
                    toEmail,
                    subject,
                    body,
                    inReplyTo,
                    references,
                    effectiveThreadId,
                    Instant.now().getEpochSecond()
            );
            context.sentStore().save(sentMsg);
        } catch (Exception e) {
            log.warn("Sent message but failed to save locally: {}", e.getMessage());
        }

        return new SendResult(true, "Encrypted message sent to " + toEmail);
    }

    /**
     * Maximum attachment size: 25MB
     */
    public static final long MAX_ATTACHMENT_SIZE = 25 * 1024 * 1024;

    /**
     * Send an encrypted message with attachments.
     * @param password the app password
     * @param toEmail the recipient email
     * @param subject the message subject
     * @param body the message body
     * @param attachmentPaths list of file paths to attach
     * @param inReplyTo the Message-ID being replied to (for threading)
     * @param references the References header (for threading)
     * @param threadId the custom thread ID (null to generate a new one)
     * @return send result
     */
    public SendResult sendMessageWithAttachments(String password, String toEmail, String subject, String body,
                                                  List<Path> attachmentPaths,
                                                  String inReplyTo, String references, String threadId) throws Exception {
        if (!context.hasActiveProfile()) {
            return new SendResult(false, "No active profile set");
        }

        Config cfg = context.zkStore().readJson("config.json", Config.class);
        IdentityKeys.KeyBundle myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);

        if (cfg == null) {
            return new SendResult(false, "Not initialized. Run: zke init --email <your-email>");
        }
        if (myKeys == null) {
            return new SendResult(false, "Missing keys.json. Re-run init.");
        }

        if (toEmail == null || toEmail.isBlank()) {
            return new SendResult(false, "Recipient is required");
        }
        if (subject == null || subject.isBlank()) {
            return new SendResult(false, "Subject is required");
        }
        if (body == null || body.isBlank()) {
            return new SendResult(false, "Cannot send empty message");
        }

        ContactsStore.Contact c = context.contacts().get(toEmail);
        if (c == null || c.x25519PublicB64 == null || c.fingerprintHex == null) {
            return new SendResult(false, "No pinned X25519 key for contact: " + toEmail +
                    ". Run: zke sync-ack (or ack invi if they invited you).");
        }

        // Build attachment inputs
        List<CryptoBox.AttachmentInput> attachments = new ArrayList<>();
        if (attachmentPaths != null) {
            for (Path path : attachmentPaths) {
                try {
                    CryptoBox.AttachmentInput att = CryptoBox.AttachmentInput.fromFile(path);
                    if (att.data().length > MAX_ATTACHMENT_SIZE) {
                        return new SendResult(false, "Attachment too large: " + path.getFileName() +
                                " (" + (att.data().length / 1024 / 1024) + "MB > 25MB limit)");
                    }
                    attachments.add(att);
                } catch (Exception e) {
                    return new SendResult(false, "Failed to read attachment: " + path + " - " + e.getMessage());
                }
            }
        }

        // Generate thread ID for new messages
        String effectiveThreadId = threadId;
        if (effectiveThreadId == null || effectiveThreadId.isBlank()) {
            effectiveThreadId = UUID.randomUUID().toString();
        }

        String messageId;
        try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(
                cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, password))) {
            if (attachments.isEmpty()) {
                messageId = smtp.sendEncryptedMessage(cfg.email, toEmail, subject, body, myKeys,
                        c.fingerprintHex, c.x25519PublicB64, inReplyTo, references, effectiveThreadId);
            } else {
                messageId = smtp.sendEncryptedMessageWithAttachments(cfg.email, toEmail, subject, body,
                        attachments, myKeys, c.fingerprintHex, c.x25519PublicB64, inReplyTo, references, effectiveThreadId);
            }
        }

        // Save sent message locally
        try {
            String msgLocalId = UUID.randomUUID().toString();
            SentStore.SentMessage sentMsg = new SentStore.SentMessage(
                    msgLocalId,
                    messageId,
                    toEmail,
                    subject,
                    body,
                    inReplyTo,
                    references,
                    effectiveThreadId,
                    Instant.now().getEpochSecond()
            );

            // Save attachment metadata and files locally
            if (!attachments.isEmpty()) {
                List<SentStore.AttachmentMeta> attachmentMetas = new ArrayList<>();
                for (var att : attachments) {
                    String localPath = context.sentStore().saveAttachment(effectiveThreadId, msgLocalId,
                            att.filename(), att.data());
                    attachmentMetas.add(new SentStore.AttachmentMeta(
                            att.filename(),
                            att.contentType(),
                            att.data().length,
                            localPath
                    ));
                }
                sentMsg.attachments = attachmentMetas;
            }

            context.sentStore().save(sentMsg);
        } catch (Exception e) {
            log.warn("Sent message but failed to save locally: {}", e.getMessage());
        }

        String attachmentInfo = attachments.isEmpty() ? "" : " with " + attachments.size() + " attachment(s)";
        return new SendResult(true, "Encrypted message sent to " + toEmail + attachmentInfo);
    }

    // ==================== V2 Multi-Recipient Send ====================

    /**
     * Send an encrypted v2 message to multiple recipients.
     *
     * @param password the app password
     * @param recipients the To/CC/BCC recipients
     * @param subject the message subject
     * @param body the message body
     * @param inReplyTo Message-ID of message being replied to (null for new)
     * @param references thread reference chain (null for new)
     * @param threadId custom thread ID (null to generate new one)
     * @return result with message IDs and any failed recipients
     */
    public MultiSendResult sendMultiRecipientMessage(
            String password,
            MultiRecipientInput recipients,
            String subject, String body,
            String inReplyTo, String references, String threadId) throws Exception {

        if (!context.hasActiveProfile()) {
            return new MultiSendResult(false, "No active profile set", null, null, null);
        }

        Config cfg = context.zkStore().readJson("config.json", Config.class);
        IdentityKeys.KeyBundle myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);

        if (cfg == null) {
            return new MultiSendResult(false, "Not initialized. Run: zke init --email <your-email>", null, null, null);
        }
        if (myKeys == null) {
            return new MultiSendResult(false, "Missing keys.json. Re-run init.", null, null, null);
        }

        if (recipients == null || recipients.allRecipients().isEmpty()) {
            return new MultiSendResult(false, "At least one recipient required", null, null, null);
        }
        if (subject == null || subject.isBlank()) {
            return new MultiSendResult(false, "Subject is required", null, null, null);
        }
        if (body == null || body.isBlank()) {
            return new MultiSendResult(false, "Cannot send empty message", null, null, null);
        }

        // Validate all recipients have keys and build key map
        Map<String, String> recipientFpToX25519 = new LinkedHashMap<>();
        Map<String, String> emailToFp = new LinkedHashMap<>();
        List<String> failedRecipients = new ArrayList<>();

        for (String email : recipients.allRecipients()) {
            ContactsStore.Contact c = context.contacts().get(email);
            if (c == null || c.x25519PublicB64 == null || c.fingerprintHex == null) {
                failedRecipients.add(email);
            } else {
                recipientFpToX25519.put(c.fingerprintHex, c.x25519PublicB64);
                emailToFp.put(email, c.fingerprintHex);
            }
        }

        if (!failedRecipients.isEmpty()) {
            return new MultiSendResult(false,
                "Missing keys for contacts: " + String.join(", ", failedRecipients),
                null, null, failedRecipients);
        }

        // Generate thread ID for new messages
        String effectiveThreadId = threadId;
        if (effectiveThreadId == null || effectiveThreadId.isBlank()) {
            effectiveThreadId = UUID.randomUUID().toString();
        }

        // Get primary To recipient for AAD binding
        String primaryTo = recipients.toEmails() != null && !recipients.toEmails().isEmpty()
            ? recipients.toEmails().get(0) : recipients.allRecipients().get(0);

        // Encrypt for visible recipients (To + CC)
        Map<String, String> visibleRecipientKeys = new LinkedHashMap<>();
        for (String email : recipients.visibleRecipients()) {
            String fp = emailToFp.get(email);
            visibleRecipientKeys.put(fp, recipientFpToX25519.get(fp));
        }

        String mainMessageId = null;
        List<String> bccMessageIds = new ArrayList<>();

        try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(
                cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, password))) {

            // Send main email to To/CC recipients if any
            if (!recipients.visibleRecipients().isEmpty()) {
                CryptoBox.EncryptedPayloadV2 mainPayload = CryptoBox.encryptToMultipleRecipients(
                    cfg.email, primaryTo, subject, body, myKeys, visibleRecipientKeys
                );

                mainMessageId = smtp.sendEncryptedMultiRecipientMessage(
                    cfg.email,
                    recipients.toEmails(),
                    recipients.ccEmails(),
                    subject,
                    mainPayload,
                    inReplyTo, references, effectiveThreadId
                );
            }

            // Send separate emails to BCC recipients
            if (recipients.bccEmails() != null) {
                for (String bccEmail : recipients.bccEmails()) {
                    String bccFp = emailToFp.get(bccEmail);
                    String bccX25519 = recipientFpToX25519.get(bccFp);

                    // Encrypt separately for BCC privacy
                    CryptoBox.EncryptedPayloadV2 bccPayload = CryptoBox.encryptForBccRecipient(
                        cfg.email, bccEmail, subject, body, myKeys, bccFp, bccX25519
                    );

                    String bccMsgId = smtp.sendEncryptedBccMessage(
                        cfg.email,
                        bccEmail,
                        recipients.toEmails(),  // Show To addresses for context
                        subject,
                        bccPayload,
                        inReplyTo, references, effectiveThreadId
                    );
                    bccMessageIds.add(bccMsgId);
                }
            }
        }

        // Save sent message locally
        try {
            SentStore.SentMessage sentMsg = new SentStore.SentMessage(
                    UUID.randomUUID().toString(),
                    mainMessageId != null ? mainMessageId : (bccMessageIds.isEmpty() ? null : bccMessageIds.get(0)),
                    primaryTo,  // Keep for backward compat
                    subject,
                    body,
                    inReplyTo,
                    references,
                    effectiveThreadId,
                    java.time.Instant.now().getEpochSecond()
            );
            // Set multi-recipient fields
            sentMsg.toEmails = recipients.toEmails() != null ? new ArrayList<>(recipients.toEmails()) : null;
            sentMsg.ccEmails = recipients.ccEmails() != null ? new ArrayList<>(recipients.ccEmails()) : null;
            sentMsg.bccEmails = recipients.bccEmails() != null ? new ArrayList<>(recipients.bccEmails()) : null;
            context.sentStore().save(sentMsg);
        } catch (Exception e) {
            log.warn("Sent message but failed to save locally: {}", e.getMessage());
        }

        int totalRecipients = recipients.allRecipients().size();
        return new MultiSendResult(true,
            "Encrypted message sent to " + totalRecipients + " recipient(s)",
            mainMessageId, bccMessageIds, null);
    }

    /**
     * Reply to a message.
     * @param password the app password
     * @param originalMessageUid the UID of the message to reply to
     * @param replyBody the reply body
     * @return send result
     */
    public SendResult replyToMessage(String password, long originalMessageUid, String replyBody) throws Exception {
        if (!context.hasActiveProfile()) {
            return new SendResult(false, "No active profile set");
        }

        Config cfg = context.zkStore().readJson("config.json", Config.class);
        IdentityKeys.KeyBundle myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);

        if (cfg == null || myKeys == null) {
            return new SendResult(false, "Not initialized");
        }

        ImapClient.ImapConfig imapConfig = new ImapClient.ImapConfig(
                cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, password);

        ImapClient imap = ImapConnectionPool.getInstance().getConnection(imapConfig);
        try {
            ImapClient.MailSummary msg = imap.getMessageByUid(originalMessageUid);
            if (msg == null) {
                return new SendResult(false, "No message found with ID=" + originalMessageUid);
            }

            String replyToEmail = extractEmail(msg.from());
            if (replyToEmail == null) {
                return new SendResult(false, "Could not extract email from: " + msg.from());
            }

            ContactsStore.Contact contact = context.contacts().get(replyToEmail);
            if (contact == null || contact.x25519PublicB64 == null || contact.fingerprintHex == null) {
                return new SendResult(false, "No pinned X25519 key for contact: " + replyToEmail);
            }

            String replySubject = msg.subject();
            if (replySubject != null && !replySubject.toLowerCase().startsWith("re:")) {
                replySubject = "Re: " + replySubject;
            }

            String originalMessageId = imap.getMessageId(originalMessageUid);
            String originalReferences = imap.getReferences(originalMessageUid);

            // Get threadId: first try IMAP header, then local storage, then generate fallback
            String threadId = imap.getZkeThreadId(originalMessageUid);
            if (threadId == null || threadId.isEmpty()) {
                // Check local storage for the threadId
                String localThreadId = context.inboxStore().findThreadIdByUid(originalMessageUid);
                if (localThreadId != null) {
                    threadId = localThreadId;
                } else {
                    // Generate same fallback as sync: subject:sender
                    String baseSubject = msg.subject() != null
                        ? msg.subject().replaceAll("(?i)^(Re:|Fwd:|Fw:)\\s*", "").trim()
                        : "";
                    threadId = baseSubject + ":" + (replyToEmail != null ? replyToEmail : "unknown");
                }
            }

            String newReferences = originalReferences != null ? originalReferences + " " : "";
            if (originalMessageId != null) {
                newReferences += originalMessageId;
            }
            newReferences = newReferences.trim();
            if (newReferences.isEmpty()) newReferences = null;

            try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(
                    cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, password))) {
                smtp.sendEncryptedMessage(
                        cfg.email,
                        replyToEmail,
                        replySubject,
                        replyBody,
                        myKeys,
                        contact.fingerprintHex,
                        contact.x25519PublicB64,
                        originalMessageId,
                        newReferences,
                        threadId
                );
            }

            return new SendResult(true, "Reply sent to " + replyToEmail);
        } catch (Exception e) {
            ImapConnectionPool.getInstance().invalidateConnection(imap);
            throw e;
        }
    }

    /**
     * Get reply context for a message (recipient, subject, quoted text, thread ID).
     */
    public record ReplyContext(
        String toEmail,           // Original sender (for Reply)
        List<String> allToEmails, // All To recipients (for Reply All)
        List<String> ccEmails,    // CC recipients (for Reply All)
        String subject,
        String quotedBody,
        String originalMessageId,
        String references,
        String threadId  // Custom ZKE thread ID for correlation
    ) {
        // Convenience constructor for backward compatibility
        public ReplyContext(String toEmail, String subject, String quotedBody,
                           String originalMessageId, String references, String threadId) {
            this(toEmail, null, null, subject, quotedBody, originalMessageId, references, threadId);
        }
    }

    /**
     * Get reply context for a message.
     * @param password the app password
     * @param messageUid the UID of the message to reply to
     * @return reply context
     */
    public ReplyContext getReplyContext(String password, long messageUid) throws Exception {
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
            ImapClient.MailSummary msg = imap.getMessageByUid(messageUid);
            if (msg == null) {
                throw new IllegalArgumentException("No message found with ID=" + messageUid);
            }

            String plaintext = decryptMessageInternal(imap, msg, cfg, myKeys);
            String toEmail = extractEmail(msg.from());

            String replySubject = msg.subject();
            if (replySubject != null && !replySubject.toLowerCase().startsWith("re:")) {
                replySubject = "Re: " + replySubject;
            }

            String quotedBody = quoteText(plaintext);

            String originalMessageId = imap.getMessageId(messageUid);
            String originalReferences = imap.getReferences(messageUid);

            // Get threadId: first try IMAP header, then local storage, then generate fallback
            String threadId = imap.getZkeThreadId(messageUid);
            if (threadId == null || threadId.isEmpty()) {
                // Check local storage for the threadId
                String localThreadId = context.inboxStore().findThreadIdByUid(messageUid);
                if (localThreadId != null) {
                    threadId = localThreadId;
                } else {
                    // Generate same fallback as sync: subject:sender
                    String baseSubject = msg.subject() != null
                        ? msg.subject().replaceAll("(?i)^(Re:|Fwd:|Fw:)\\s*", "").trim()
                        : "";
                    threadId = baseSubject + ":" + (toEmail != null ? toEmail : "unknown");
                }
            }

            String newReferences = originalReferences != null ? originalReferences + " " : "";
            if (originalMessageId != null) {
                newReferences += originalMessageId;
            }
            newReferences = newReferences.trim();
            if (newReferences.isEmpty()) newReferences = null;

            // Extract To and CC headers for Reply All
            Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(messageUid);
            String toHeader = first(hdrs, "To");
            String ccHeader = first(hdrs, "Cc");
            List<String> allToEmails = parseEmailList(toHeader);
            List<String> ccEmails = parseEmailList(ccHeader);

            return new ReplyContext(toEmail, allToEmails, ccEmails, replySubject, quotedBody,
                    originalMessageId, newReferences, threadId);
        } catch (Exception e) {
            ImapConnectionPool.getInstance().invalidateConnection(imap);
            throw e;
        }
    }

    /**
     * Get the current user's email.
     */
    public String getCurrentUserEmail() throws Exception {
        if (!context.hasActiveProfile()) {
            return null;
        }
        Config cfg = context.zkStore().readJson("config.json", Config.class);
        return cfg != null ? cfg.email : null;
    }

    private String decryptMessageInternal(ImapClient imap, ImapClient.MailSummary msg,
                                          Config cfg, IdentityKeys.KeyBundle myKeys) {
        try {
            System.out.println("=== DECRYPT DEBUG: Attempting to decrypt UID " + msg.uid() + " subject=" + msg.subject());
            Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(msg.uid());
            System.out.println("=== DECRYPT DEBUG: Fetched " + hdrs.size() + " headers");

            // Check version header for v2 multi-recipient messages
            String version = first(hdrs, "X-ZKEmails-Version");
            System.out.println("=== DECRYPT DEBUG: Message version='" + version + "' (is v2: " + "2".equals(version) + ")");

            String fromEmail = extractEmail(msg.from());
            System.out.println("=== DECRYPT DEBUG: fromEmail=" + fromEmail);
            ContactsStore.Contact contact = context.contacts().get(fromEmail);
            if (contact == null || contact.ed25519PublicB64 == null) {
                System.out.println("=== DECRYPT DEBUG: No contact found for " + fromEmail);
                return null;
            }
            System.out.println("=== DECRYPT DEBUG: Contact found, attempting decryption");

            // V2 multi-recipient message: fetch JSON payload from MIME
            if ("2".equals(version)) {
                return decryptV2Message(imap, msg, cfg, myKeys, contact);
            }

            // V1 header-based message (default/fallback)
            return decryptV1Message(hdrs, msg, cfg, myKeys, fromEmail, contact);
        } catch (Exception e) {
            System.out.println("=== DECRYPT DEBUG: Exception: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Decrypt a v1 header-based encrypted message.
     */
    private String decryptV1Message(Map<String, List<String>> hdrs, ImapClient.MailSummary msg,
                                     Config cfg, IdentityKeys.KeyBundle myKeys,
                                     String fromEmail, ContactsStore.Contact contact) throws Exception {
        String ephemX25519PubB64 = first(hdrs, "X-ZKEmails-Ephem-X25519");
        String wrappedKeyB64 = first(hdrs, "X-ZKEmails-WrappedKey");
        String wrappedKeyNonceB64 = first(hdrs, "X-ZKEmails-WrappedKey-Nonce");
        String msgNonceB64 = first(hdrs, "X-ZKEmails-Nonce");
        String ciphertextB64 = first(hdrs, "X-ZKEmails-Ciphertext");
        String sigB64 = first(hdrs, "X-ZKEmails-Sig");
        String recipientFpHex = first(hdrs, "X-ZKEmails-Recipient-Fp");

        System.out.println("=== DECRYPT DEBUG (v1): ephemKey=" + (ephemX25519PubB64 != null) +
            " wrappedKey=" + (wrappedKeyB64 != null) + " ciphertext=" + (ciphertextB64 != null));

        CryptoBox.EncryptedPayload payload = new CryptoBox.EncryptedPayload(
                ephemX25519PubB64, wrappedKeyB64, wrappedKeyNonceB64,
                msgNonceB64, ciphertextB64, sigB64, recipientFpHex
        );

        String result = CryptoBox.decryptForRecipient(
                fromEmail,
                cfg.email,
                msg.subject(),
                payload,
                myKeys.x25519PrivateB64(),
                contact.ed25519PublicB64
        );
        System.out.println("=== DECRYPT DEBUG (v1): Decryption " + (result != null ? "succeeded" : "returned null"));
        return result;
    }

    /**
     * Decrypt a v2 multi-recipient encrypted message.
     */
    private String decryptV2Message(ImapClient imap, ImapClient.MailSummary msg,
                                     Config cfg, IdentityKeys.KeyBundle myKeys,
                                     ContactsStore.Contact contact) throws Exception {
        // Fetch the JSON payload from MIME multipart
        CryptoBox.EncryptedPayloadV2 payload = imap.fetchJsonPayload(msg.uid());
        if (payload == null) {
            System.out.println("=== DECRYPT DEBUG (v2): Failed to fetch JSON payload");
            return null;
        }

        System.out.println("=== DECRYPT DEBUG (v2): Fetched v2 payload with " + payload.recipients().size() + " recipients");

        // Get my fingerprint from keys
        String myFpHex = myKeys.fingerprintHex();
        System.out.println("=== DECRYPT DEBUG (v2): My fingerprint: " + myFpHex);
        System.out.println("=== DECRYPT DEBUG (v2): Recipient fingerprints in payload:");
        for (CryptoBox.RecipientKey rk : payload.recipients()) {
            System.out.println("  - " + rk.fpHex());
        }

        // Extract primary To recipient from message headers for AAD binding
        String primaryTo = extractPrimaryTo(imap, msg.uid());
        System.out.println("=== DECRYPT DEBUG (v2): Extracted primaryTo from headers: " + primaryTo);
        if (primaryTo == null) {
            primaryTo = cfg.email;  // Fallback to self
            System.out.println("=== DECRYPT DEBUG (v2): Using fallback primaryTo: " + primaryTo);
        }

        String fromEmail = extractEmail(msg.from());
        System.out.println("=== DECRYPT DEBUG (v2): fromEmail=" + fromEmail + ", primaryTo=" + primaryTo + ", subject=" + msg.subject());

        try {
            String result = CryptoBox.decryptFromMultipleRecipientMessage(
                    fromEmail,
                    primaryTo,
                    msg.subject(),
                    payload,
                    myFpHex,
                    myKeys.x25519PrivateB64(),
                    contact.ed25519PublicB64
            );
            System.out.println("=== DECRYPT DEBUG (v2): Decryption " + (result != null ? "succeeded" : "returned null"));
            return result;
        } catch (Exception e) {
            System.out.println("=== DECRYPT DEBUG (v2): Decryption failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Extract primary To recipient from message headers.
     */
    private String extractPrimaryTo(ImapClient imap, long uid) {
        try {
            Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(uid);
            String toHeader = first(hdrs, "To");
            if (toHeader == null) return null;
            // Take first email from To header
            return extractEmail(toHeader.split(",")[0]);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractEmail(String from) {
        if (from == null) return null;
        int start = from.indexOf('<');
        int end = from.indexOf('>');
        if (start >= 0 && end > start) {
            return from.substring(start + 1, end).trim();
        }
        return from.trim();
    }

    /**
     * Parse a comma-separated email list header into individual addresses.
     */
    private List<String> parseEmailList(String header) {
        if (header == null || header.isBlank()) return List.of();
        return java.util.Arrays.stream(header.split(","))
            .map(String::trim)
            .map(this::extractEmail)
            .filter(java.util.Objects::nonNull)
            .filter(s -> !s.isBlank())
            .toList();
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

    private static String first(Map<String, List<String>> map, String key) {
        List<String> v = map.get(key);
        return (v != null && !v.isEmpty()) ? v.get(0) : null;
    }

    /**
     * Convert inbox attachment metadata to AttachmentInfo for GUI.
     */
    private List<AttachmentInfo> convertInboxAttachments(String threadId, long uid,
                                                          List<InboxStore.AttachmentMeta> metas) {
        if (metas == null || metas.isEmpty()) {
            return List.of();
        }

        List<AttachmentInfo> result = new ArrayList<>();
        for (var meta : metas) {
            Path fullPath = context.inboxStore().getAttachmentFullPath(threadId, uid, meta.localPath);
            boolean exists = context.inboxStore().attachmentExists(threadId, uid, meta.localPath);
            result.add(new AttachmentInfo(
                    meta.filename,
                    meta.contentType,
                    meta.size,
                    exists,
                    fullPath
            ));
        }
        return result;
    }

    /**
     * Convert sent attachment metadata to AttachmentInfo for GUI.
     */
    private List<AttachmentInfo> convertSentAttachments(String threadId, String id,
                                                         List<SentStore.AttachmentMeta> metas) {
        if (metas == null || metas.isEmpty()) {
            return List.of();
        }

        List<AttachmentInfo> result = new ArrayList<>();
        for (var meta : metas) {
            Path fullPath = context.sentStore().getAttachmentFullPath(threadId, id, meta.localPath);
            boolean exists = context.sentStore().attachmentExists(threadId, id, meta.localPath);
            result.add(new AttachmentInfo(
                    meta.filename,
                    meta.contentType,
                    meta.size,
                    exists,
                    fullPath
            ));
        }
        return result;
    }
}
