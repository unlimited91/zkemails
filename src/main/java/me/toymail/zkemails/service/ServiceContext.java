package me.toymail.zkemails.service;

import me.toymail.zkemails.store.StoreContext;

/**
 * Container for all service instances.
 * Provides a single point of access to all services.
 */
public final class ServiceContext {
    private final StoreContext storeContext;

    private final ProfileService profileService;
    private final CredentialService credentialService;
    private final ContactService contactService;
    private final InviteService inviteService;
    private final MessageService messageService;
    private final InitService initService;

    private ServiceContext(StoreContext storeContext) {
        this.storeContext = storeContext;
        this.profileService = new ProfileService(storeContext);
        this.credentialService = new CredentialService(storeContext);
        this.contactService = new ContactService(storeContext);
        this.inviteService = new InviteService(storeContext);
        this.messageService = new MessageService(storeContext);
        this.initService = new InitService(storeContext);
    }

    /**
     * Create a new ServiceContext with a fresh StoreContext.
     */
    public static ServiceContext create() {
        StoreContext storeContext = StoreContext.initialize();
        return new ServiceContext(storeContext);
    }

    /**
     * Create a ServiceContext from an existing StoreContext.
     * Use this when launching GUI from CLI to share the same context.
     */
    public static ServiceContext fromStoreContext(StoreContext storeContext) {
        return new ServiceContext(storeContext);
    }

    /**
     * Get the underlying StoreContext.
     */
    public StoreContext storeContext() {
        return storeContext;
    }

    /**
     * Get the profile service.
     */
    public ProfileService profiles() {
        return profileService;
    }

    /**
     * Get the credential service.
     */
    public CredentialService credentials() {
        return credentialService;
    }

    /**
     * Get the contact service.
     */
    public ContactService contacts() {
        return contactService;
    }

    /**
     * Get the invite service.
     */
    public InviteService invites() {
        return inviteService;
    }

    /**
     * Get the message service.
     */
    public MessageService messages() {
        return messageService;
    }

    /**
     * Get the init service.
     */
    public InitService init() {
        return initService;
    }
}
