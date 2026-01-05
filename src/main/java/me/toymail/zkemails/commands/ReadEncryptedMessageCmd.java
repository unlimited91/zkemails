package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.crypto.CryptoBox;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ContactsStore;
import me.toymail.zkemails.store.StoreContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.*;

@Command(name = "rem", description = "Read encrypted messages (list or decrypt by messageId)")
public class ReadEncryptedMessageCmd implements Runnable {
    private final StoreContext context;

    public ReadEncryptedMessageCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names = "--password", required = true, interactive = true, description = "App password / password (not saved)")
    String password;

    @Option(names = "--message", description = "Show decrypted message by messageId")
    String messageId;

    @Option(names = "--limit", defaultValue = "20")
    int limit;

    @Override
    public void run() {
        if (!context.hasActiveProfile()) {
            System.err.println("No active profile set or profile directory missing. Use 'prof' to set a profile.");
            return;
        }
        Config cfg;
        IdentityKeys.KeyBundle myKeys;
        try {
            cfg = context.zkStore().readJson("config.json", Config.class);
            myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);
        } catch (IOException e) {
            System.err.println("❌ Error reading config or keys: " + e.getMessage());
            return;
        }
        if (cfg == null) {
            System.err.println("❌ Not initialized. Run: zkemails init ...");
            return;
        }
        if (myKeys == null) {
            System.err.println("❌ Missing keys.json. Re-run init.");
            return;
        }
        try (ImapClient imap = ImapClient.connect(new ImapClient.ImapConfig(cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, password))) {
            if (messageId == null) {
                // rem list
                List<ImapClient.MailSummary> msgs = imap.searchHeaderEquals("X-ZKEmails-Type", "msg", limit);
                if (msgs.isEmpty()) {
                    System.out.println("No encrypted messages found.");
                    return;
                }
                for (var m : msgs) {
                    Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(m.uid());
                    String sig = first(hdrs, "X-ZKEmails-Sig");
                    System.out.printf("messageID=%s | UID=%d | from=%s | subject=%s | date=%s\n", sig, m.uid(), m.from(), m.subject(), m.received());
                }
            } else {
                // rem --message <messageId>
                List<ImapClient.MailSummary> msgs = imap.searchHeaderEquals("X-ZKEmails-Type", "msg", 100);
                ImapClient.MailSummary found = null;
                Map<String, List<String>> foundHdrs = null;
                for (var m : msgs) {
                    Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(m.uid());
                    String sig = first(hdrs, "X-ZKEmails-Sig");
                    if (messageId.equals(sig)) {
                        found = m;
                        foundHdrs = hdrs;
                        break;
                    }
                }
                if (found == null) {
                    System.err.println("No message found with messageId=" + messageId);
                    return;
                }
                // Extract encryption headers
                String ephemX25519PubB64 = first(foundHdrs, "X-ZKEmails-Ephem-X25519");
                String wrappedKeyB64 = first(foundHdrs, "X-ZKEmails-WrappedKey");
                String wrappedKeyNonceB64 = first(foundHdrs, "X-ZKEmails-WrappedKey-Nonce");
                String msgNonceB64 = first(foundHdrs, "X-ZKEmails-Nonce");
                String ciphertextB64 = first(foundHdrs, "X-ZKEmails-Ciphertext");
                String sigB64 = first(foundHdrs, "X-ZKEmails-Sig");
                String senderFpHex = first(foundHdrs, "X-ZKEmails-Sender-Fp");
                String recipientFpHex = first(foundHdrs, "X-ZKEmails-Recipient-Fp");
                String fromEmail = found.from();
                String toEmail = cfg.email;
                String subject = found.subject();
                ContactsStore.Contact c = context.contacts().get(fromEmail);
                // Check for missing or empty headers
//                Map<String, String> required = new LinkedHashMap<>();
//                required.put("X-ZKEmails-Ephem-X25519", ephemX25519PubB64);
//                required.put("X-ZKEmails-WrappedKey", wrappedKeyB64);
//                required.put("X-ZKEmails-WrappedKey-Nonce", wrappedKeyNonceB64);
//                required.put("X-ZKEmails-Nonce", msgNonceB64);
//                required.put("X-ZKEmails-Ciphertext", ciphertextB64);
//                required.put("X-ZKEmails-Sig", sigB64);
//                required.put("X-ZKEmails-Sender-Fp", senderFpHex);
//                boolean missing = false;
//                for (var entry : required.entrySet()) {
//                    String val = entry.getValue();
//                    if (val == null || val.trim().isEmpty()) {
//                        System.err.println("❌ Missing or empty header: " + entry.getKey() + " (value='" + val + "')");
//                        missing = true;
//                    }
//                }
//                if (missing) {
//                    System.err.println("Available headers:");
//                    for (var k : foundHdrs.keySet()) {
//                        System.err.println("  " + k + ": " + foundHdrs.get(k));
//                    }
//                    return;
//                }
//                String senderEd25519PubB64 = first(foundHdrs, "X-ZKEmails-PubKey-Ed25519");
//                if (senderEd25519PubB64 == null) {
//                    System.err.println("⚠️  Warning: Sender's Ed25519 public key (X-ZKEmails-PubKey-Ed25519) not found in headers. Signature verification will be skipped.");
//                }
                try {
                    CryptoBox.EncryptedPayload payload = new CryptoBox.EncryptedPayload(
                            ephemX25519PubB64, wrappedKeyB64, wrappedKeyNonceB64, msgNonceB64, ciphertextB64, sigB64, recipientFpHex
                    );
                    String plaintext = CryptoBox.decryptForRecipient(
                            fromEmail,
                            toEmail,
                            subject,
                            payload,
                            myKeys.x25519PrivateB64(),
                            c.ed25519PublicB64
//                            senderEd25519PubB64 // use sender's pubkey if available, else null
                    );
                    System.out.println("Decrypted message:\n" + plaintext);
                } catch (Exception e) {
                    System.err.println("❌ Decryption failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    private static String first(Map<String, List<String>> map, String key) {
        List<String> v = map.get(key);
        return (v != null && !v.isEmpty()) ? v.get(0) : null;
    }
}
