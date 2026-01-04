package me.toymail.zkemails.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Simple local TOFU contact store keyed by email address.
 */
public final class ContactsStore {
    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static final class Contact {
        public String email;
        // status examples: invited-out, ready
        public String status;

        public String fingerprintHex;
        public String ed25519PublicB64;
        public String x25519PublicB64;

        public long firstSeenEpochSec;
        public long lastUpdatedEpochSec;

        public Contact() {}
    }

    private final ZkStore store;
    private final Path file;

    public ContactsStore(ZkStore store) {
        this.store = store;
        this.file = store.path("contacts.json");
    }

    public synchronized Contact upsertBasic(String email, String status) throws IOException {
        store.ensure();
        Map<String, Contact> map = readAll();
        String k = norm(email);
        Contact c = map.getOrDefault(k, new Contact());
        if (c.email == null) {
            c.email = k;
            c.firstSeenEpochSec = Instant.now().getEpochSecond();
        }
        c.status = status;
        c.lastUpdatedEpochSec = Instant.now().getEpochSecond();
        map.put(k, c);
        writeAll(map);
        return c;
    }

    public synchronized Contact upsertKeys(String email, String status,
                                           String fingerprintHex, String edPubB64, String xPubB64) throws IOException {
        store.ensure();
        Map<String, Contact> map = readAll();
        String k = norm(email);
        Contact c = map.getOrDefault(k, new Contact());
        if (c.email == null) {
            c.email = k;
            c.firstSeenEpochSec = Instant.now().getEpochSecond();
        }
        c.status = status;
        c.fingerprintHex = fingerprintHex;
        c.ed25519PublicB64 = edPubB64;
        c.x25519PublicB64 = xPubB64;
        c.lastUpdatedEpochSec = Instant.now().getEpochSecond();
        map.put(k, c);
        writeAll(map);
        return c;
    }

    public synchronized Contact get(String email) throws IOException {
        Map<String, Contact> map = readAll();
        return map.get(norm(email));
    }

    public synchronized List<Contact> listAll() throws IOException {
        Map<String, Contact> map = readAll();
        List<Contact> out = new ArrayList<>(map.values());
        out.sort(Comparator.comparing(c -> c.email));
        return out;
    }

    private Map<String, Contact> readAll() throws IOException {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) return new LinkedHashMap<>();
        return M.readValue(bytes, new TypeReference<Map<String, Contact>>() {});
    }

    private void writeAll(Map<String, Contact> map) throws IOException {
        byte[] bytes = M.writeValueAsBytes(map);
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String norm(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
