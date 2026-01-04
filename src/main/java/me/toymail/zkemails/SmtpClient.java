package me.toymail.zkemails;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import me.toymail.zkemails.crypto.CryptoBox;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.InviteStore;

public final class SmtpClient implements AutoCloseable {

    public record SmtpConfig(String host, int port, String username, String password) {}

    private final Session session;

    private SmtpClient(Session session) {
        this.session = session;
    }

    public static SmtpClient connect(SmtpConfig cfg) {
        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.host", cfg.host());
        props.put("mail.smtp.port", String.valueOf(cfg.port()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");

        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "20000");
        props.put("mail.smtp.writetimeout", "20000");
        props.put("mail.smtp.ssl.checkserveridentity", "true");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(cfg.username(), cfg.password());
            }
        });
        // session.setDebug(true);
        return new SmtpClient(session);
    }

    @Override public void close() { }

    public void testLogin() throws MessagingException {
        Transport t = null;
        try {
            t = session.getTransport("smtp");
            t.connect();
        } finally {
            if (t != null) t.close();
        }
    }

    public String sendInvite(String fromEmail, String toEmail,
                             IdentityKeys.KeyBundle senderKeys,
                             InviteStore inviteStore) throws MessagingException {
        String inviteId = UUID.randomUUID().toString();
        String subject = "🔒 Private chat? (zkemails)";
        String body =
                "Your friend wants to chat in private using zkemails.\n\n" +
                        "If you want to enable encrypted emails:\n" +
                        "1) Install zkemails\n" +
                        "2) Reply to this email with: yes satoshi\n\n" +
                        "(This is a toy protocol invite.)\n";

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromEmail));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false));
        msg.setSubject(subject, "UTF-8");
        msg.setSentDate(new Date());
        msg.setText(body, "UTF-8");

        msg.setHeader("X-ZKEmails-Type", "invite");
        msg.setHeader("X-ZKEmails-Invite-Id", inviteId);

        // key gossip from inviter (so receiver can TOFU-pin on ack)
        msg.setHeader("X-ZKEmails-Fingerprint", senderKeys.fingerprintHex());
        msg.setHeader("X-ZKEmails-PubKey-Ed25519", senderKeys.ed25519PublicB64());
        msg.setHeader("X-ZKEmails-PubKey-X25519", senderKeys.x25519PublicB64());

        Transport.send(msg);

        try {
            if (inviteStore != null) inviteStore.addOutgoing(inviteId, fromEmail, toEmail, subject);
        } catch (Exception e) {
            System.err.println("⚠️ Sent invite but failed to persist invites.json: " + e.getMessage());
        }
        return inviteId;
    }

    public void sendAcceptWithKeys(String fromEmail, String toEmail, String inviteId,
                                   String fpHex, String edPubB64, String xPubB64) throws MessagingException {

        String subject = "Re: 🔒 Private chat? (zkemails)";
        String body = "yes satoshi";

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromEmail));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false));
        msg.setSubject(subject, "UTF-8");
        msg.setSentDate(new Date());
        msg.setText(body, "UTF-8");

        msg.setHeader("X-ZKEmails-Type", "accept");
        msg.setHeader("X-ZKEmails-Invite-Id", inviteId);

        msg.setHeader("X-ZKEmails-Fingerprint", fpHex);
        msg.setHeader("X-ZKEmails-PubKey-Ed25519", edPubB64);
        msg.setHeader("X-ZKEmails-PubKey-X25519", xPubB64);

        Transport.send(msg);
    }

    public void sendEncryptedMessage(String fromEmail, String toEmail, String subject, String plaintext,
                                     IdentityKeys.KeyBundle senderKeys,
                                     String recipientFpHex, String recipientXPubB64) throws Exception {

        CryptoBox.EncryptedPayload p = CryptoBox.encryptToRecipient(
                fromEmail, toEmail, subject, plaintext, senderKeys, recipientFpHex, recipientXPubB64
        );

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromEmail));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false));
        msg.setSubject(subject, "UTF-8");
        msg.setSentDate(new Date());
        msg.setText("Encrypted message (zkemails).", "UTF-8");
//        msg.setText(p.toString(),"UTF-8");

        msg.setHeader("X-ZKEmails-Type", "msg");
        msg.setHeader("X-ZKEmails-Enc", "x25519+hkdf+aesgcm;sig=ed25519");

        msg.setHeader("X-ZKEmails-Sender-Fp", senderKeys.fingerprintHex());
        msg.setHeader("X-ZKEmails-Recipient-Fp", recipientFpHex);

        msg.setHeader("X-ZKEmails-Ephem-X25519", p.ephemX25519PubB64());
        msg.setHeader("X-ZKEmails-WrappedKey", p.wrappedKeyB64());
        msg.setHeader("X-ZKEmails-WrappedKey-Nonce", p.wrappedKeyNonceB64());
        msg.setHeader("X-ZKEmails-Nonce", p.msgNonceB64());
        msg.setHeader("X-ZKEmails-Ciphertext", p.ciphertextB64());
        msg.setHeader("X-ZKEmails-Sig", p.sigB64());

        Transport.send(msg);
    }

    public void sendPlain(String fromEmail, String toEmail, String subject, String body,
                          Map<String, String> headers) throws MessagingException {
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromEmail));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false));
        msg.setSubject(subject, "UTF-8");
        msg.setSentDate(new Date());
        msg.setText(body, "UTF-8");

        if (headers != null) for (var e : headers.entrySet()) msg.setHeader(e.getKey(), e.getValue());
        Transport.send(msg);
    }
}
