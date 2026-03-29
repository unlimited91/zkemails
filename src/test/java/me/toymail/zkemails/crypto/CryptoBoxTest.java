package me.toymail.zkemails.crypto;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CryptoBox encryption and decryption.
 *
 * After V1/V2 consolidation:
 * - V1 encryption (encryptToRecipient) is kept for backward compatibility testing
 * - V1 decryption (decryptForRecipient) MUST remain functional for decrypting old messages
 * - V2 is now the default for all new messages (even single recipient)
 */
public class CryptoBoxTest {

    private static IdentityKeys.KeyBundle sender;
    private static IdentityKeys.KeyBundle recipient;

    @BeforeAll
    static void setupKeys() throws Exception {
        sender = IdentityKeys.generate();
        recipient = IdentityKeys.generate();
    }

    // ==================== V1 Format Tests (Backward Compatibility) ====================

    @Test
    public void testV1_EncryptDecryptRoundTrip() throws Exception {
        // Generate sender and recipient keys
        IdentityKeys.KeyBundle sender = IdentityKeys.generate();
        IdentityKeys.KeyBundle recipient = IdentityKeys.generate();

        System.out.println("Sender X25519 pub: " + sender.x25519PublicB64());
        System.out.println("Sender X25519 priv: " + sender.x25519PrivateB64());
        System.out.println("Recipient X25519 pub: " + recipient.x25519PublicB64());
        System.out.println("Recipient X25519 priv: " + recipient.x25519PrivateB64());
        System.out.println("Sender fingerprint: " + sender.fingerprintHex());
        System.out.println("Recipient fingerprint: " + recipient.fingerprintHex());

        String fromEmail = "alice@example.com";
        String toEmail = "bob@example.com";
        String subject = "Test Subject";
        String plaintext = "Hello, this is a secret message!";

        // Encrypt using V1 format
        CryptoBox.EncryptedPayload payload = CryptoBox.encryptToRecipient(
                fromEmail,
                toEmail,
                subject,
                plaintext,
                sender,
                recipient.fingerprintHex(),
                recipient.x25519PublicB64()
        );

        System.out.println("Payload.ephemX25519PubB64: " + payload.ephemX25519PubB64());
        System.out.println("Payload.wrappedKeyB64: " + payload.wrappedKeyB64());
        System.out.println("Payload.wrappedKeyNonceB64: " + payload.wrappedKeyNonceB64());
        System.out.println("Payload.msgNonceB64: " + payload.msgNonceB64());
        System.out.println("Payload.ciphertextB64: " + payload.ciphertextB64());
        System.out.println("Payload.sigB64: " + payload.sigB64());
        System.out.println("Payload.recipientFpHex: " + payload.recipientFpHex());

        // Decrypt using V1 decryption (MUST work for backward compatibility)
        String decrypted = CryptoBox.decryptForRecipient(
                fromEmail,
                toEmail,
                subject,
                payload,
                recipient.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );
        assertEquals(plaintext, decrypted);
    }

    @Test
    public void testV1_SignatureVerificationFailsWithWrongKey() throws Exception {
        IdentityKeys.KeyBundle sender = IdentityKeys.generate();
        IdentityKeys.KeyBundle recipient = IdentityKeys.generate();
        IdentityKeys.KeyBundle attacker = IdentityKeys.generate();

        String fromEmail = "alice@example.com";
        String toEmail = "bob@example.com";
        String subject = "Test Subject";
        String plaintext = "Hello, this is a secret message!";

        CryptoBox.EncryptedPayload payload = CryptoBox.encryptToRecipient(
                fromEmail,
                toEmail,
                subject,
                plaintext,
                sender,
                recipient.fingerprintHex(),
                recipient.x25519PublicB64()
        );

        // Try to decrypt with wrong sender pubkey (should fail signature verification)
        Exception ex = assertThrows(Exception.class, () -> {
            CryptoBox.decryptForRecipient(
                    fromEmail,
                    toEmail,
                    subject,
                    payload,
                    recipient.x25519PrivateB64(),
                    attacker.ed25519PublicB64()
            );
        });
        assertTrue(ex.getMessage().toLowerCase().contains("signature"));
    }

