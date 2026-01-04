package me.toymail.zkemails.crypto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CryptoBoxTest {
    @Test
    public void testEncryptDecryptRoundTrip() throws Exception {
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

        // Encrypt
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

        // Decrypt (should succeed)
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
    public void testSignatureVerificationFailsWithWrongKey() throws Exception {
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
}
