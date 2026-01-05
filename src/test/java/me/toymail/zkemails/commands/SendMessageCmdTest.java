package me.toymail.zkemails.commands;

import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.ZkStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class SendMessageCmdTest extends CommandTestBase {

    @Test
    public void testSendMessage_Success() throws Exception {
        setupInitializedProfile("sender@example.com");

        // Add contact
        ZkStore store = new ZkStore("sender@example.com");
        ContactsStore contacts = new ContactsStore(store);
        IdentityKeys.KeyBundle recipientKeys = IdentityKeys.generate();
        contacts.upsertKeys("recipient@example.com", "verified", recipientKeys.fingerprintHex(), recipientKeys.ed25519PublicB64(), recipientKeys.x25519PublicB64());

        reinitializeContext();

        try (MockedStatic<SmtpClient> mockedSmtp = mockStatic(SmtpClient.class)) {
            SmtpClient smtp = mock(SmtpClient.class);
            mockedSmtp.when(() -> SmtpClient.connect(any())).thenReturn(smtp);

            SendMessageCmd cmd = new SendMessageCmd(context);
            cmd.password = "pass";
            cmd.to = "recipient@example.com";
            cmd.subject = "Hello";
            cmd.body = "World";

            cmd.run();

            verify(smtp).sendEncryptedMessage(
                    eq("sender@example.com"),
                    eq("recipient@example.com"),
                    eq("Hello"),
                    eq("World"),
                    any(),
                    eq(recipientKeys.fingerprintHex()),
                    eq(recipientKeys.x25519PublicB64())
            );
        }
    }

    @Test
    public void testSendMessage_ContactNotFound() throws Exception {
        setupInitializedProfile("sender@example.com");
        reinitializeContext();

        SendMessageCmd cmd = new SendMessageCmd(context);
        cmd.password = "pass";
        cmd.to = "unknown@example.com";
        cmd.subject = "Hello";
        cmd.body = "World";

        cmd.run();
        // Should print error and return, no exception
    }

    private void setupInitializedProfile(String email) throws Exception {
        ZkStore store = new ZkStore(email);
        store.ensure();

        java.nio.file.Files.writeString(
                tempDir.resolve(".zkemails").resolve("profile.config"),
                "{\"profiles\":[\"" + email + "\"],\"default\":\"" + email + "\"}"
        );

        Config cfg = new Config();
        cfg.email = email;
        cfg.smtp.host = "smtp.test";
        cfg.smtp.port = 587;
        cfg.smtp.username = email;
        store.writeJson("config.json", cfg);

        IdentityKeys.KeyBundle keys = IdentityKeys.generate();
        store.writeJson("keys.json", keys);
    }
}