    @Test
    public void testV1_WrongRecipientCannotDecrypt() throws Exception {
        IdentityKeys.KeyBundle sender = IdentityKeys.generate();
        IdentityKeys.KeyBundle recipient = IdentityKeys.generate();
        IdentityKeys.KeyBundle wrongRecipient = IdentityKeys.generate();

        CryptoBox.EncryptedPayload payload = CryptoBox.encryptToRecipient(
                "sender@test.com",
                "recipient@test.com",
                "Subject",
                "Secret message",
                sender,
                recipient.fingerprintHex(),
                recipient.x25519PublicB64()
        );

        // Wrong recipient should fail to decrypt
        assertThrows(Exception.class, () -> {
            CryptoBox.decryptForRecipient(
                    "sender@test.com",
                    "recipient@test.com",
                    "Subject",
                    payload,
                    wrongRecipient.x25519PrivateB64(),
                    sender.ed25519PublicB64()
            );
        });
    }

    @Test
    public void testV1_TamperedCiphertextFails() throws Exception {
        IdentityKeys.KeyBundle sender = IdentityKeys.generate();
        IdentityKeys.KeyBundle recipient = IdentityKeys.generate();

        CryptoBox.EncryptedPayload originalPayload = CryptoBox.encryptToRecipient(
                "sender@test.com",
                "recipient@test.com",
                "Subject",
                "Secret message",
                sender,
                recipient.fingerprintHex(),
                recipient.x25519PublicB64()
        );

        // Create tampered payload with modified ciphertext
        CryptoBox.EncryptedPayload tamperedPayload = new CryptoBox.EncryptedPayload(
                originalPayload.ephemX25519PubB64(),
                originalPayload.wrappedKeyB64(),
                originalPayload.wrappedKeyNonceB64(),
                originalPayload.msgNonceB64(),
                "dGFtcGVyZWQ=",  // "tampered" in base64
                originalPayload.sigB64(),
                originalPayload.recipientFpHex()
        );

        // Should fail signature verification
        Exception ex = assertThrows(Exception.class, () -> {
            CryptoBox.decryptForRecipient(
                    "sender@test.com",
                    "recipient@test.com",
                    "Subject",
                    tamperedPayload,
                    recipient.x25519PrivateB64(),
                    sender.ed25519PublicB64()
            );
        });
        assertTrue(ex.getMessage().toLowerCase().contains("signature"));
    }

    // ==================== V2 Format Tests (New Default) ====================

    @Test
    public void testV2_SingleRecipientEncryptDecrypt() throws Exception {
        IdentityKeys.KeyBundle sender = IdentityKeys.generate();
        IdentityKeys.KeyBundle recipient = IdentityKeys.generate();

        String plaintext = "V2 single recipient message";

        Map<String, String> recipientKeys = Map.of(
                recipient.fingerprintHex(), recipient.x25519PublicB64()
        );

        // Encrypt using V2 format (now the default for all messages)
        CryptoBox.EncryptedPayloadV2 payload = CryptoBox.encryptToMultipleRecipients(
                "sender@test.com",
                "recipient@test.com",
                "Subject",
                plaintext,
                sender,
                recipientKeys
        );

        // Verify V2 structure
        assertEquals(2, payload.version());
        assertEquals(1, payload.recipients().size());
        assertEquals(recipient.fingerprintHex(), payload.recipients().get(0).fpHex());

        // Decrypt
        String decrypted = CryptoBox.decryptFromMultipleRecipientMessage(
                "sender@test.com",
                "recipient@test.com",
                "Subject",
                payload,
                recipient.fingerprintHex(),
                recipient.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );

        assertEquals(plaintext, decrypted);
    }

    @Test
    public void testV2_MultipleRecipientsAllCanDecrypt() throws Exception {
        IdentityKeys.KeyBundle sender = IdentityKeys.generate();
        IdentityKeys.KeyBundle r1 = IdentityKeys.generate();
        IdentityKeys.KeyBundle r2 = IdentityKeys.generate();
        IdentityKeys.KeyBundle r3 = IdentityKeys.generate();

        String plaintext = "Message for multiple recipients";

        Map<String, String> recipientKeys = Map.of(
                r1.fingerprintHex(), r1.x25519PublicB64(),
                r2.fingerprintHex(), r2.x25519PublicB64(),
                r3.fingerprintHex(), r3.x25519PublicB64()
        );

        CryptoBox.EncryptedPayloadV2 payload = CryptoBox.encryptToMultipleRecipients(
                "sender@test.com",
                "r1@test.com",
                "Multi-recipient",
                plaintext,
                sender,
                recipientKeys
        );

        assertEquals(3, payload.recipients().size());

        // All recipients should be able to decrypt
        for (IdentityKeys.KeyBundle r : List.of(r1, r2, r3)) {
            String decrypted = CryptoBox.decryptFromMultipleRecipientMessage(
                    "sender@test.com",
                    "r1@test.com",
                    "Multi-recipient",
                    payload,
                    r.fingerprintHex(),
                    r.x25519PrivateB64(),
                    sender.ed25519PublicB64()
            );
            assertEquals(plaintext, decrypted);
        }
    }

