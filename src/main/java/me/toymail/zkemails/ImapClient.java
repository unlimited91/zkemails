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

    private ImapClient(Store store) {
        this.store = store;
    }

    public static ImapClient connect(ImapConfig cfg) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", cfg.ssl() ? "imaps" : "imap");

        props.put("mail.imaps.ssl.enable", String.valueOf(cfg.ssl()));
        props.put("mail.imaps.ssl.checkserveridentity", "true");
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "20000");
        props.put("mail.imaps.keepalive", "true");

        Session session = Session.getInstance(props);
        // session.setDebug(true);

        Store store = session.getStore(cfg.ssl() ? "imaps" : "imap");
        store.connect(cfg.host(), cfg.port(), cfg.username(), cfg.password());
        return new ImapClient(store);
    }

    @Override
    public void close() throws MessagingException {
        if (store != null && store.isConnected()) store.close();
    }

    public List<MailSummary> listInboxLatest(int limit) throws MessagingException {
        try (Folder inbox = openInboxReadOnly()) {
            int total = inbox.getMessageCount();
            if (total <= 0) return List.of();

            int end = total;
            int start = Math.max(1, total - limit + 1);
            Message[] msgs = inbox.getMessages(start, end);

            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.FLAGS);
            inbox.fetch(msgs, fp);

            UIDFolder uidFolder = (UIDFolder) inbox;
            List<MailSummary> out = new ArrayList<>(msgs.length);
            for (Message m : msgs) {
                long uid = uidFolder.getUID(m);
                boolean seen = m.isSet(Flags.Flag.SEEN);

                String from = "(unknown)";
                Address[] froms = m.getFrom();
                if (froms != null && froms.length > 0) from = froms[0].toString();

                String subject = m.getSubject() != null ? m.getSubject() : "(no subject)";
                Date received = m.getReceivedDate();
                out.add(new MailSummary(m.getMessageNumber(), uid, seen, received, from, subject));
            }
            out.sort(Comparator.comparingInt(MailSummary::msgNum).reversed());
            return out;
        }
    }

    public List<MailSummary> searchHeaderEquals(String headerName, String headerValue, int limit) throws MessagingException {
        try (Folder inbox = openInboxReadOnly()) {
            Message[] found = inbox.search(new HeaderTerm(headerName, headerValue));
            return summarize(inbox, found, limit);
        }
    }

    public List<MailSummary> searchUnread(int limit) throws MessagingException {
        try (Folder inbox = openInboxReadOnly()) {
            Message[] found = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            return summarize(inbox, found, limit);
        }
    }

    public List<MailSummary> searchUnreadWithHeader(String headerName, String headerValue, int limit) throws MessagingException {
        try (Folder inbox = openInboxReadOnly()) {
            SearchTerm term = new AndTerm(
                    new FlagTerm(new Flags(Flags.Flag.SEEN), false),
                    new HeaderTerm(headerName, headerValue)
            );
            Message[] found = inbox.search(term);
            return summarize(inbox, found, limit);
        }
    }

    public Map<String, List<String>> fetchAllHeadersByUid(long uid) throws MessagingException {
        try (Folder inbox = openInboxReadOnly()) {
            UIDFolder uf = (UIDFolder) inbox;
            Message m = uf.getMessageByUID(uid);
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
    }

    private Folder openInboxReadOnly() throws MessagingException {
        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        if (!(inbox instanceof UIDFolder)) {
            inbox.close(false);
            throw new MessagingException("Folder does not support UIDFolder; cannot use stable UIDs.");
        }
        return inbox;
    }

    private List<MailSummary> summarize(Folder folder, Message[] msgs, int limit) throws MessagingException {
        if (msgs == null || msgs.length == 0) return List.of();

        Arrays.sort(msgs, Comparator.comparingInt(Message::getMessageNumber).reversed());
        int n = Math.min(limit, msgs.length);
        Message[] slice = Arrays.copyOf(msgs, n);

        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.FLAGS);
        folder.fetch(slice, fp);

        UIDFolder uf = (UIDFolder) folder;
        List<MailSummary> out = new ArrayList<>(n);
        for (Message m : slice) {
            long uid = uf.getUID(m);
            boolean seen = m.isSet(Flags.Flag.SEEN);

            String from = "(unknown)";
            Address[] froms = m.getFrom();
            if (froms != null && froms.length > 0) from = froms[0].toString();

            String subject = m.getSubject() != null ? m.getSubject() : "(no subject)";
            Date received = m.getReceivedDate();
            out.add(new MailSummary(m.getMessageNumber(), uid, seen, received, from, subject));
        }
        return out;
    }
}
