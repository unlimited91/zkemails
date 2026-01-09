package me.toymail.zkemails.service;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.InviteStore;
import me.toymail.zkemails.store.InviteStore.Invite;
import me.toymail.zkemails.store.StoreContext;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Service for managing invitations.
 */
public final class InviteService {
    private final StoreContext context;

    public InviteService(StoreContext context) {
        this.context = context;
    }

    /**
     * Invite summary record for GUI display.
     */
    public record InviteSummary(
        String inviteId,
        String email,
        String subject,
        long createdEpochSec,
        String status,
        String direction
    ) {
        public static InviteSummary from(Invite i) {
            String email = "out".equals(i.direction) ? i.toEmail : i.fromEmail;
            return new InviteSummary(
                i.inviteId,
                email,
                i.subject,
                i.createdEpochSec,
                i.status,
                i.direction
            );
        }
    }

    /**
     * Result of sending an invite.
     */
    public record SendInviteResult(boolean success, String inviteId, String message) {}

    /**
     * Result of acknowledging an invite.
     */
    public record AckInviteResult(boolean success, String message, String inviterEmail) {}

    /**
     * Send an invite to a new contact.
     * @param password the app password
     * @param toEmail the recipient email
     * @return result with invite ID
     */
    public SendInviteResult sendInvite(String password, String toEmail) throws Exception {
        if (!context.hasActiveProfile()) {
            return new SendInviteResult(false, null, "No active profile set");
        }

        Config cfg = context.zkStore().readJson("config.json", Config.class);
        if (cfg == null) {
            return new SendInviteResult(false, null, "Not initialized. Run: zke init --email <your-email>");
        }

        IdentityKeys.KeyBundle keys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);
        if (keys == null) {
            return new SendInviteResult(false, null, "Missing keys.json. Re-run init.");
        }

        context.contacts().upsertBasic(toEmail, "invited-out");

        try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(
                cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, password))) {
            String inviteId = smtp.sendInvite(cfg.email, toEmail, keys, context.invites());
            return new SendInviteResult(true, inviteId, "Invite sent to " + toEmail);
        }
    }

    /**
     * Acknowledge an incoming invite.
     * @param password the app password
     * @param inviteId the invite ID from the email
     * @return result with inviter email if successful
     */
    public AckInviteResult acknowledgeInvite(String password, String inviteId) throws Exception {
        if (!context.hasActiveProfile()) {
            return new AckInviteResult(false, "No active profile set", null);
        }

        Config cfg = context.zkStore().readJson("config.json", Config.class);
        if (cfg == null) {
            return new AckInviteResult(false, "Not initialized. Run: zke init --email <your-email>", null);
        }

        IdentityKeys.KeyBundle myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);
        if (myKeys == null) {
            return new AckInviteResult(false, "Missing keys.json. Re-run init.", null);
        }

        String inviterEmail;
        String subject;
        String inviterFp;
        String inviterEdPub;
        String inviterXPub;

        try (ImapClient imap = ImapClient.connect(new ImapClient.ImapConfig(
                cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, password
        ))) {
            List<ImapClient.MailSummary> matches = imap.searchByInviteId(inviteId, 1);
            if (matches.isEmpty()) {
                return new AckInviteResult(false, "No invite found with invite-id=" + inviteId, null);
            }

            ImapClient.MailSummary invite = matches.get(0);
            Map<String, List<String>> headers = imap.fetchAllHeadersByUid(invite.uid());

            inviterEmail = extractEmail(invite.from());
            subject = invite.subject();
            inviterFp = first(headers, "X-ZKEmails-Fingerprint");
            inviterEdPub = first(headers, "X-ZKEmails-PubKey-Ed25519");
            inviterXPub = first(headers, "X-ZKEmails-PubKey-X25519");

            if (inviterEmail == null) {
                return new AckInviteResult(false, "Could not parse inviter email", null);
            }
            if (inviterFp == null || inviterEdPub == null || inviterXPub == null) {
                return new AckInviteResult(false, "Invite missing key headers", null);
            }
        }

        context.contacts().upsertKeys(inviterEmail, "ready", inviterFp, inviterEdPub, inviterXPub);

        try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(
                cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, password))) {
            smtp.sendAcceptWithKeys(cfg.email, inviterEmail, inviteId,
                    myKeys.fingerprintHex(), myKeys.ed25519PublicB64(), myKeys.x25519PublicB64());
        }

        context.invites().ensureIncoming(inviteId, inviterEmail, cfg.email, subject);
        context.invites().markIncomingAcked(inviteId);

        return new AckInviteResult(true, "Acknowledged invite from " + inviterEmail, inviterEmail);
    }

    /**
     * Sync accept messages and import contact keys.
     * @param password the app password
     * @param limit maximum messages to scan
     * @return number of contacts updated
     */
    public int syncAcceptMessages(String password, int limit) throws Exception {
        if (!context.hasActiveProfile()) {
            throw new IllegalStateException("No active profile set");
        }

        Config cfg = context.zkStore().readJson("config.json", Config.class);
        if (cfg == null) {
            throw new IllegalStateException("Not initialized");
        }

        int updated = 0;
        try (ImapClient imap = ImapClient.connect(new ImapClient.ImapConfig(
                cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, password
        ))) {
            List<ImapClient.MailSummary> accepts = imap.searchHeaderEquals("X-ZKEmails-Type", "accept", limit);
            for (var m : accepts) {
                Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(m.uid());
                String fp = first(hdrs, "X-ZKEmails-Fingerprint");
                String ed = first(hdrs, "X-ZKEmails-PubKey-Ed25519");
                String x = first(hdrs, "X-ZKEmails-PubKey-X25519");

                String sender = extractEmail(m.from());
                if (sender == null) continue;
                if (fp == null || ed == null || x == null) continue;

                context.contacts().upsertKeys(sender, "ready", fp, ed, x);
                updated++;
            }
        }

        return updated;
    }

    /**
     * List pending (unacknowledged) invites.
     * @return list of pending invite summaries
     */
    public List<InviteSummary> listPendingInvites() throws IOException {
        if (!context.hasActiveProfile()) {
            return List.of();
        }
        List<Invite> invites = context.invites().listIncoming(true);
        return invites.stream()
            .filter(i -> "in".equals(i.direction) && !"acked".equals(i.status))
            .map(InviteSummary::from)
            .toList();
    }

    /**
     * List acknowledged invites.
     * @return list of acknowledged invite summaries
     */
    public List<InviteSummary> listAcknowledgedInvites() throws IOException {
        if (!context.hasActiveProfile()) {
            return List.of();
        }
        List<Invite> invites = context.invites().listIncoming(false);
        return invites.stream()
            .filter(i -> "acked".equals(i.status))
            .map(InviteSummary::from)
            .toList();
    }

    /**
     * List outgoing invites (invites you sent).
     * @return list of outgoing invite summaries
     */
    public List<InviteSummary> listOutgoingInvites() throws IOException {
        if (!context.hasActiveProfile()) {
            return List.of();
        }
        return context.invites().listOutgoingNewestFirst().stream()
            .map(InviteSummary::from)
            .toList();
    }

    private static String first(Map<String, List<String>> hdrs, String key) {
        List<String> v = hdrs.get(key);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private static String extractEmail(String fromHeader) {
        if (fromHeader == null) return null;
        int lt = fromHeader.indexOf('<');
        int gt = fromHeader.indexOf('>');
        if (lt >= 0 && gt > lt) return fromHeader.substring(lt + 1, gt).trim();
        String s = fromHeader.trim();
        if (s.contains("@") && !s.contains(" ")) return s;
        return null;
    }
}
