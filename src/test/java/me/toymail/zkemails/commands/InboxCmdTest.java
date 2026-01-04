package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ZkStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

public class InboxCmdTest extends CommandTestBase {

    @Test
    public void testInbox_Success() throws Exception {
        setupInitializedProfile("test@example.com");

        try (MockedStatic<ImapClient> mockedImap = mockStatic(ImapClient.class)) {
            ImapClient imap = mock(ImapClient.class);
            mockedImap.when(() -> ImapClient.connect(any())).thenReturn(imap);

            List<ImapClient.MailSummary> msgs = new ArrayList<>();
            msgs.add(new ImapClient.MailSummary(1, 100, false, new Date(), "sender@example.com", "Subject"));
            when(imap.listInboxLatest(anyInt())).thenReturn(msgs);

            InboxCmd cmd = new InboxCmd();
            cmd.password = "pass";
            cmd.limit = 10;

            cmd.run();

            verify(imap).listInboxLatest(10);
        }
    }

    @Test
    public void testInbox_Filter() throws Exception {
        setupInitializedProfile("test@example.com");

        try (MockedStatic<ImapClient> mockedImap = mockStatic(ImapClient.class)) {
            ImapClient imap = mock(ImapClient.class);
            mockedImap.when(() -> ImapClient.connect(any())).thenReturn(imap);

            List<ImapClient.MailSummary> msgs = new ArrayList<>();
            when(imap.searchHeaderEquals(eq("X-Type"), eq("invite"), anyInt())).thenReturn(msgs);

            InboxCmd cmd = new InboxCmd();
            cmd.password = "pass";
            cmd.headerName = "X-Type";
            cmd.headerValue = "invite";
            cmd.limit = 20;

            cmd.run();

            verify(imap).searchHeaderEquals("X-Type", "invite", 20);
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

