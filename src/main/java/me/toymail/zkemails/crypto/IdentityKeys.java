package me.toymail.zkemails.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.*;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

public final class IdentityKeys {

    static {
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
    }

    public record KeyBundle(
            String ed25519PublicB64,
            String ed25519PrivateB64,
            String x25519PublicB64,
            String x25519PrivateB64,
            String fingerprintHex
    ) {}

    public static KeyBundle generate() throws Exception {
        KeyPair ed = gen("Ed25519");
        KeyPair xk = gen("X25519");

        String fp = fingerprint(ed.getPublic().getEncoded(), xk.getPublic().getEncoded());

        return new KeyBundle(
                b64(ed.getPublic().getEncoded()),
                b64(ed.getPrivate().getEncoded()),
                b64(xk.getPublic().getEncoded()),
                b64(xk.getPrivate().getEncoded()),
                fp
        );
    }

    private static KeyPair gen(String alg) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(alg, "BC");
        return kpg.generateKeyPair();
    }

    private static String fingerprint(byte[] edPubDer, byte[] x25519PubDer) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(edPubDer);
        sha.update(x25519PubDer);
        return hex(sha.digest());
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
