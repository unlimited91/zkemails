package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ZkStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AckInviCmdTest extends CommandTestBase {

    @Test
    public void testAckInvi_Success() throws Exception {
        setupInitializedProfile("receiver@example.com");

        try (MockedStatic<ImapClient> mockedImap = mockStatic(ImapClient.class);
             MockedStatic<SmtpClient> mockedSmtp = mockStatic(SmtpClient.class)) {

            ImapClient imap = mock(ImapClient.class);
            mockedImap.when(() -> ImapClient.connect(any())).thenReturn(imap);

            SmtpClient smtp = mock(SmtpClient.class);
            mockedSmtp.when(() -> SmtpClient.connect(any())).thenReturn(smtp);

            // Mock finding invite email
            List<ImapClient.MailSummary> msgs = new ArrayList<>();
            msgs.add(new ImapClient.MailSummary(1, 100, false, new Date(), "inviter@example.com", "Invite"));
            when(imap.searchByInviteId(eq("inv123"), anyInt())).thenReturn(msgs);

            Map<String, List<String>> headers = new HashMap<>();
            headers.put("X-ZKEmails-Type", List.of("invite"));
            headers.put("X-ZKEmails-Invite-Id", List.of("inv123"));
            headers.put("X-ZKEmails-Fingerprint", List.of("fp123"));
            headers.put("X-ZKEmails-PubKey-Ed25519", List.of("ed123"));
            headers.put("X-ZKEmails-PubKey-X25519", List.of("x123"));
            when(imap.fetchAllHeadersByUid(100)).thenReturn(headers);

            reinitializeContext();

            AckInviCmd cmd = new AckInviCmd(context);
            cmd.password = "pass";
            cmd.inviteId = "inv123";

            cmd.run();

            // Verify reply sent
            verify(smtp).sendAcceptWithKeys(
                    eq("receiver@example.com"),
                    eq("inviter@example.com"),
                    eq("inv123"),
                    any(),
                    any(),
                    any()
            );
        }
    }

    private void setupInitializedProfile(String email) throws Exception {
        ZkStore store = new ZkStore(email);
        store.ensure();

        java.nio.file.Files.writeString(
                tempDir.resolve(".zkemails").resolve("profile.config"),
                "{\"profiles\":[\"" + email + "\"],\"default\":\"" + email + "\"}"
        );

        Config cfg = new Config();
        cfg.email = email;
        cfg.imap.host = "imap.test";
        cfg.imap.port = 993;
        cfg.imap.username = email;
        cfg.smtp.host = "smtp.test";
        cfg.smtp.port = 587;
        cfg.smtp.username = email;
        store.writeJson("config.json", cfg);

        IdentityKeys.KeyBundle keys = IdentityKeys.generate();
        store.writeJson("keys.json", keys);
    }
}

