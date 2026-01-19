package me.toymail.zkemails.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Simple local TOFU contact store keyed by email address.
 * Automatically creates version snapshots before each modification.
 */
public final class ContactsStore {
    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter VERSION_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
            .withZone(ZoneId.of("UTC"));

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

        /**
         * Create a deep copy of this contact.
         */
        public Contact copy() {
            Contact c = new Contact();
            c.email = this.email;
            c.status = this.status;
            c.fingerprintHex = this.fingerprintHex;
            c.ed25519PublicB64 = this.ed25519PublicB64;
            c.x25519PublicB64 = this.x25519PublicB64;
            c.firstSeenEpochSec = this.firstSeenEpochSec;
            c.lastUpdatedEpochSec = this.lastUpdatedEpochSec;
            return c;
        }
    }

    /**
     * Information about a version snapshot.
     */
    public record VersionInfo(Path path, String filename, Instant timestamp, long sizeBytes) {}

    private final ZkStore store;
    private final Path file;
    private final Path versionsDir;

    public ContactsStore(ZkStore store) {
        this.store = store;
        this.file = store.path("contacts.json");
        this.versionsDir = store.path("contacts-versions");
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

    /**
     * Read current contacts.
     */
    public Map<String, Contact> readAll() throws IOException {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) return new LinkedHashMap<>();
        return M.readValue(bytes, new TypeReference<Map<String, Contact>>() {});
    }

    private void writeAll(Map<String, Contact> map) throws IOException {
        saveVersionSnapshot();
        byte[] bytes = M.writeValueAsBytes(map);
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Save current contacts.json as a version snapshot before modification.
     */
    private void saveVersionSnapshot() throws IOException {
        if (!Files.exists(file)) return;

        Files.createDirectories(versionsDir);
        String timestamp = VERSION_FORMATTER.format(Instant.now());
        Path snapshot = versionsDir.resolve("contacts_" + timestamp + ".json");
        Files.copy(file, snapshot, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * List all version snapshots, newest first.
     */
    public List<VersionInfo> listVersions() throws IOException {
        if (!Files.exists(versionsDir)) return List.of();

        try (Stream<Path> stream = Files.list(versionsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("contacts_"))
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(this::toVersionInfo)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(VersionInfo::timestamp).reversed())
                    .toList();
        }
    }

    private VersionInfo toVersionInfo(Path path) {
        try {
            String filename = path.getFileName().toString();
            // Parse timestamp from filename: contacts_2026-01-10T17-30-00Z.json
            String timestampStr = filename
                    .replace("contacts_", "")
                    .replace(".json", "");
            Instant timestamp = VERSION_FORMATTER.parse(timestampStr, Instant::from);
            long size = Files.size(path);
            return new VersionInfo(path, filename, timestamp, size);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Read contacts from a specific version file.
     */
    public Map<String, Contact> readVersion(Path versionPath) throws IOException {
        byte[] bytes = Files.readAllBytes(versionPath);
        if (bytes.length == 0) return new LinkedHashMap<>();
        return M.readValue(bytes, new TypeReference<Map<String, Contact>>() {});
    }

    /**
     * Read contacts from a version by filename.
     */
    public Map<String, Contact> readVersionByFilename(String filename) throws IOException {
        Path versionPath = versionsDir.resolve(filename);
        if (!Files.exists(versionPath)) {
            throw new IOException("Version not found: " + filename);
        }
        return readVersion(versionPath);
    }

    /**
     * Get the path to the versions directory.
     */
    public Path getVersionsDir() {
        return versionsDir;
    }

    private static String norm(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
