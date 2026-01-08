package me.toymail.zkemails;

import jakarta.mail.*;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.HeaderTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final int SEARCH_DAYS_LIMIT = 10;

    public static ImapClient connect(ImapConfig cfg) throws MessagingException {
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

        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        log.info("Opened INBOX with messages");
        if (!(inbox instanceof UIDFolder)) {
            inbox.close(false);
            store.close();
            throw new MessagingException("Folder does not support UIDFolder; cannot use stable UIDs.");
        }

        return new ImapClient(store, inbox);
    }

    @Override
    public void close() throws MessagingException {
        if (inbox != null && inbox.isOpen()) inbox.close(false);
        if (store != null && store.isConnected()) store.close();
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
        if (found == null || found.length == 0) return List.of();

        // Filter by thread membership
        List<Message> threadMsgs = new ArrayList<>();
        for (Message m : found) {
            String msgId = getHeaderValue(m, "Message-ID");
            String inReplyTo = getHeaderValue(m, "In-Reply-To");
            String references = getHeaderValue(m, "References");

            boolean inThread = false;
            if (msgId != null && threadMessageIds.contains(msgId)) inThread = true;
            if (inReplyTo != null && threadMessageIds.contains(inReplyTo)) inThread = true;
            if (references != null) {
                for (String id : threadMessageIds) {
                    if (references.contains(id)) {
                        inThread = true;
                        break;
                    }
                }
            }
            if (inThread) threadMsgs.add(m);
        }

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
}
