package me.toymail.zkemails.crypto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for multi-recipient attachment encryption/decryption (V2 format).
 */
public class MultiRecipientAttachmentTest {

    private static IdentityKeys.KeyBundle sender;
    private static IdentityKeys.KeyBundle recipient1;
    private static IdentityKeys.KeyBundle recipient2;
    private static IdentityKeys.KeyBundle recipient3;
    private static IdentityKeys.KeyBundle nonRecipient;

    @BeforeAll
    static void setup() throws Exception {
        sender = IdentityKeys.generate();
        recipient1 = IdentityKeys.generate();
        recipient2 = IdentityKeys.generate();
        recipient3 = IdentityKeys.generate();
        nonRecipient = IdentityKeys.generate();
    }

    // ==================== Happy Path Tests ====================

    @Test
    void testMultipleRecipientsWithSingleAttachment() throws Exception {
        String plaintext = "Secret message with attachment";
        byte[] attachmentData = "This is the attachment content".getBytes(StandardCharsets.UTF_8);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("test.txt", "text/plain", attachmentData)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64(),
                recipient2.fingerprintHex(), recipient2.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient1@test.com", "Test Subject",
                plaintext, attachments, sender, recipientKeys
        );

        assertNotNull(encrypted.textPayload());
        assertEquals(2, encrypted.textPayload().version());
        assertEquals(2, encrypted.textPayload().recipients().size());
        assertEquals(1, encrypted.attachments().size());

