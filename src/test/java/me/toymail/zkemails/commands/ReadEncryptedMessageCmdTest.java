package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.crypto.CryptoBox;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ZkStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import picocli.CommandLine;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ReadEncryptedMessageCmdTest extends CommandTestBase {

    @Test
    public void testRemList_NoProfile() {
        ReadEncryptedMessageCmd cmd = new ReadEncryptedMessageCmd(context);
        cmd.run();
        // Should print error about no profile (can capture stdout/stderr if needed, but for now just ensure no exception)
    }

    @Test
    public void testRemList_NotInitialized() throws Exception {
        // Setup profile
        ZkStore store = new ZkStore("test@example.com");
        store.ensure();

        // Write profile.config
        java.nio.file.Files.writeString(
                tempDir.resolve(".zkemails").resolve("profile.config"),
                "{\"profiles\":[\"test@example.com\"],\"default\":\"test@example.com\"}"
        );

        reinitializeContext();

        ReadEncryptedMessageCmd cmd = new ReadEncryptedMessageCmd(context);
        cmd.password = "pass";
        cmd.run();
        // Should print error about not initialized
    }

    @Test
    public void testRemList_Success() throws Exception {
        setupInitializedProfile("test@example.com");
        reinitializeContext();

        try (MockedStatic<ImapClient> mockedImap = mockStatic(ImapClient.class)) {
            ImapClient imap = mock(ImapClient.class);
            mockedImap.when(() -> ImapClient.connect(any())).thenReturn(imap);

            List<ImapClient.MailSummary> msgs = new ArrayList<>();
            msgs.add(new ImapClient.MailSummary(1, 100, false, new Date(), "sender@example.com", "Subject"));
            when(imap.searchHeaderEquals(eq("X-ZKEmails-Type"), eq("msg"), anyInt())).thenReturn(msgs);

            Map<String, List<String>> headers = new HashMap<>();
            headers.put("X-ZKEmails-Sig", List.of("sig123"));
            when(imap.fetchAllHeadersByUid(100)).thenReturn(headers);

            ReadEncryptedMessageCmd cmd = new ReadEncryptedMessageCmd(context);
            cmd.password = "pass";
            cmd.run();

            verify(imap).searchHeaderEquals(eq("X-ZKEmails-Type"), eq("msg"), anyInt());
            verify(imap).fetchAllHeadersByUid(100);
        }
    }

    @Test
    public void testRemMessage_Success() throws Exception {
        setupInitializedProfile("test@example.com");
        IdentityKeys.KeyBundle recipientKeys = new ZkStore("test@example.com").readJson("keys.json", IdentityKeys.KeyBundle.class);
        IdentityKeys.KeyBundle senderKeys = IdentityKeys.generate();

        // Add sender as contact so we can verify signature
        ZkStore store = new ZkStore("test@example.com");
        me.toymail.zkemails.store.ContactsStore contacts = new me.toymail.zkemails.store.ContactsStore(store);
        contacts.upsertKeys("sender@example.com", "ready", senderKeys.fingerprintHex(), senderKeys.ed25519PublicB64(), senderKeys.x25519PublicB64());

        reinitializeContext();

        try (MockedStatic<ImapClient> mockedImap = mockStatic(ImapClient.class)) {
            ImapClient imap = mock(ImapClient.class);
            mockedImap.when(() -> ImapClient.connect(any())).thenReturn(imap);

            List<ImapClient.MailSummary> msgs = new ArrayList<>();
            msgs.add(new ImapClient.MailSummary(1, 100, false, new Date(), "sender@example.com", "Subject"));
            when(imap.searchHeaderEquals(eq("X-ZKEmails-Type"), eq("msg"), anyInt())).thenReturn(msgs);

            // Create valid encrypted payload
            CryptoBox.EncryptedPayload payload = CryptoBox.encryptToRecipient(
                    "sender@example.com", "test@example.com", "Subject", "Secret Message",
                    senderKeys, recipientKeys.fingerprintHex(), recipientKeys.x25519PublicB64()
            );

            Map<String, List<String>> headers = new HashMap<>();
            headers.put("X-ZKEmails-Sig", List.of(payload.sigB64()));
            headers.put("X-ZKEmails-Ephem-X25519", List.of(payload.ephemX25519PubB64()));
            headers.put("X-ZKEmails-WrappedKey", List.of(payload.wrappedKeyB64()));
            headers.put("X-ZKEmails-WrappedKey-Nonce", List.of(payload.wrappedKeyNonceB64()));
            headers.put("X-ZKEmails-Nonce", List.of(payload.msgNonceB64()));
            headers.put("X-ZKEmails-Ciphertext", List.of(payload.ciphertextB64()));
            headers.put("X-ZKEmails-Sender-Fp", List.of(senderKeys.fingerprintHex()));
            headers.put("X-ZKEmails-Recipient-Fp", List.of(payload.recipientFpHex()));
            headers.put("X-ZKEmails-PubKey-Ed25519", List.of(senderKeys.ed25519PublicB64()));

            when(imap.fetchAllHeadersByUid(100)).thenReturn(headers);

            ReadEncryptedMessageCmd cmd = new ReadEncryptedMessageCmd(context);
            cmd.password = "pass";
            cmd.messageId = payload.sigB64();
            cmd.run();

            verify(imap).searchHeaderEquals(eq("X-ZKEmails-Type"), eq("msg"), anyInt());
            verify(imap).fetchAllHeadersByUid(100);
        }
    }

    private void setupInitializedProfile(String email) throws Exception {
        ZkStore store = new ZkStore(email);
        store.ensure();

        // Write profile.config
        java.nio.file.Files.writeString(
                tempDir.resolve(".zkemails").resolve("profile.config"),
                "{\"profiles\":[\"" + email + "\"],\"default\":\"" + email + "\"}"
        );

        Config cfg = new Config();
        cfg.email = email;
        cfg.imap.host = "imap.test";
        cfg.imap.port = 993;
        cfg.imap.username = email;
        store.writeJson("config.json", cfg);

        IdentityKeys.KeyBundle keys = IdentityKeys.generate();
        store.writeJson("keys.json", keys);
    }
}

