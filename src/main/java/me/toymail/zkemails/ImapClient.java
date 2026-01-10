package me.toymail.zkemails;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.HeaderTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import me.toymail.zkemails.crypto.CryptoBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public final class ImapClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ImapClient.class);

    public record ImapConfig(String host, int port, boolean ssl, String username, String password) {}

    public record MailSummary(int msgNum, long uid, boolean seen, Date received, String from, String subject) {}

    private final Store store;
    private final Folder inbox;
    private final UIDFolder uidFolder;

    private ImapClient(Store store, Folder inbox) {
        this.store = store;
        this.inbox = inbox;
        this.uidFolder = (UIDFolder) inbox;
    }

    private static final int SEARCH_DAYS_LIMIT = 30;

    // Common sent folder names for different email providers
    private static final String[] SENT_FOLDER_NAMES = {
        "[Gmail]/Sent Mail",  // Gmail
        "Sent",               // Generic/Outlook
        "Sent Items",         // Exchange/Outlook
        "Sent Messages",      // Yahoo
        "INBOX.Sent"          // Some IMAP servers
    };

    public static ImapClient connect(ImapConfig cfg) throws MessagingException {
        return connect(cfg, "INBOX");
    }

    public static ImapClient connect(ImapConfig cfg, String folderName) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", cfg.ssl() ? "imaps" : "imap");

        props.put("mail.imaps.ssl.enable", String.valueOf(cfg.ssl()));
        props.put("mail.imaps.ssl.checkserveridentity", "true");
        props.put("mail.imaps.connectiontimeout", "15000");
        props.put("mail.imaps.timeout", "120000");
        props.put("mail.imaps.writetimeout", "60000");
        props.put("mail.imaps.usesocketchannels", "true");

        props.put("mail.imap.connectiontimeout", "15000");
        props.put("mail.imap.timeout", "120000");
        props.put("mail.imap.writetimeout", "60000");

        Session session = Session.getInstance(props);
        // session.setDebug(true);

        Store store = session.getStore(cfg.ssl() ? "imaps" : "imap");
        store.connect(cfg.host(), cfg.port(), cfg.username(), cfg.password());
        log.info("Store connected: {}", store.getClass().getSimpleName());

        Folder folder = store.getFolder(folderName);
        folder.open(Folder.READ_ONLY);
        log.info("Opened {} with messages", folderName);
        if (!(folder instanceof UIDFolder)) {
            folder.close(false);
            store.close();
            throw new MessagingException("Folder does not support UIDFolder; cannot use stable UIDs.");
        }

        return new ImapClient(store, folder);
    }

    /**
     * Connect to the sent folder (tries common folder names).
     */
    public static ImapClient connectToSent(ImapConfig cfg) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", cfg.ssl() ? "imaps" : "imap");
        props.put("mail.imaps.ssl.enable", String.valueOf(cfg.ssl()));
        props.put("mail.imaps.ssl.checkserveridentity", "true");
        props.put("mail.imaps.connectiontimeout", "15000");
        props.put("mail.imaps.timeout", "120000");
        props.put("mail.imaps.writetimeout", "60000");
        props.put("mail.imaps.usesocketchannels", "true");

        Session session = Session.getInstance(props);
        Store store = session.getStore(cfg.ssl() ? "imaps" : "imap");
        store.connect(cfg.host(), cfg.port(), cfg.username(), cfg.password());
        log.info("Store connected: {}", store.getClass().getSimpleName());

        // Try each sent folder name
        for (String sentFolderName : SENT_FOLDER_NAMES) {
            try {
                Folder folder = store.getFolder(sentFolderName);
                if (folder.exists()) {
                    folder.open(Folder.READ_ONLY);
                    log.info("Opened sent folder: {}", sentFolderName);
                    if (folder instanceof UIDFolder) {
                        return new ImapClient(store, folder);
                    }
                    folder.close(false);
                }
            } catch (MessagingException e) {
                log.debug("Sent folder '{}' not available: {}", sentFolderName, e.getMessage());
            }
        }

        store.close();
        throw new MessagingException("Could not find sent folder. Tried: " + String.join(", ", SENT_FOLDER_NAMES));
    }

    @Override
    public void close() throws MessagingException {
        if (inbox != null && inbox.isOpen()) inbox.close(false);
        if (store != null && store.isConnected()) store.close();
    }

    /**
     * Check if the connection is still valid.
     */
    public boolean isConnected() {
        try {
            return store != null && store.isConnected() && inbox != null && inbox.isOpen();
        } catch (Exception e) {
            return false;
        }
    }

    public List<MailSummary> listInboxLatest(int limit) throws MessagingException {
        int total = inbox.getMessageCount();
        if (total <= 0) return List.of();

        int end = total;
        int start = Math.max(1, total - limit + 1);
        Message[] msgs = inbox.getMessages(start, end);

        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.FLAGS);
        inbox.fetch(msgs, fp);

        List<MailSummary> out = new ArrayList<>(msgs.length);
        for (Message m : msgs) {
            out.add(toSummary(m));
        }
        out.sort(Comparator.comparingInt(MailSummary::msgNum).reversed());
        return out;
    }

    public List<MailSummary> searchHeaderEquals(String headerName, String headerValue, int limit) throws MessagingException {
        Date sinceDate = daysAgo(SEARCH_DAYS_LIMIT);
        SearchTerm term = new AndTerm(
                new ReceivedDateTerm(ComparisonTerm.GE, sinceDate),
                new HeaderTerm(headerName, headerValue)
        );
        log.debug("Searching for header {}={} since {}", headerName, headerValue, sinceDate);
        Message[] found = inbox.search(term);
        return summarize(found, limit);
    }

    public List<MailSummary> searchUnread(int limit) throws MessagingException {
        Message[] found = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        return summarize(found, limit);
    }

    public List<MailSummary> searchUnreadWithHeader(String headerName, String headerValue, int limit) throws MessagingException {
        SearchTerm term = new AndTerm(
                new FlagTerm(new Flags(Flags.Flag.SEEN), false),
                new HeaderTerm(headerName, headerValue)
        );
        Message[] found = inbox.search(term);
        return summarize(found, limit);
    }

    public List<MailSummary> searchByInviteId(String inviteId, int limit) throws MessagingException {
        Date sinceDate = daysAgo(SEARCH_DAYS_LIMIT);
        SearchTerm term = new AndTerm(new SearchTerm[]{
                new ReceivedDateTerm(ComparisonTerm.GE, sinceDate),
                new HeaderTerm("X-ZKEmails-Type", "invite"),
                new HeaderTerm("X-ZKEmails-Invite-Id", inviteId)
        });
        log.debug("Searching for unread invite-id={} since {}", inviteId, sinceDate);
        Message[] found = inbox.search(term);
        log.info("Found {} unread invites with invite-id={}", found.length, inviteId);
        return summarize(found, limit);
    }

    private static Date daysAgo(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -days);
        return cal.getTime();
    }

    public Map<String, List<String>> fetchAllHeadersByUid(long uid) throws MessagingException {
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) throw new MessagingException("No message found for UID=" + uid);

        @SuppressWarnings("unchecked")
        Enumeration<Header> headers = m.getAllHeaders();

        Map<String, List<String>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        while (headers.hasMoreElements()) {
            Header h = headers.nextElement();
            out.computeIfAbsent(h.getName(), k -> new ArrayList<>()).add(h.getValue());
        }
        return out;
    }

    private MailSummary toSummary(Message m) throws MessagingException {
        long uid = uidFolder.getUID(m);
        boolean seen = m.isSet(Flags.Flag.SEEN);
        String from = "(unknown)";
        Address[] froms = m.getFrom();
        if (froms != null && froms.length > 0) from = froms[0].toString();
        String subject = m.getSubject() != null ? m.getSubject() : "(no subject)";
        Date received = m.getReceivedDate();
        return new MailSummary(m.getMessageNumber(), uid, seen, received, from, subject);
    }

    private List<MailSummary> summarize(Message[] msgs, int limit) throws MessagingException {
        if (msgs == null || msgs.length == 0) return List.of();

        Arrays.sort(msgs, Comparator.comparingInt(Message::getMessageNumber).reversed());
        int n = Math.min(limit, msgs.length);
        Message[] slice = Arrays.copyOf(msgs, n);

        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.FLAGS);
        inbox.fetch(slice, fp);
        log.info("Fetched {} messages", slice.length);

        List<MailSummary> out = new ArrayList<>(n);
        for (Message m : slice) {
            out.add(toSummary(m));
        }
        return out;
    }

    /**
     * Get a message summary by UID directly.
     */
    public MailSummary getMessageByUid(long uid) throws MessagingException {
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) return null;
        return toSummary(m);
    }

    /**
     * Get the Message-ID header for a message.
     */
    public String getMessageId(long uid) throws MessagingException {
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) return null;
        String[] ids = m.getHeader("Message-ID");
        return (ids != null && ids.length > 0) ? ids[0] : null;
    }

    /**
     * Get In-Reply-To header for a message.
     */
    public String getInReplyTo(long uid) throws MessagingException {
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) return null;
        String[] refs = m.getHeader("In-Reply-To");
        return (refs != null && refs.length > 0) ? refs[0] : null;
    }

    /**
     * Get References header for a message.
     */
    public String getReferences(long uid) throws MessagingException {
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) return null;
        String[] refs = m.getHeader("References");
        return (refs != null && refs.length > 0) ? refs[0] : null;
    }

    /**
     * Get custom ZKEmails Thread ID header for a message.
     * This header survives Gmail's header stripping (unlike In-Reply-To/References).
     */
    public String getZkeThreadId(long uid) throws MessagingException {
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) return null;
        String[] threadId = m.getHeader("X-ZKEmails-Thread-Id");
        return (threadId != null && threadId.length > 0) ? threadId[0] : null;
    }

    /**
     * Get Gmail Thread ID (X-GM-THRID) for a message.
     * Only works with Gmail IMAP server.
     * @return the thread ID as a string, or null if not available
     */
    public String getGmailThreadId(long uid) throws MessagingException {
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) return null;
        String[] thrid = m.getHeader("X-GM-THRID");
        return (thrid != null && thrid.length > 0) ? thrid[0] : null;
    }

    /**
     * Get Gmail Message ID (X-GM-MSGID) for a message.
     * Only works with Gmail IMAP server.
     */
    public String getGmailMessageId(long uid) throws MessagingException {
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) return null;
        String[] msgid = m.getHeader("X-GM-MSGID");
        return (msgid != null && msgid.length > 0) ? msgid[0] : null;
    }

    /**
     * Search for all messages in a thread by Message-ID.
     * Finds messages where Message-ID, In-Reply-To, or References match.
     */
    public List<MailSummary> searchThread(Set<String> threadMessageIds, int limit) throws MessagingException {
        if (threadMessageIds == null || threadMessageIds.isEmpty()) return List.of();

        // Search for zkemails messages in recent days
        Date sinceDate = daysAgo(SEARCH_DAYS_LIMIT);
        SearchTerm baseTerm = new AndTerm(
                new ReceivedDateTerm(ComparisonTerm.GE, sinceDate),
                new HeaderTerm("X-ZKEmails-Type", "msg")
        );
        Message[] found = inbox.search(baseTerm);
        System.out.println("=== SEARCH DEBUG: Found " + (found != null ? found.length : 0) + " ZKE messages in last " + SEARCH_DAYS_LIMIT + " days");
        if (found == null || found.length == 0) return List.of();

        // Filter by thread membership
        List<Message> threadMsgs = new ArrayList<>();
        System.out.println("=== SEARCH DEBUG: Looking for thread IDs: " + threadMessageIds);
        for (Message m : found) {
            String msgId = getHeaderValue(m, "Message-ID");
            String inReplyTo = getHeaderValue(m, "In-Reply-To");
            String references = getHeaderValue(m, "References");
            String subject = "";
            try { subject = m.getSubject(); } catch (Exception e) {}
            System.out.println("=== SEARCH DEBUG: Checking msg [" + subject + "] In-Reply-To=" + inReplyTo + " References=" + references);

            boolean inThread = false;
            if (msgId != null && threadMessageIds.contains(msgId)) inThread = true;
            if (inReplyTo != null && threadMessageIds.contains(inReplyTo)) {
                System.out.println("=== SEARCH DEBUG: Match via In-Reply-To: " + inReplyTo);
                inThread = true;
            }
            if (references != null) {
                for (String id : threadMessageIds) {
                    if (references.contains(id)) {
                        System.out.println("=== SEARCH DEBUG: Match via References: " + references);
                        inThread = true;
                        break;
                    }
                }
            }
            if (inThread) threadMsgs.add(m);
        }
        System.out.println("=== SEARCH DEBUG: " + threadMsgs.size() + " messages match thread IDs");

        // Sort by date ascending (oldest first for thread view)
        threadMsgs.sort(Comparator.comparing(m -> {
            try {
                return m.getReceivedDate();
            } catch (MessagingException e) {
                return new Date(0);
            }
        }));

        int n = Math.min(limit, threadMsgs.size());
        List<MailSummary> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(toSummary(threadMsgs.get(i)));
        }
        return out;
    }

    /**
     * Search for messages by subject (for Gmail sent folder where In-Reply-To/References aren't preserved).
     * Matches messages where subject contains the base subject (with or without Re: prefix).
     */
    public List<MailSummary> searchBySubject(String baseSubject, int limit) throws MessagingException {
        if (baseSubject == null || baseSubject.isEmpty()) return List.of();

        Date sinceDate = daysAgo(SEARCH_DAYS_LIMIT);
        // Search for ZKE messages with matching subject
        SearchTerm baseTerm = new AndTerm(new SearchTerm[]{
                new ReceivedDateTerm(ComparisonTerm.GE, sinceDate),
                new HeaderTerm("X-ZKEmails-Type", "msg"),
                new SubjectTerm(baseSubject)
        });

        Message[] found = inbox.search(baseTerm);
        if (found == null || found.length == 0) return List.of();

        // Sort by date ascending
        Arrays.sort(found, Comparator.comparing(m -> {
            try {
                return m.getReceivedDate();
            } catch (MessagingException e) {
                return new Date(0);
            }
        }));

        int n = Math.min(limit, found.length);
        List<MailSummary> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(toSummary(found[i]));
        }
        return out;
    }

    /**
     * Search for messages by custom ZKEmails Thread ID.
     * This is the primary thread correlation method (survives Gmail header stripping).
     */
    public List<MailSummary> searchByZkeThreadId(String threadId, int limit) throws MessagingException {
        if (threadId == null || threadId.isEmpty()) return List.of();

        Date sinceDate = daysAgo(SEARCH_DAYS_LIMIT);
        SearchTerm term = new AndTerm(new SearchTerm[]{
                new ReceivedDateTerm(ComparisonTerm.GE, sinceDate),
                new HeaderTerm("X-ZKEmails-Type", "msg"),
                new HeaderTerm("X-ZKEmails-Thread-Id", threadId)
        });

        Message[] found = inbox.search(term);
        if (found == null || found.length == 0) return List.of();

        // Sort by date ascending
        Arrays.sort(found, Comparator.comparing(m -> {
            try {
                return m.getReceivedDate();
            } catch (MessagingException e) {
                return new Date(0);
            }
        }));

        int n = Math.min(limit, found.length);
        List<MailSummary> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(toSummary(found[i]));
        }
        return out;
    }

    private String getHeaderValue(Message m, String headerName) throws MessagingException {
        String[] vals = m.getHeader(headerName);
        return (vals != null && vals.length > 0) ? vals[0] : null;
    }

    /**
     * Build the full set of Message-IDs in a thread starting from a message.
     */
    public Set<String> buildThreadIdSet(long uid) throws MessagingException {
        Set<String> ids = new HashSet<>();
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) return ids;

        String msgId = getHeaderValue(m, "Message-ID");
        if (msgId != null) ids.add(msgId);

        String inReplyTo = getHeaderValue(m, "In-Reply-To");
        if (inReplyTo != null) ids.add(inReplyTo);

        String references = getHeaderValue(m, "References");
        if (references != null) {
            // References is space-separated list of Message-IDs
            for (String ref : references.split("\\s+")) {
                if (!ref.isBlank()) ids.add(ref.trim());
            }
        }
        return ids;
    }

    /**
     * Check if a message has encrypted attachments.
     * @return number of attachments, or 0 if none
     */
    public int getAttachmentCount(long uid) throws MessagingException {
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) return 0;
        String[] countHeader = m.getHeader("X-ZKEmails-Has-Attachments");
        if (countHeader == null || countHeader.length == 0) return 0;
        try {
            return Integer.parseInt(countHeader[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Check if a message has attachments.
     */
    public boolean hasAttachments(long uid) throws MessagingException {
        return getAttachmentCount(uid) > 0;
    }

    /**
     * Container for encrypted attachments parsed from email.
     */
    public record AttachmentContainer(int version, List<CryptoBox.EncryptedAttachment> attachments) {}

    /**
     * Fetch the JSON payload (zkemails-payload.json) from a v2 multi-recipient message.
     * Returns null if not found or not a v2 message.
     */
    public CryptoBox.EncryptedPayloadV2 fetchJsonPayload(long uid) throws MessagingException {
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) {
            log.warn("fetchJsonPayload: message not found for UID {}", uid);
            return null;
        }

        try {
            Object content = m.getContent();
            if (!(content instanceof MimeMultipart multipart)) {
                return null;  // Not multipart, not a v2 message
            }

            // Look for the zkemails-payload.json part
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                String filename = part.getFileName();
                if ("zkemails-payload.json".equals(filename)) {
                    // Parse JSON content
                    Object partContent = part.getContent();
                    String json;
                    if (partContent instanceof String) {
                        json = (String) partContent;
                    } else if (partContent instanceof java.io.InputStream is) {
                        json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    } else {
                        log.warn("Unexpected payload part content type: {}", partContent.getClass());
                        continue;
                    }

                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.readValue(json, CryptoBox.EncryptedPayloadV2.class);
                }
            }
            return null;  // No payload found
        } catch (IOException e) {
            throw new MessagingException("Failed to parse v2 payload", e);
        }
    }

    /**
     * Fetch and parse the encrypted attachment container from a message.
     * Returns null if no attachments or parsing fails.
     */
    public AttachmentContainer fetchAttachmentContainer(long uid) throws MessagingException {
        Message m = uidFolder.getMessageByUID(uid);
        if (m == null) {
            log.warn("fetchAttachmentContainer: message not found for UID {}", uid);
            return null;
        }

        // Check if message has attachments
        if (!hasAttachments(uid)) {
            return null;
        }

        try {
            Object content = m.getContent();
            if (!(content instanceof MimeMultipart multipart)) {
                log.warn("Message {} has attachments header but content is not multipart", uid);
                return null;
            }

            // Look for the attachments.zke part
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                String filename = part.getFileName();
                if ("attachments.zke".equals(filename)) {
                    // Parse JSON content
                    Object partContent = part.getContent();
                    String json;
                    if (partContent instanceof String) {
                        json = (String) partContent;
                    } else if (partContent instanceof java.io.InputStream is) {
                        json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    } else {
                        log.warn("Unexpected attachment part content type: {}", partContent.getClass());
                        continue;
                    }

                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.readValue(json, AttachmentContainer.class);
                }
            }
            log.warn("Message {} has attachments header but no attachments.zke part found", uid);
            return null;
        } catch (IOException e) {
            throw new MessagingException("Failed to parse attachment container", e);
        }
    }
}
