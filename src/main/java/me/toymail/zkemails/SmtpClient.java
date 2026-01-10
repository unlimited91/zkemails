package me.toymail.zkemails;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import me.toymail.zkemails.crypto.CryptoBox;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.InviteStore;

public final class SmtpClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SmtpClient.class);

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
        String subject = "🔒 Welcome to Private chat through zke (Zero Knowledge Emails)";
        String body = String.format("""
                Hey! I'd like to chat with you privately using zke.

                Invitation ID: %s

                What is zke?
                ------------
                zke (Zero Knowledge Emails) is an end-to-end encrypted email client that works on top of
                your regular email. Read more: https://musings.sayanr.com/2025/12/26/zkmails.html

                It is an open source CLI tool designed for privacy-first users who live in the terminal.
                It does NOT talk to any remote server - all encryption happens locally.

                Note: The password for zke is NOT your Gmail password. You need to create an "App Password".

                Creating an App Password (Gmail):
                ----------------------------------
                1. Visit https://myaccount.google.com/apppasswords
                   (If blocked, enable 2FA first at https://myaccount.google.com/ -> Security)
                2. Create an app password for zke
                3. The password looks like "xxxx yyyy zzzz" - remove spaces to get "xxxxyyyyzzzz"

                Getting Started:
                ----------------
                1. Install zke:
                   curl -fsSL https://raw.githubusercontent.com/unlimited91/zkemails/1.0.0.beta1/install.sh | bash
                
                For GUI users, just run the command "zke gui". For terminal loves follow the below steps

                2. Initialize with your email:
                   zke init --email %s

                3. Accept this invitation:
                   zke ack invi --invite-id %s

                Using zke:
                ----------
                Send an encrypted message:
                   zke sem                              (Opens editor)
                   zke sem --to %s      (Pre-fills recipient)

                Read encrypted messages:
                   zke rem                   (List messages)
                   zke rem --message 42      (Read message)
                   zke rem --thread 42       (View conversation)
                   zke rem --reply 42        (Reply to message)

                Manage profiles:
                   zke lsp                   (List profiles)
                   zke pset <email>          (Switch profile)

                That's it! Once you accept, we can exchange end-to-end encrypted messages.

                For help: zke --help
                """, inviteId, toEmail, inviteId, fromEmail);

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
            log.warn("Sent invite but failed to persist invites.json: {}", e.getMessage());
        }
        return inviteId;
    }

    public void sendAcceptWithKeys(String fromEmail, String toEmail, String inviteId,
                                   String fpHex, String edPubB64, String xPubB64) throws MessagingException {

        String subject = "Re: 🔒 Private chat? (zke)";
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

    /**
     * Send an encrypted message with optional threading headers.
     *
     * @param inReplyTo  Message-ID of the message being replied to (null for new messages)
     * @param references Thread reference chain (null for new messages)
     * @param threadId   Custom thread ID for correlation (survives Gmail header stripping)
     * @return the Message-ID of the sent message for local storage
     */
    public String sendEncryptedMessage(String fromEmail, String toEmail, String subject, String plaintext,
                                     IdentityKeys.KeyBundle senderKeys,
                                     String recipientFpHex, String recipientXPubB64,
                                     String inReplyTo, String references, String threadId) throws Exception {

        CryptoBox.EncryptedPayload p = CryptoBox.encryptToRecipient(
                fromEmail, toEmail, subject, plaintext, senderKeys, recipientFpHex, recipientXPubB64
        );

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromEmail));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false));
        msg.setSubject(subject, "UTF-8");
        msg.setSentDate(new Date());
        msg.setText("Encrypted message (zke).", "UTF-8");

        // Threading headers for replies
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            msg.setHeader("In-Reply-To", inReplyTo);
        }
        if (references != null && !references.isBlank()) {
            msg.setHeader("References", references);
        }

        // Custom thread ID header (survives Gmail header stripping)
        if (threadId != null && !threadId.isBlank()) {
            msg.setHeader("X-ZKEmails-Thread-Id", threadId);
        }

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

        // Return the Message-ID for local storage
        return msg.getMessageID();
    }

    /**
     * Send an encrypted message (convenience method for new messages without threading).
     * @return the Message-ID of the sent message for local storage
     */
    public String sendEncryptedMessage(String fromEmail, String toEmail, String subject, String plaintext,
                                     IdentityKeys.KeyBundle senderKeys,
                                     String recipientFpHex, String recipientXPubB64) throws Exception {
        return sendEncryptedMessage(fromEmail, toEmail, subject, plaintext, senderKeys, recipientFpHex, recipientXPubB64, null, null, null);
    }

    /**
     * Send an encrypted message with attachments.
     * Uses MIME multipart format to include encrypted attachments.
     *
     * @param attachments List of files to attach (will be encrypted)
     * @return the Message-ID of the sent message
     */
    public String sendEncryptedMessageWithAttachments(
            String fromEmail, String toEmail, String subject, String plaintext,
            List<CryptoBox.AttachmentInput> attachments,
            IdentityKeys.KeyBundle senderKeys,
            String recipientFpHex, String recipientXPubB64,
            String inReplyTo, String references, String threadId) throws Exception {

        // Encrypt message and attachments together
        CryptoBox.EncryptedMessageWithAttachments encrypted = CryptoBox.encryptWithAttachments(
                fromEmail, toEmail, subject, plaintext, attachments,
                senderKeys, recipientFpHex, recipientXPubB64
        );

        CryptoBox.EncryptedPayload p = encrypted.textPayload();

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromEmail));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false));
        msg.setSubject(subject, "UTF-8");
        msg.setSentDate(new Date());

        // Create multipart content
        MimeMultipart multipart = new MimeMultipart("mixed");

        // Part 1: Text body
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("Encrypted message with attachments (zke).", "UTF-8");
        multipart.addBodyPart(textPart);

        // Part 2: Encrypted attachments as JSON
        if (encrypted.attachments() != null && !encrypted.attachments().isEmpty()) {
            MimeBodyPart attachmentPart = new MimeBodyPart();
            ObjectMapper mapper = new ObjectMapper();

            // Create attachment container with version for future compatibility
            var container = new AttachmentContainer(1, encrypted.attachments());
            String json = mapper.writeValueAsString(container);

            // Use DataHandler for proper MIME encoding
            attachmentPart.setDataHandler(new jakarta.activation.DataHandler(
                    json, "application/json; charset=UTF-8"));
            attachmentPart.setFileName("attachments.zke");
            attachmentPart.setDisposition(MimeBodyPart.ATTACHMENT);
            multipart.addBodyPart(attachmentPart);
        }

        msg.setContent(multipart);

        // Threading headers
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            msg.setHeader("In-Reply-To", inReplyTo);
        }
        if (references != null && !references.isBlank()) {
            msg.setHeader("References", references);
        }
        if (threadId != null && !threadId.isBlank()) {
            msg.setHeader("X-ZKEmails-Thread-Id", threadId);
        }

        // ZKE encryption headers
        msg.setHeader("X-ZKEmails-Type", "msg");
        msg.setHeader("X-ZKEmails-Enc", "x25519+hkdf+aesgcm;sig=ed25519");
        msg.setHeader("X-ZKEmails-Has-Attachments", String.valueOf(encrypted.attachments().size()));

        msg.setHeader("X-ZKEmails-Sender-Fp", senderKeys.fingerprintHex());
        msg.setHeader("X-ZKEmails-Recipient-Fp", recipientFpHex);

        msg.setHeader("X-ZKEmails-Ephem-X25519", p.ephemX25519PubB64());
        msg.setHeader("X-ZKEmails-WrappedKey", p.wrappedKeyB64());
        msg.setHeader("X-ZKEmails-WrappedKey-Nonce", p.wrappedKeyNonceB64());
        msg.setHeader("X-ZKEmails-Nonce", p.msgNonceB64());
        msg.setHeader("X-ZKEmails-Ciphertext", p.ciphertextB64());
        msg.setHeader("X-ZKEmails-Sig", p.sigB64());

        // Save changes to ensure multipart boundaries are set in headers
        msg.saveChanges();
        Transport.send(msg);
        return msg.getMessageID();
    }

    /**
     * Container for encrypted attachments in JSON format.
     */
    public record AttachmentContainer(int version, List<CryptoBox.EncryptedAttachment> attachments) {}

    // ==================== V2 Multi-Recipient Sending ====================

    /**
     * Send an encrypted v2 message to multiple To/CC recipients.
     * Uses MIME multipart with JSON payload attachment.
     *
     * @param fromEmail sender email
     * @param toEmails list of To recipients
     * @param ccEmails list of CC recipients (can be null or empty)
     * @param subject email subject
     * @param payload the v2 encrypted payload
     * @param inReplyTo Message-ID of message being replied to (null for new)
     * @param references thread reference chain (null for new)
     * @param threadId custom thread ID for correlation
     * @return the Message-ID of the sent message
     */
    public String sendEncryptedMultiRecipientMessage(
            String fromEmail,
            List<String> toEmails,
            List<String> ccEmails,
            String subject,
            CryptoBox.EncryptedPayloadV2 payload,
            String inReplyTo, String references, String threadId) throws Exception {

        if (toEmails == null || toEmails.isEmpty()) {
            throw new IllegalArgumentException("At least one To recipient required");
        }

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromEmail));

        // Add To recipients
        InternetAddress[] toAddresses = toEmails.stream()
                .map(email -> {
                    try { return new InternetAddress(email); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .toArray(InternetAddress[]::new);
        msg.setRecipients(Message.RecipientType.TO, toAddresses);

        // Add CC recipients
        if (ccEmails != null && !ccEmails.isEmpty()) {
            InternetAddress[] ccAddresses = ccEmails.stream()
                    .map(email -> {
                        try { return new InternetAddress(email); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .toArray(InternetAddress[]::new);
            msg.setRecipients(Message.RecipientType.CC, ccAddresses);
        }

        msg.setSubject(subject, "UTF-8");
        msg.setSentDate(new Date());

        // Create multipart content with JSON payload
        MimeMultipart multipart = new MimeMultipart("mixed");

        // Part 1: Placeholder text body
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("Encrypted message (zke v2).", "UTF-8");
        multipart.addBodyPart(textPart);

        // Part 2: Encrypted payload as JSON attachment
        MimeBodyPart payloadPart = new MimeBodyPart();
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(payload);

        payloadPart.setDataHandler(new jakarta.activation.DataHandler(
                json, "application/json; charset=UTF-8"));
        payloadPart.setFileName("zkemails-payload.json");
        payloadPart.setDisposition(MimeBodyPart.ATTACHMENT);
        multipart.addBodyPart(payloadPart);

        msg.setContent(multipart);

        // Threading headers
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            msg.setHeader("In-Reply-To", inReplyTo);
        }
        if (references != null && !references.isBlank()) {
            msg.setHeader("References", references);
        }
        if (threadId != null && !threadId.isBlank()) {
            msg.setHeader("X-ZKEmails-Thread-Id", threadId);
        }

        // V2 message headers
        msg.setHeader("X-ZKEmails-Type", "msg");
        msg.setHeader("X-ZKEmails-Version", "2");
        msg.setHeader("X-ZKEmails-Enc", payload.enc());
        msg.setHeader("X-ZKEmails-Sender-Fp", payload.senderFpHex());

        msg.saveChanges();
        Transport.send(msg);
        return msg.getMessageID();
    }

    /**
     * Send an encrypted v2 message to a BCC recipient.
     * Creates a separate email that looks like it's only to the visible recipients
     * but actually goes only to the BCC recipient.
     *
     * @param fromEmail sender email
     * @param bccEmail the BCC recipient
     * @param visibleToEmails To addresses to show in headers (for context)
     * @param subject email subject
     * @param payload the v2 encrypted payload (should only contain BCC's wrapped key)
     * @param inReplyTo Message-ID of message being replied to
     * @param references thread reference chain
     * @param threadId custom thread ID
     * @return the Message-ID of the sent message
     */
    public String sendEncryptedBccMessage(
            String fromEmail,
            String bccEmail,
            List<String> visibleToEmails,
            String subject,
            CryptoBox.EncryptedPayloadV2 payload,
            String inReplyTo, String references, String threadId) throws Exception {

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(fromEmail));

        // Show visible To addresses in headers (for context)
        // but actually send to BCC recipient
        if (visibleToEmails != null && !visibleToEmails.isEmpty()) {
            InternetAddress[] toAddresses = visibleToEmails.stream()
                    .map(email -> {
                        try { return new InternetAddress(email); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    })
                    .toArray(InternetAddress[]::new);
            msg.setRecipients(Message.RecipientType.TO, toAddresses);
        }

        // Actual delivery is via BCC
        msg.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bccEmail, false));

        msg.setSubject(subject, "UTF-8");
        msg.setSentDate(new Date());

        // Create multipart content
        MimeMultipart multipart = new MimeMultipart("mixed");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("Encrypted message (zke v2).", "UTF-8");
        multipart.addBodyPart(textPart);

        MimeBodyPart payloadPart = new MimeBodyPart();
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(payload);

        payloadPart.setDataHandler(new jakarta.activation.DataHandler(
                json, "application/json; charset=UTF-8"));
        payloadPart.setFileName("zkemails-payload.json");
        payloadPart.setDisposition(MimeBodyPart.ATTACHMENT);
        multipart.addBodyPart(payloadPart);

        msg.setContent(multipart);

        // Threading headers
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            msg.setHeader("In-Reply-To", inReplyTo);
        }
        if (references != null && !references.isBlank()) {
            msg.setHeader("References", references);
        }
        if (threadId != null && !threadId.isBlank()) {
            msg.setHeader("X-ZKEmails-Thread-Id", threadId);
        }

        // V2 message headers
        msg.setHeader("X-ZKEmails-Type", "msg");
        msg.setHeader("X-ZKEmails-Version", "2");
        msg.setHeader("X-ZKEmails-Enc", payload.enc());
        msg.setHeader("X-ZKEmails-Sender-Fp", payload.senderFpHex());

        msg.saveChanges();
        Transport.send(msg);
        return msg.getMessageID();
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
