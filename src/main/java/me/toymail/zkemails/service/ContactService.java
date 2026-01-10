package me.toymail.zkemails.service;

import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.ContactsStore.Contact;
import me.toymail.zkemails.store.StoreContext;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Service for managing contacts.
 */
public final class ContactService {
    private final StoreContext context;

    public ContactService(StoreContext context) {
        this.context = context;
    }

    /**
     * Contact information record for GUI display.
     */
    public record ContactInfo(
        String email,
        String status,
        String fingerprintHex,
        boolean hasKeys,
        long firstSeenEpochSec,
        long lastUpdatedEpochSec
    ) {
        public static ContactInfo from(Contact c) {
            boolean hasKeys = c.ed25519PublicB64 != null && c.x25519PublicB64 != null;
            return new ContactInfo(
                c.email,
                c.status,
                c.fingerprintHex,
                hasKeys,
                c.firstSeenEpochSec,
                c.lastUpdatedEpochSec
            );
        }
    }

    /**
     * List all contacts.
     * @return list of contact info records
     */
    public List<ContactInfo> listContacts() throws IOException {
        if (!context.hasActiveProfile()) {
            return new ArrayList<>();
        }
        List<Contact> contacts = context.contacts().listAll();
        return contacts.stream().map(ContactInfo::from).toList();
    }

    /**
     * Get a specific contact by email.
     * @param email the contact email
     * @return contact info, or null if not found
     */
    public ContactInfo getContact(String email) throws IOException {
        if (!context.hasActiveProfile()) {
            return null;
        }
        Contact c = context.contacts().get(email);
        return c != null ? ContactInfo.from(c) : null;
    }

    /**
     * Check if a contact is ready for encrypted messaging.
     * @param email the contact email
     * @return true if contact has keys and status is "ready"
     */
    public boolean isContactReady(String email) throws IOException {
        ContactInfo info = getContact(email);
        return info != null && info.hasKeys() && "ready".equals(info.status());
    }

    /**
     * Get contacts ready for encrypted messaging.
     * @return list of contacts with status "ready" and keys present
     */
    public List<ContactInfo> getReadyContacts() throws IOException {
        return listContacts().stream()
            .filter(c -> c.hasKeys() && "ready".equals(c.status()))
            .toList();
    }

    /**
     * Get contacts who have been invited but not yet ready.
     * @return list of contacts with status "invited-out"
     */
    public List<ContactInfo> getPendingContacts() throws IOException {
        return listContacts().stream()
            .filter(c -> "invited-out".equals(c.status()))
            .toList();
    }

    // ========== Version History Methods ==========

    /**
     * Version information record.
     */
    public record VersionInfo(
        String filename,
        Instant timestamp,
        long sizeBytes,
        int contactCount
    ) {}

    /**
     * Represents a change to a contact between versions.
     */
    public record ContactDiff(
        String email,
        String changeType,  // "added", "removed", "modified"
        ContactInfo oldValue,
        ContactInfo newValue,
        List<String> changedFields
    ) {}

    /**
     * List all version snapshots.
     * @return list of version info, newest first
     */
    public List<VersionInfo> listVersions() throws IOException {
        if (!context.hasActiveProfile()) {
            return List.of();
        }

        List<ContactsStore.VersionInfo> storeVersions = context.contacts().listVersions();
        List<VersionInfo> result = new ArrayList<>();

        for (var v : storeVersions) {
            int count = 0;
            try {
                count = context.contacts().readVersion(v.path()).size();
            } catch (IOException e) {
                // Ignore read errors
            }
            result.add(new VersionInfo(v.filename(), v.timestamp(), v.sizeBytes(), count));
        }

        return result;
    }

    /**
     * Compute diff between a version and current contacts.
     * @param versionFilename the version filename to compare
     * @return list of differences
     */
    public List<ContactDiff> diffWithCurrent(String versionFilename) throws IOException {
        if (!context.hasActiveProfile()) {
            return List.of();
        }

        Map<String, Contact> older = context.contacts().readVersionByFilename(versionFilename);
        Map<String, Contact> newer = context.contacts().readAll();

        return computeDiff(older, newer);
    }

    /**
     * Compute diff between two versions.
     * @param olderFilename the older version filename
     * @param newerFilename the newer version filename
     * @return list of differences
     */
    public List<ContactDiff> diffVersions(String olderFilename, String newerFilename) throws IOException {
        if (!context.hasActiveProfile()) {
            return List.of();
        }

        Map<String, Contact> older = context.contacts().readVersionByFilename(olderFilename);
        Map<String, Contact> newer = context.contacts().readVersionByFilename(newerFilename);

        return computeDiff(older, newer);
    }

    /**
     * Get contacts from a specific version.
     * @param versionFilename the version filename
     * @return list of contacts in that version
     */
    public List<ContactInfo> getContactsInVersion(String versionFilename) throws IOException {
        if (!context.hasActiveProfile()) {
            return List.of();
        }

        Map<String, Contact> contacts = context.contacts().readVersionByFilename(versionFilename);
        return contacts.values().stream()
                .sorted(Comparator.comparing(c -> c.email))
                .map(ContactInfo::from)
                .toList();
    }

    private List<ContactDiff> computeDiff(Map<String, Contact> older, Map<String, Contact> newer) {
        List<ContactDiff> diffs = new ArrayList<>();

        // Removed contacts (in older but not in newer)
        for (String email : older.keySet()) {
            if (!newer.containsKey(email)) {
                diffs.add(new ContactDiff(
                    email,
                    "removed",
                    ContactInfo.from(older.get(email)),
                    null,
                    List.of()
                ));
            }
        }

        // Added contacts (in newer but not in older)
        for (String email : newer.keySet()) {
            if (!older.containsKey(email)) {
                diffs.add(new ContactDiff(
                    email,
                    "added",
                    null,
                    ContactInfo.from(newer.get(email)),
                    List.of()
                ));
            }
        }

        // Modified contacts (in both, check for changes)
        for (String email : older.keySet()) {
            if (newer.containsKey(email)) {
                Contact o = older.get(email);
                Contact n = newer.get(email);
                List<String> changed = findChangedFields(o, n);
                if (!changed.isEmpty()) {
                    diffs.add(new ContactDiff(
                        email,
                        "modified",
                        ContactInfo.from(o),
                        ContactInfo.from(n),
                        changed
                    ));
                }
            }
        }

        // Sort by email for consistent ordering
        diffs.sort(Comparator.comparing(ContactDiff::email));
        return diffs;
    }

    private List<String> findChangedFields(Contact o, Contact n) {
        List<String> changed = new ArrayList<>();

        if (!Objects.equals(o.status, n.status)) {
            changed.add("status");
        }
        if (!Objects.equals(o.fingerprintHex, n.fingerprintHex)) {
            changed.add("fingerprintHex");
        }
        if (!Objects.equals(o.ed25519PublicB64, n.ed25519PublicB64)) {
            changed.add("ed25519PublicB64");
        }
        if (!Objects.equals(o.x25519PublicB64, n.x25519PublicB64)) {
            changed.add("x25519PublicB64");
        }
        if (o.lastUpdatedEpochSec != n.lastUpdatedEpochSec) {
            changed.add("lastUpdatedEpochSec");
        }

        return changed;
    }
}
