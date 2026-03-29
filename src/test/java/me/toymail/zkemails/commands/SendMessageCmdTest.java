package me.toymail.zkemails.commands;

import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.CryptoBox;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.ZkStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Tests for SendMessageCmd verifying V2 format is used for all messages.
 * After V1/V2 consolidation, all messages use V2 format regardless of recipient count.
 */
public class SendMessageCmdTest extends CommandTestBase {

    @Test
    public void testSendMessage_SingleRecipient_UsesV2Format() throws Exception {
        setupInitializedProfile("sender@example.com");
        addContact("recipient@example.com");
        reinitializeContext();

        try (MockedStatic<SmtpClient> mockedSmtp = mockStatic(SmtpClient.class)) {
            SmtpClient smtp = mock(SmtpClient.class);
            mockedSmtp.when(() -> SmtpClient.connect(any())).thenReturn(smtp);
            when(smtp.sendEncryptedMultiRecipientMessageWithAttachments(
                    any(), any(), any(), any(), any(), any(), any(), any()
            )).thenReturn("<test-message-id@example.com>");

            SendMessageCmd cmd = new SendMessageCmd(context);
            cmd.password = "pass";
            cmd.toEmails = List.of("recipient@example.com");
            cmd.subject = "Hello";
            cmd.body = "World";

            cmd.run();

            // Single recipient now uses V2 format (not V1)
            verify(smtp).sendEncryptedMultiRecipientMessageWithAttachments(
                    eq("sender@example.com"),
                    eq(List.of("recipient@example.com")),
                    isNull(),  // ccEmails
                    eq("Hello"),
                    any(CryptoBox.EncryptedMessageWithAttachmentsV2.class),
                    isNull(),  // inReplyTo
                    isNull(),  // references
                    any()      // threadId (generated)
            );
        }
    }

    @Test
    public void testSendMessage_SingleRecipient_PayloadHasV2Version() throws Exception {
        setupInitializedProfile("sender@example.com");
        IdentityKeys.KeyBundle recipientKeys = addContact("recipient@example.com");
        reinitializeContext();

        try (MockedStatic<SmtpClient> mockedSmtp = mockStatic(SmtpClient.class)) {
            SmtpClient smtp = mock(SmtpClient.class);
            mockedSmtp.when(() -> SmtpClient.connect(any())).thenReturn(smtp);

            ArgumentCaptor<CryptoBox.EncryptedMessageWithAttachmentsV2> payloadCaptor =
                    ArgumentCaptor.forClass(CryptoBox.EncryptedMessageWithAttachmentsV2.class);

            when(smtp.sendEncryptedMultiRecipientMessageWithAttachments(
                    any(), any(), any(), any(), payloadCaptor.capture(), any(), any(), any()
            )).thenReturn("<test-message-id@example.com>");

            SendMessageCmd cmd = new SendMessageCmd(context);
            cmd.password = "pass";
            cmd.toEmails = List.of("recipient@example.com");
            cmd.subject = "Test Subject";
            cmd.body = "Test Body";

            cmd.run();

            // Verify payload is V2 format with version=2
            CryptoBox.EncryptedMessageWithAttachmentsV2 payload = payloadCaptor.getValue();
            assertNotNull(payload);
            assertEquals(2, payload.textPayload().version());
            assertEquals(1, payload.textPayload().recipients().size());
            assertEquals(recipientKeys.fingerprintHex(),
                    payload.textPayload().recipients().get(0).fpHex());
        }
    }

    @Test
    public void testSendMessage_ContactNotFound() throws Exception {
        setupInitializedProfile("sender@example.com");
        reinitializeContext();

        SendMessageCmd cmd = new SendMessageCmd(context);
        cmd.password = "pass";
        cmd.toEmails = List.of("unknown@example.com");
        cmd.subject = "Hello";
        cmd.body = "World";

        // Should not throw, just log error
        cmd.run();
    }

    @Test
    public void testSendMessage_NoActiveProfile() throws Exception {
        // Don't setup profile
        SendMessageCmd cmd = new SendMessageCmd(context);
        cmd.password = "pass";
        cmd.toEmails = List.of("recipient@example.com");
        cmd.subject = "Hello";
        cmd.body = "World";

        // Should not throw, just log error
        cmd.run();
    }

    // ==================== Helper Methods ====================

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

    private IdentityKeys.KeyBundle addContact(String email) throws Exception {
        ZkStore store = new ZkStore("sender@example.com");
        ContactsStore contacts = new ContactsStore(store);
        IdentityKeys.KeyBundle keys = IdentityKeys.generate();
        contacts.upsertKeys(email, "verified",
                keys.fingerprintHex(), keys.ed25519PublicB64(), keys.x25519PublicB64());
        return keys;
    }
}
