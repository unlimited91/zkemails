package me.toymail.zkemails;

import jakarta.mail.*;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.HeaderTerm;
import jakarta.mail.search.SearchTerm;

import java.util.*;

public final class ImapClient implements AutoCloseable {

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

    public static ImapClient connect(ImapConfig cfg) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", cfg.ssl() ? "imaps" : "imap");

        props.put("mail.imaps.ssl.enable", String.valueOf(cfg.ssl()));
        props.put("mail.imaps.ssl.checkserveridentity", "true");
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "30000");
        props.put("mail.imaps.keepalive", "true");

        Session session = Session.getInstance(props);
        // session.setDebug(true);

        Store store = session.getStore(cfg.ssl() ? "imaps" : "imap");
        store.connect(cfg.host(), cfg.port(), cfg.username(), cfg.password());

        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
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
        Message[] found = inbox.search(new HeaderTerm(headerName, headerValue));
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
        SearchTerm term = new AndTerm(
                new HeaderTerm("X-ZKEmails-Type", "invite"),
                new HeaderTerm("X-ZKEmails-Invite-Id", inviteId)
        );
        Message[] found = inbox.search(term);
        return summarize(found, limit);
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

        List<MailSummary> out = new ArrayList<>(n);
        for (Message m : slice) {
            out.add(toSummary(m));
        }
        return out;
    }
}
