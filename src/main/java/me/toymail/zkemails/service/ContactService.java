package me.toymail.zkemails.service;

import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.ContactsStore.Contact;
import me.toymail.zkemails.store.StoreContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
}