    @Test
    public void testV2_NonRecipientCannotDecrypt() throws Exception {
        IdentityKeys.KeyBundle sender = IdentityKeys.generate();
        IdentityKeys.KeyBundle recipient = IdentityKeys.generate();
        IdentityKeys.KeyBundle nonRecipient = IdentityKeys.generate();

        CryptoBox.EncryptedPayloadV2 payload = CryptoBox.encryptToMultipleRecipients(
                "sender@test.com",
                "recipient@test.com",
                "Subject",
                "Secret",
                sender,
                Map.of(recipient.fingerprintHex(), recipient.x25519PublicB64())
        );

        // Non-recipient should fail
        SecurityException ex = assertThrows(SecurityException.class, () -> {
            CryptoBox.decryptFromMultipleRecipientMessage(
                    "sender@test.com",
                    "recipient@test.com",
                    "Subject",
                    payload,
                    nonRecipient.fingerprintHex(),
                    nonRecipient.x25519PrivateB64(),
                    sender.ed25519PublicB64()
            );
        });
        assertTrue(ex.getMessage().contains("not encrypted for this recipient"));
    }

    @Test
    public void testV2_SharedCiphertextDifferentWrappedKeys() throws Exception {
        IdentityKeys.KeyBundle sender = IdentityKeys.generate();
        IdentityKeys.KeyBundle r1 = IdentityKeys.generate();
        IdentityKeys.KeyBundle r2 = IdentityKeys.generate();

        Map<String, String> recipientKeys = Map.of(
                r1.fingerprintHex(), r1.x25519PublicB64(),
                r2.fingerprintHex(), r2.x25519PublicB64()
        );

        CryptoBox.EncryptedPayloadV2 payload = CryptoBox.encryptToMultipleRecipients(
                "sender@test.com",
                "r1@test.com",
                "Subject",
                "Plaintext",
                sender,
                recipientKeys
        );

        // There's ONE ciphertext but TWO wrapped keys
        assertNotNull(payload.ciphertextB64());
        assertEquals(2, payload.recipients().size());

        // Wrapped keys should be different (encrypted with different recipient keys)
        String wrappedKey1 = payload.recipients().get(0).wrappedKeyB64();
        String wrappedKey2 = payload.recipients().get(1).wrappedKeyB64();
        assertNotEquals(wrappedKey1, wrappedKey2);
    }

    // ==================== Interoperability Tests ====================

    @Test
    public void testV1AndV2ProduceDifferentFormats() throws Exception {
        IdentityKeys.KeyBundle sender = IdentityKeys.generate();
        IdentityKeys.KeyBundle recipient = IdentityKeys.generate();

        String plaintext = "Test message";

        // V1 format
        CryptoBox.EncryptedPayload v1Payload = CryptoBox.encryptToRecipient(
                "sender@test.com",
                "recipient@test.com",
                "Subject",
                plaintext,
                sender,
                recipient.fingerprintHex(),
                recipient.x25519PublicB64()
        );

        // V2 format
        CryptoBox.EncryptedPayloadV2 v2Payload = CryptoBox.encryptToMultipleRecipients(
                "sender@test.com",
                "recipient@test.com",
                "Subject",
                plaintext,
                sender,
                Map.of(recipient.fingerprintHex(), recipient.x25519PublicB64())
        );

        // V1 has wrappedKey directly in payload
        assertNotNull(v1Payload.wrappedKeyB64());

        // V2 has recipients list with wrapped keys
        assertEquals(2, v2Payload.version());
        assertEquals(1, v2Payload.recipients().size());
        assertNotNull(v2Payload.recipients().get(0).wrappedKeyB64());

        // Both should decrypt to same plaintext
        String decryptedV1 = CryptoBox.decryptForRecipient(
                "sender@test.com",
                "recipient@test.com",
                "Subject",
                v1Payload,
                recipient.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );

        String decryptedV2 = CryptoBox.decryptFromMultipleRecipientMessage(
                "sender@test.com",
                "recipient@test.com",
                "Subject",
                v2Payload,
                recipient.fingerprintHex(),
                recipient.x25519PrivateB64(),
                sender.ed25519PublicB64()
        );

        assertEquals(plaintext, decryptedV1);
        assertEquals(plaintext, decryptedV2);
    }
}
