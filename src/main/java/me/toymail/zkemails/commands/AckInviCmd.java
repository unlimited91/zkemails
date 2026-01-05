package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.StoreContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.Map;

@Command(name = "invi", description = "Acknowledge an invite by invite-id (send accept + store inviter pubkeys).")
public final class AckInviCmd implements Runnable {
    private final StoreContext context;

    public AckInviCmd(StoreContext context) {
        this.context = context;
    }

    @Option(names="--invite-id", required = true, description = "Invite ID to acknowledge")
    String inviteId;

    @Option(names="--password", required = true, interactive = true,
            description = "App password / password (not saved)")
    String password;

    @Override
    public void run() {
        try {
            if (!context.hasActiveProfile()) {
                System.err.println("No active profile set or profile directory missing. Use 'prof' to set a profile.");
                return;
            }
            Config cfg = context.zkStore().readJson("config.json", Config.class);
            if (cfg == null) {
                System.err.println("Not initialized. Run: zkemails init ...");
                return;
            }

            IdentityKeys.KeyBundle myKeys = context.zkStore().readJson("keys.json", IdentityKeys.KeyBundle.class);
            if (myKeys == null) {
                System.err.println("Missing keys.json. Re-run init.");
                return;
            }

            String inviterEmail;
            String subject;
            String inviterFp;
            String inviterEdPub;
            String inviterXPub;

            try (ImapClient imap = ImapClient.connect(new ImapClient.ImapConfig(
                    cfg.imap.host, cfg.imap.port, cfg.imap.ssl, cfg.imap.username, password
            ))) {
                List<ImapClient.MailSummary> matches = imap.searchHeaderEquals("X-ZKEmails-Invite-Id", inviteId, 50);
                if (matches.isEmpty()) {
                    System.err.println("No email found with invite-id=" + inviteId);
                    return;
                }

                ImapClient.MailSummary chosen = null;
                Map<String, List<String>> chosenHdrs = null;

                for (var m : matches) {
                    Map<String, List<String>> hdrs = imap.fetchAllHeadersByUid(m.uid());
                    String type = first(hdrs, "X-ZKEmails-Type");
                    String id = first(hdrs, "X-ZKEmails-Invite-Id");
                    if ("invite".equalsIgnoreCase(type) && inviteId.equals(id)) {
                        chosen = m;
                        chosenHdrs = hdrs;
                        break;
                    }
                }
                if (chosen == null) {
                    chosen = matches.get(0);
                    chosenHdrs = imap.fetchAllHeadersByUid(chosen.uid());
                }

                inviterEmail = extractEmail(chosen.from());
                subject = chosen.subject();

                inviterFp = first(chosenHdrs, "X-ZKEmails-Fingerprint");
                inviterEdPub = first(chosenHdrs, "X-ZKEmails-PubKey-Ed25519");
                inviterXPub = first(chosenHdrs, "X-ZKEmails-PubKey-X25519");

                if (inviterEmail == null) {
                    System.err.println("Could not parse inviter email from: " + chosen.from());
                    return;
                }
                if (inviterFp == null || inviterEdPub == null || inviterXPub == null) {
                    System.err.println("Invite missing key headers from inviter (Fingerprint/Ed25519/X25519).");
                    return;
                }

                System.out.println("Found invite from " + inviterEmail + " subject=" + subject);
            }

            context.contacts().upsertKeys(inviterEmail, "ready", inviterFp, inviterEdPub, inviterXPub);
            System.out.println("Stored inviter keys in contacts.json (TOFU pin).");

            try (SmtpClient smtp = SmtpClient.connect(new SmtpClient.SmtpConfig(cfg.smtp.host, cfg.smtp.port, cfg.smtp.username, password))) {
                smtp.sendAcceptWithKeys(cfg.email, inviterEmail, inviteId,
                        myKeys.fingerprintHex(), myKeys.ed25519PublicB64(), myKeys.x25519PublicB64());
            }
            System.out.println("Sent ACCEPT to " + inviterEmail + " (your keys gossiped).");

            context.invites().ensureIncoming(inviteId, inviterEmail, cfg.email, subject);
            context.invites().markIncomingAcked(inviteId);
            System.out.println("Marked invite as acked locally (invites.json)");
        } catch (Exception e) {
            System.err.println("Ack invi failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
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
