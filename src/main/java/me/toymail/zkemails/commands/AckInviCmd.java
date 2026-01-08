package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.StoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Map;

@Command(name = "invi", description = "Accept an invitation to start encrypted communication",
        footer = {
            "",
            "Examples:",
            "  zke ack invi --invite-id abc123-def456",
            "",
            "What this command does:",
            "  1. Finds the invite email in your inbox by ID",
            "  2. Extracts the sender's public keys from the invite",
            "  3. Stores their keys locally (TOFU - Trust On First Use)",
            "  4. Sends an ACCEPT message with your public keys",
            "",
            "After acknowledging, you can exchange encrypted messages:",
            "  zke sem                    Send encrypted message",
            "  zke rem                    Read encrypted messages",
            "",
            "To find invite IDs, use:",
            "  zke lsi                    List pending invites"
        })
public final class AckInviCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(AckInviCmd.class);
    private final StoreContext context;

    public AckInviCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--invite-id", required = true, paramLabel = "<id>",
            description = "The invite ID from the invitation email")
    String inviteId;

    @Option(names="--password", paramLabel = "<password>",
            description = "App password (optional if saved to keychain)")
    String password;

    @Override
    public void run() {
        try {
            if (!context.hasActiveProfile()) {
                log.error("No active profile set or profile directory missing. Use 'prof' to set a profile.");
                return;
            }
            Config cfg = context.zkStore().readJson("config.json", Config.class);
            if (cfg == null) {
                log.error("Not initialized. Run: zke init --email <your-email>");
                return;
            }

            IdentityKeys.KeyBundle myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);
            if (myKeys == null) {
                log.error("Missing keys.json. Re-run init.");
                return;
            }

            String resolvedPassword = context.passwordResolver().resolve(password, cfg.email, System.console());

            String inviterEmail;
            String subject;
            String inviterFp;
            String inviterEdPub;
            String inviterXPub;

            try (ImapClient imap = ImapClient.connect(new ImapClient.ImapConfig(
                    cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, resolvedPassword
            ))) {
                // Search by both type=invite AND invite-id in one query
                List<ImapClient.MailSummary> matches = imap.searchByInviteId(inviteId, 1);
                if (matches.isEmpty()) {
                    log.error("No invite found with invite-id={}", inviteId);
                    return;
                }
                log.info("Found {} invites with invite-id={}", matches.size(), inviteId);
                ImapClient.MailSummary invite = matches.get(0);
                Map<String, List<String>> headers = imap.fetchAllHeadersByUid(invite.uid());

                inviterEmail = extractEmail(invite.from());
                subject = invite.subject();
                inviterFp = first(headers, "X-ZKEmails-Fingerprint");
                inviterEdPub = first(headers, "X-ZKEmails-PubKey-Ed25519");
                inviterXPub = first(headers, "X-ZKEmails-PubKey-X25519");

                if (inviterEmail == null) {
                    log.error("Could not parse inviter email from: {}", invite.from());
                    return;
                }
                if (inviterFp == null || inviterEdPub == null || inviterXPub == null) {
                    log.error("Invite missing key headers (Fingerprint/Ed25519/X25519).");
                    return;
                }

                log.info("Found invite from {} subject={}", inviterEmail, subject);
            }

            context.contacts().upsertKeys(inviterEmail, "ready", inviterFp, inviterEdPub, inviterXPub);
            log.info("Stored inviter keys in contacts.json (TOFU pin).");
            try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, resolvedPassword))) {
                smtp.sendAcceptWithKeys(cfg.email, inviterEmail, inviteId,
                        myKeys.fingerprintHex(), myKeys.ed25519PublicB64(), myKeys.x25519PublicB64());
            }
            log.info("Sent ACCEPT to {} (your keys gossiped).", inviterEmail);
            context.invites().ensureIncoming(inviteId, inviterEmail, cfg.email, subject);
            context.invites().markIncomingAcked(inviteId);
            log.info("Marked invite as acked locally (invites.json)");

        } catch (Exception e) {
            log.error("Ack invi failed: {} - {} - {}", e.getClass().getSimpleName(), e.getMessage(), e.getStackTrace());
        }
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
