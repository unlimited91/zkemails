package me.toymail.zkemails.store;

import java.io.IOException;

public final class StoreContext {
    private final ProfileConfigStore profileConfigStore;
    private ZkStore zkStore;
    private ContactsStore contactsStore;
    private InviteStore inviteStore;

    private StoreContext(ProfileConfigStore profileConfigStore) {
        this.profileConfigStore = profileConfigStore;
    }

    public static StoreContext initialize() {
        ProfileConfigStore profileConfigStore = new ProfileConfigStore();
        StoreContext context = new StoreContext(profileConfigStore);

        try {
            String defaultProfile = profileConfigStore.getDefault();
            if (defaultProfile != null) {
                context.initializeProfileStores(defaultProfile);
            }
        } catch (IOException e) {
            // No profile config exists yet, stores will be null
        }

        return context;
    }

    private void initializeProfileStores(String email) {
        this.zkStore = new ZkStore(email);
        this.contactsStore = new ContactsStore(zkStore);
        this.inviteStore = new InviteStore(zkStore);
    }

    public void switchProfile(String email) throws IOException {
        profileConfigStore.setDefault(email);
        initializeProfileStores(email);
    }

    public void addAndSwitchProfile(String email) throws IOException {
        profileConfigStore.addProfile(email);
        initializeProfileStores(email);
    }

    public ProfileConfigStore profileConfig() {
        return profileConfigStore;
    }

    public ZkStore zkStore() {
        return zkStore;
    }

    public ContactsStore contacts() {
        return contactsStore;
    }

    public InviteStore invites() {
        return inviteStore;
    }

    public boolean hasActiveProfile() {
        return zkStore != null;
    }
}
