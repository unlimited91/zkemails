package me.toymail.zkemails.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class InviteStore {
    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static final class Invite {
        public String inviteId;
        public String direction; // "out" or "in"
        public String fromEmail;
        public String toEmail;
        public String subject;
        public long createdEpochSec;
        public String status; // out: sent; in: pending/acked

        public Invite() {}
    }

    private final ZkStore store;
    private final Path file;

    public InviteStore(ZkStore store) {
        this.store = store;
        this.file = store.path("invites.json");
    }

    public void addOutgoing(String inviteId, String from, String to, String subject) throws IOException {
        store.ensure();
        List<Invite> all = listAll();

        Invite i = new Invite();
        i.inviteId = inviteId;
        i.direction = "out";
        i.fromEmail = from;
        i.toEmail = to;
        i.subject = subject;
        i.createdEpochSec = Instant.now().getEpochSecond();
        i.status = "sent";

        all.add(i);
        writeAll(all);
    }

    /** Ensure an incoming invite exists locally (used by ack flow). */
    public void ensureIncoming(String inviteId, String from, String to, String subject) throws IOException {
        store.ensure();
        List<Invite> all = listAll();

        for (Invite i : all) {
            if (inviteId.equals(i.inviteId) && "in".equalsIgnoreCase(i.direction)) return;
        }

        Invite i = new Invite();
        i.inviteId = inviteId;
        i.direction = "in";
        i.fromEmail = from;
        i.toEmail = to;
        i.subject = subject != null ? subject : "(invite)";
        i.createdEpochSec = Instant.now().getEpochSecond();
        i.status = "pending";

        all.add(i);
        writeAll(all);
    }

    /** Mark an incoming invite as acked so it disappears from `ls invi`. */
    public void markIncomingAcked(String inviteId) throws IOException {
        store.ensure();
        List<Invite> all = listAll();

        boolean changed = false;
        for (Invite i : all) {
            if (inviteId.equals(i.inviteId) && "in".equalsIgnoreCase(i.direction)) {
                i.status = "acked";
                changed = true;
                break;
            }
        }
        if (changed) writeAll(all);
    }

    /** List incoming invites; if onlyUnacked=true filters out status=acked. */
    public List<Invite> listIncoming(boolean onlyUnacked) throws IOException {
        List<Invite> all = listAll();
        List<Invite> out = new ArrayList<>();
        for (Invite i : all) {
//            if (!"in".equalsIgnoreCase(i.direction)) continue;
            boolean acked = "acked".equalsIgnoreCase(i.status);
            if (onlyUnacked && acked) continue;
            out.add(i);
        }
        out.sort(Comparator.comparingLong((Invite i) -> i.createdEpochSec).reversed());
        return out;
    }

    public List<Invite> listOutgoingNewestFirst() throws IOException {
        List<Invite> all = listAll();
        List<Invite> out = new ArrayList<>();
        for (Invite i : all) {
            if ("out".equalsIgnoreCase(i.direction)) out.add(i);
        }
        out.sort(Comparator.comparingLong((Invite i) -> i.createdEpochSec).reversed());
        return out;
    }

    private List<Invite> listAll() throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) return new ArrayList<>();
        return M.readValue(bytes, new TypeReference<List<Invite>>() {});
    }

    private void writeAll(List<Invite> all) throws IOException {
        byte[] bytes = M.writeValueAsBytes(all);
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
