package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ZkStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class SyncAckCmdTest extends CommandTestBase {

    @Test
    public void testSyncAck_Success() throws Exception {
        setupInitializedProfile("inviter@example.com");

        try (MockedStatic<ImapClient> mockedImap = mockStatic(ImapClient.class)) {
            ImapClient imap = mock(ImapClient.class);
            mockedImap.when(() -> ImapClient.connect(any())).thenReturn(imap);

            // Mock finding accept email
            List<ImapClient.MailSummary> msgs = new ArrayList<>();
            msgs.add(new ImapClient.MailSummary(1, 100, false, new Date(), "receiver@example.com", "Re: Invite"));
            when(imap.searchHeaderEquals(eq("X-ZKEmails-Type"), eq("accept"), anyInt())).thenReturn(msgs);

            Map<String, List<String>> headers = new HashMap<>();
            headers.put("X-ZKEmails-Fingerprint", List.of("fp123"));
            headers.put("X-ZKEmails-PubKey-Ed25519", List.of("ed123"));
            headers.put("X-ZKEmails-PubKey-X25519", List.of("x123"));
            when(imap.fetchAllHeadersByUid(100)).thenReturn(headers);

            reinitializeContext();

            SyncAckCmd cmd = new SyncAckCmd(context);
            cmd.password = "pass";

            cmd.run();

            verify(imap).searchHeaderEquals(eq("X-ZKEmails-Type"), eq("accept"), anyInt());
            verify(imap).fetchAllHeadersByUid(100);

            // Verify contact saved (implicitly via ZkStore, but hard to verify without reading file)
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
        store.writeJson("config.json", cfg);
    }
}