        // Recipient 1 can decrypt
        CryptoBox.DecryptedMessageWithAttachments decrypted1 = CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                "sender@test.com", "recipient1@test.com", "Test Subject",
                encrypted.textPayload(), encrypted.attachments(),
                recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );

        assertEquals(plaintext, decrypted1.plaintext());
        assertEquals(1, decrypted1.attachments().size());
        assertArrayEquals(attachmentData, decrypted1.attachments().get(0).data());

        // Recipient 2 can decrypt
        CryptoBox.DecryptedMessageWithAttachments decrypted2 = CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                "sender@test.com", "recipient1@test.com", "Test Subject",
                encrypted.textPayload(), encrypted.attachments(),
                recipient2.fingerprintHex(), recipient2.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );

        assertEquals(plaintext, decrypted2.plaintext());
        assertEquals(1, decrypted2.attachments().size());
        assertArrayEquals(attachmentData, decrypted2.attachments().get(0).data());
    }

    @Test
    void testMultipleAttachments() throws Exception {
        String plaintext = "Message with multiple attachments";
        byte[] attachment1Data = "First attachment".getBytes(StandardCharsets.UTF_8);
        byte[] attachment2Data = "Second attachment with more content".getBytes(StandardCharsets.UTF_8);
        byte[] attachment3Data = new byte[1024]; // Binary data
        new Random().nextBytes(attachment3Data);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("file1.txt", "text/plain", attachment1Data),
                new CryptoBox.AttachmentInput("file2.txt", "text/plain", attachment2Data),
                new CryptoBox.AttachmentInput("binary.dat", "application/octet-stream", attachment3Data)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Multiple Attachments",
                plaintext, attachments, sender, recipientKeys
        );

        assertEquals(3, encrypted.attachments().size());

        CryptoBox.DecryptedMessageWithAttachments decrypted = CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                "sender@test.com", "recipient@test.com", "Multiple Attachments",
                encrypted.textPayload(), encrypted.attachments(),
                recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );

        assertEquals(plaintext, decrypted.plaintext());
        assertEquals(3, decrypted.attachments().size());
        assertArrayEquals(attachment1Data, decrypted.attachments().get(0).data());
        assertArrayEquals(attachment2Data, decrypted.attachments().get(1).data());
        assertArrayEquals(attachment3Data, decrypted.attachments().get(2).data());
    }

    @Test
    void testThreeRecipientsAllCanDecrypt() throws Exception {
        String plaintext = "Message for three recipients";
        byte[] attachmentData = "Shared attachment".getBytes(StandardCharsets.UTF_8);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("shared.txt", "text/plain", attachmentData)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64(),
                recipient2.fingerprintHex(), recipient2.x25519PublicB64(),
                recipient3.fingerprintHex(), recipient3.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient1@test.com", "Three Recipients",
                plaintext, attachments, sender, recipientKeys
        );

        assertEquals(3, encrypted.textPayload().recipients().size());

        // All three recipients can decrypt
        for (IdentityKeys.KeyBundle recipient : List.of(recipient1, recipient2, recipient3)) {
            CryptoBox.DecryptedMessageWithAttachments decrypted = CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                    "sender@test.com", "recipient1@test.com", "Three Recipients",
                    encrypted.textPayload(), encrypted.attachments(),
                    recipient.fingerprintHex(), recipient.x25519PrivateB64(),
                    sender.ed25519PublicB64()
            );

            assertEquals(plaintext, decrypted.plaintext());
            assertArrayEquals(attachmentData, decrypted.attachments().get(0).data());
        }
    }

    // ==================== Edge Case: Empty/Null Attachments ====================

    @Test
    void testNoAttachments() throws Exception {
        String plaintext = "Message without attachments";

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64()
        );

        // Empty list
        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "No Attachments",
                plaintext, List.of(), sender, recipientKeys
        );

        assertTrue(encrypted.attachments().isEmpty());

        CryptoBox.DecryptedMessageWithAttachments decrypted = CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                "sender@test.com", "recipient@test.com", "No Attachments",
                encrypted.textPayload(), encrypted.attachments(),
                recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );

        assertEquals(plaintext, decrypted.plaintext());
        assertTrue(decrypted.attachments().isEmpty());
    }

    @Test
    void testNullAttachmentsList() throws Exception {
        String plaintext = "Message with null attachments";

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Null Attachments",
                plaintext, null, sender, recipientKeys
        );

        assertTrue(encrypted.attachments().isEmpty());

        CryptoBox.DecryptedMessageWithAttachments decrypted = CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                "sender@test.com", "recipient@test.com", "Null Attachments",
                encrypted.textPayload(), null,
                recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );

        assertEquals(plaintext, decrypted.plaintext());
        assertTrue(decrypted.attachments().isEmpty());
    }

    // ==================== Edge Case: Single Recipient (V2 format) ====================

    @Test
    void testSingleRecipientV2Format() throws Exception {
        String plaintext = "Single recipient V2 message";
        byte[] attachmentData = "Attachment for one".getBytes(StandardCharsets.UTF_8);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("single.txt", "text/plain", attachmentData)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Single Recipient V2",
                plaintext, attachments, sender, recipientKeys
        );

        assertEquals(1, encrypted.textPayload().recipients().size());

        CryptoBox.DecryptedMessageWithAttachments decrypted = CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                "sender@test.com", "recipient@test.com", "Single Recipient V2",
                encrypted.textPayload(), encrypted.attachments(),
                recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );

        assertEquals(plaintext, decrypted.plaintext());
        assertArrayEquals(attachmentData, decrypted.attachments().get(0).data());
    }

    // ==================== Security Tests: Non-recipient Cannot Decrypt ====================

    @Test
    void testNonRecipientCannotDecrypt() throws Exception {
        String plaintext = "Secret message";
        byte[] attachmentData = "Secret attachment".getBytes(StandardCharsets.UTF_8);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("secret.txt", "text/plain", attachmentData)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64(),
                recipient2.fingerprintHex(), recipient2.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Secret",
                plaintext, attachments, sender, recipientKeys
        );

        // Non-recipient should fail to find their wrapped key
        SecurityException ex = assertThrows(SecurityException.class, () -> {
            CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                    "sender@test.com", "recipient@test.com", "Secret",
                    encrypted.textPayload(), encrypted.attachments(),
                    nonRecipient.fingerprintHex(), nonRecipient.x25519PrivateB64(),
                    sender.ed25519PublicB64()
            );
        });

        assertTrue(ex.getMessage().contains("not encrypted for this recipient"));
    }

    // ==================== Security Tests: Signature Verification ====================

    @Test
    void testWrongSenderKeyFailsSignatureVerification() throws Exception {
        String plaintext = "Signed message";
        byte[] attachmentData = "Signed attachment".getBytes(StandardCharsets.UTF_8);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("signed.txt", "text/plain", attachmentData)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Signed",
                plaintext, attachments, sender, recipientKeys
        );

        // Try to decrypt with wrong sender's public key (attacker trying to impersonate)
        SecurityException ex = assertThrows(SecurityException.class, () -> {
            CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                    "sender@test.com", "recipient@test.com", "Signed",
                    encrypted.textPayload(), encrypted.attachments(),
                    recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                    nonRecipient.ed25519PublicB64()  // Wrong key!
            );
        });

        assertTrue(ex.getMessage().toLowerCase().contains("signature"));
    }

    @Test
    void testTamperedCiphertextFailsSignatureVerification() throws Exception {
        String plaintext = "Original message";
        byte[] attachmentData = "Original attachment".getBytes(StandardCharsets.UTF_8);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("original.txt", "text/plain", attachmentData)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Tamper Test",
                plaintext, attachments, sender, recipientKeys
        );

        // Tamper with the ciphertext
        String tamperedCiphertext = Base64.getEncoder().encodeToString(
                "tampered".getBytes(StandardCharsets.UTF_8)
        );
        CryptoBox.EncryptedPayloadV2 tamperedPayload = new CryptoBox.EncryptedPayloadV2(
                encrypted.textPayload().version(),
                encrypted.textPayload().enc(),
                encrypted.textPayload().senderFpHex(),
                encrypted.textPayload().ephemX25519PubB64(),
                encrypted.textPayload().msgNonceB64(),
                tamperedCiphertext,  // Tampered!
                encrypted.textPayload().sigB64(),
                encrypted.textPayload().recipients()
        );

        SecurityException ex = assertThrows(SecurityException.class, () -> {
            CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                    "sender@test.com", "recipient@test.com", "Tamper Test",
                    tamperedPayload, encrypted.attachments(),
                    recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                    sender.ed25519PublicB64()
            );
        });

        assertTrue(ex.getMessage().toLowerCase().contains("signature"));
    }

    @Test
    void testTamperedAttachmentFailsSignatureVerification() throws Exception {
        String plaintext = "Message with attachment";
        byte[] attachmentData = "Original attachment data".getBytes(StandardCharsets.UTF_8);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("file.txt", "text/plain", attachmentData)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Attachment Tamper",
                plaintext, attachments, sender, recipientKeys
        );

        // Tamper with the attachment
        CryptoBox.EncryptedAttachment original = encrypted.attachments().get(0);
        CryptoBox.EncryptedAttachment tamperedAttachment = new CryptoBox.EncryptedAttachment(
                original.filename(),
                original.contentType(),
                original.originalSize(),
                Base64.getEncoder().encodeToString("tampered".getBytes()),  // Tampered ciphertext
                original.attachmentNonceB64()
        );

        SecurityException ex = assertThrows(SecurityException.class, () -> {
            CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                    "sender@test.com", "recipient@test.com", "Attachment Tamper",
                    encrypted.textPayload(), List.of(tamperedAttachment),
                    recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                    sender.ed25519PublicB64()
            );
        });

        // Signature verification should fail because attachment hash changed
        assertTrue(ex.getMessage().toLowerCase().contains("signature"));
    }

    @Test
    void testMissingAttachmentFailsSignatureVerification() throws Exception {
        String plaintext = "Message with attachment";
        byte[] attachmentData = "Attachment data".getBytes(StandardCharsets.UTF_8);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("file.txt", "text/plain", attachmentData)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Missing Attachment",
                plaintext, attachments, sender, recipientKeys
        );

        // Try to decrypt with empty attachments list (attachment was "lost" or stripped)
        SecurityException ex = assertThrows(SecurityException.class, () -> {
            CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                    "sender@test.com", "recipient@test.com", "Missing Attachment",
                    encrypted.textPayload(), List.of(),  // Missing attachment!
                    recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                    sender.ed25519PublicB64()
            );
        });

        assertTrue(ex.getMessage().toLowerCase().contains("signature"));
    }

    // ==================== Edge Case: Large Attachment ====================

    @Test
    void testLargeAttachment() throws Exception {
        String plaintext = "Message with large attachment";

        // 1MB attachment
        byte[] largeAttachment = new byte[1024 * 1024];
        new Random(42).nextBytes(largeAttachment);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("large.bin", "application/octet-stream", largeAttachment)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64(),
                recipient2.fingerprintHex(), recipient2.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Large Attachment",
                plaintext, attachments, sender, recipientKeys
        );

        CryptoBox.DecryptedMessageWithAttachments decrypted = CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                "sender@test.com", "recipient@test.com", "Large Attachment",
                encrypted.textPayload(), encrypted.attachments(),
                recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );

        assertEquals(plaintext, decrypted.plaintext());
        assertEquals(1, decrypted.attachments().size());
        assertEquals(largeAttachment.length, decrypted.attachments().get(0).originalSize());
        assertArrayEquals(largeAttachment, decrypted.attachments().get(0).data());
    }

    // ==================== Edge Case: Wrong AAD (email/subject mismatch) ====================

    @Test
    void testWrongFromEmailFailsDecryption() throws Exception {
        String plaintext = "AAD bound message";
        byte[] attachmentData = "Attachment".getBytes(StandardCharsets.UTF_8);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("file.txt", "text/plain", attachmentData)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Subject",
                plaintext, attachments, sender, recipientKeys
        );

        // Try to decrypt with wrong from email
        assertThrows(Exception.class, () -> {
            CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                    "wrong-sender@test.com",  // Wrong!
                    "recipient@test.com", "Subject",
                    encrypted.textPayload(), encrypted.attachments(),
                    recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                    sender.ed25519PublicB64()
            );
        });
    }

    @Test
    void testWrongSubjectFailsDecryption() throws Exception {
        String plaintext = "AAD bound message";
        byte[] attachmentData = "Attachment".getBytes(StandardCharsets.UTF_8);

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput("file.txt", "text/plain", attachmentData)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Original Subject",
                plaintext, attachments, sender, recipientKeys
        );

        // Try to decrypt with wrong subject
        assertThrows(Exception.class, () -> {
            CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                    "sender@test.com", "recipient@test.com",
                    "Wrong Subject",  // Wrong!
                    encrypted.textPayload(), encrypted.attachments(),
                    recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                    sender.ed25519PublicB64()
            );
        });
    }

    // ==================== Edge Case: Input Validation ====================

    @Test
    void testEmptyRecipientMapThrows() {
        String plaintext = "Message";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            CryptoBox.encryptToMultipleRecipientsWithAttachments(
                    "sender@test.com", "recipient@test.com", "Subject",
                    plaintext, null, sender, Map.of()  // Empty map!
            );
        });

        assertTrue(ex.getMessage().contains("recipient"));
    }

    @Test
    void testNullRecipientMapThrows() {
        String plaintext = "Message";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            CryptoBox.encryptToMultipleRecipientsWithAttachments(
                    "sender@test.com", "recipient@test.com", "Subject",
                    plaintext, null, sender, null  // Null map!
            );
        });

        assertTrue(ex.getMessage().contains("recipient"));
    }

    // ==================== Edge Case: Attachment Metadata Preserved ====================

    @Test
    void testAttachmentMetadataPreserved() throws Exception {
        String plaintext = "Message";
        byte[] data = "Content".getBytes(StandardCharsets.UTF_8);
        String filename = "test-file-名前.txt";  // Unicode filename
        String contentType = "text/plain; charset=utf-8";

        List<CryptoBox.AttachmentInput> attachments = List.of(
                new CryptoBox.AttachmentInput(filename, contentType, data)
        );

        Map<String, String> recipientKeys = Map.of(
                recipient1.fingerprintHex(), recipient1.x25519PublicB64()
        );

        CryptoBox.EncryptedMessageWithAttachmentsV2 encrypted = CryptoBox.encryptToMultipleRecipientsWithAttachments(
                "sender@test.com", "recipient@test.com", "Metadata Test",
                plaintext, attachments, sender, recipientKeys
        );

        // Check encrypted attachment metadata
        CryptoBox.EncryptedAttachment encAtt = encrypted.attachments().get(0);
        assertEquals(filename, encAtt.filename());
        assertEquals(contentType, encAtt.contentType());
        assertEquals(data.length, encAtt.originalSize());

        // Decrypt and verify metadata preserved
        CryptoBox.DecryptedMessageWithAttachments decrypted = CryptoBox.decryptFromMultipleRecipientMessageWithAttachments(
                "sender@test.com", "recipient@test.com", "Metadata Test",
                encrypted.textPayload(), encrypted.attachments(),
                recipient1.fingerprintHex(), recipient1.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );

        CryptoBox.DecryptedAttachment decAtt = decrypted.attachments().get(0);
        assertEquals(filename, decAtt.filename());
        assertEquals(contentType, decAtt.contentType());
        assertEquals(data.length, decAtt.originalSize());
        assertArrayEquals(data, decAtt.data());
    }
}
