package me.toymail.zkemails.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/**
 * Toy E2E crypto:
 * - Message key: random 32 bytes
 * - Message encryption: AES-256-GCM
 * - Key wrap: X25519 ECDH + HKDF-SHA256 -> AES-256-GCM wraps message key
 * - Auth: Ed25519 signature over canonical bytes
 */
public final class CryptoBox {
    private static final Logger log = LoggerFactory.getLogger(CryptoBox.class);

    static {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
    }

    public record EncryptedPayload(
            String ephemX25519PubB64,
            String wrappedKeyB64,
            String wrappedKeyNonceB64,
            String msgNonceB64,
            String ciphertextB64,
            String sigB64,
            String recipientFpHex // store recipient's fingerprint, not sender's
    ) {}

    public static EncryptedPayload encryptToRecipient(String fromEmail, String toEmail, String subject,
                                                      String plaintext,
                                                      IdentityKeys.KeyBundle senderKeys,
                                                      String recipientFpHex,
                                                      String recipientX25519PubB64) throws Exception {

        byte[] msgKey = rand(32);
        byte[] msgNonce = rand(12);
        byte[] ct = aesGcmEncrypt(msgKey, msgNonce, aad(fromEmail, toEmail, subject),
                plaintext.getBytes(StandardCharsets.UTF_8));

        KeyPair ephem = x25519KeyPair();
        log.debug("[ENCRYPT] Ephemeral public key (raw): {}", Base64.getEncoder().encodeToString(ephem.getPublic().getEncoded()));

        PublicKey recipientPub = KeyFactory.getInstance("X25519", "BC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(recipientX25519PubB64)));

        byte[] shared = x25519SharedSecret(ephem.getPrivate(), recipientPub); // generated private key, bitsofbyte public key |

        // public key, bits of byte private key

        byte[] salt = sha256(("zkemails-wrap:" + recipientFpHex).getBytes(StandardCharsets.UTF_8));
        byte[] wrapKey = hkdfSha256(shared, salt, "wrap-key".getBytes(StandardCharsets.UTF_8), 32);

        byte[] wrapNonce = rand(12);
        byte[] wrapped = aesGcmEncrypt(wrapKey, wrapNonce, null, msgKey);

        byte[] toSign = concat(
                ephem.getPublic().getEncoded(),
                wrapped,
                wrapNonce,
                msgNonce,
                ct,
                aad(fromEmail, toEmail, subject)
        );
        byte[] sig = ed25519Sign(senderKeys.ed25519PrivateB64(), toSign); // signing with sayanr91 gmails private key

        return new EncryptedPayload(
                Base64.getEncoder().encodeToString(ephem.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(wrapped),
                Base64.getEncoder().encodeToString(wrapNonce),
                Base64.getEncoder().encodeToString(msgNonce),
                Base64.getEncoder().encodeToString(ct),
                Base64.getEncoder().encodeToString(sig),
                recipientFpHex // store recipient's fingerprint
        );
    }

    public static boolean verifySignature(String fromEmail, String toEmail, String subject,
                                          EncryptedPayload p,
                                          String senderEd25519PubB64) throws Exception {

        byte[] toVerify = concat(
                Base64.getDecoder().decode(p.ephemX25519PubB64()),
                Base64.getDecoder().decode(p.wrappedKeyB64()),
                Base64.getDecoder().decode(p.wrappedKeyNonceB64()),
                Base64.getDecoder().decode(p.msgNonceB64()),
                Base64.getDecoder().decode(p.ciphertextB64()),
                aad(fromEmail, toEmail, subject)
        );
        byte[] sig = Base64.getDecoder().decode(p.sigB64());
        return ed25519Verify(senderEd25519PubB64, toVerify, sig);
    }

    public static String decryptForRecipient(String fromEmail, String toEmail, String subject,
                                             EncryptedPayload p,
                                             String recipientX25519PrivB64,
                                             String senderEd25519PubB64) throws Exception {

        if (!verifySignature(fromEmail, toEmail, subject, p, senderEd25519PubB64)) {
            throw new SecurityException("Signature verification failed");
        }

        byte[] ephemPubBytes = Base64.getDecoder().decode(p.ephemX25519PubB64());
        log.debug("[DECRYPT] Ephemeral public key (raw): {}", Base64.getEncoder().encodeToString(ephemPubBytes));
        PublicKey ephemPub = KeyFactory.getInstance("X25519", "BC")
                .generatePublic(new X509EncodedKeySpec(ephemPubBytes));

        PrivateKey recipientPriv = KeyFactory.getInstance("X25519", "BC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(recipientX25519PrivB64))); // public key, bits of private key

        byte[] shared = x25519SharedSecret(recipientPriv, ephemPub);

        byte[] salt = sha256(("zkemails-wrap:" + p.recipientFpHex()).getBytes(StandardCharsets.UTF_8));
        byte[] wrapKey = hkdfSha256(shared, salt, "wrap-key".getBytes(StandardCharsets.UTF_8), 32);

        byte[] msgKey = aesGcmDecrypt(
                wrapKey,
                Base64.getDecoder().decode(p.wrappedKeyNonceB64()),
                null,
                Base64.getDecoder().decode(p.wrappedKeyB64())
        );

        byte[] pt = aesGcmDecrypt(
                msgKey,
                Base64.getDecoder().decode(p.msgNonceB64()),
                aad(fromEmail, toEmail, subject),
                Base64.getDecoder().decode(p.ciphertextB64())
        );
        return new String(pt, StandardCharsets.UTF_8);
    }

    private static byte[] aad(String from, String to, String subject) {
        String s = (from == null ? "" : from) + "\n" + (to == null ? "" : to) + "\n" + (subject == null ? "" : subject);
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static KeyPair x25519KeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519", "BC");
        return kpg.generateKeyPair();
    }

    private static byte[] x25519SharedSecret(PrivateKey priv, PublicKey pub) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("X25519", "BC");
        ka.init(priv);
        ka.doPhase(pub, true);
        return ka.generateSecret();
    }

    private static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(plaintext);
    }

    private static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(ciphertext);
    }

    private static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int len) throws Exception {
        byte[] prk = hmacSha256(salt == null ? new byte[32] : salt, ikm);
        byte[] okm = new byte[len];
        byte[] t = new byte[0];
        int pos = 0;
        byte counter = 1;
        while (pos < len) {
            byte[] data = concat(t, info == null ? new byte[0] : info, new byte[]{counter});
            t = hmacSha256(prk, data);
            int take = Math.min(t.length, len - pos);
            System.arraycopy(t, 0, okm, pos, take);
            pos += take;
            counter++;
        }
        return okm;
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] ed25519Sign(String edPrivB64, byte[] msg) throws Exception {
        PrivateKey priv = KeyFactory.getInstance("Ed25519", "BC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(edPrivB64)));
        Signature s = Signature.getInstance("Ed25519", "BC");
        s.initSign(priv);
        s.update(msg);
        return s.sign();
    }

    private static boolean ed25519Verify(String edPubB64, byte[] msg, byte[] sig) throws Exception {
        PublicKey pub = KeyFactory.getInstance("Ed25519", "BC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(edPubB64)));
        Signature v = Signature.getInstance("Ed25519", "BC");
        v.initVerify(pub);
        v.update(msg);
        return v.verify(sig);
    }

    private static byte[] sha256(byte[] in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(in);
    }

    private static byte[] rand(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += (p == null ? 0 : p.length);
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            if (p == null) continue;
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
