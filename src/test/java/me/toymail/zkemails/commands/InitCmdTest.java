package me.toymail.zkemails.commands;

import me.toymail.zkemails.ImapClient;
import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ZkStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class InitCmdTest extends CommandTestBase {

    @Test
    public void testInit_Success() throws Exception {
        try (MockedStatic<ImapClient> mockedImap = mockStatic(ImapClient.class);
             MockedStatic<SmtpClient> mockedSmtp = mockStatic(SmtpClient.class)) {

            ImapClient imap = mock(ImapClient.class);
            mockedImap.when(() -> ImapClient.connect(any())).thenReturn(imap);

            SmtpClient smtp = mock(SmtpClient.class);
            mockedSmtp.when(() -> SmtpClient.connect(any())).thenReturn(smtp);

            InitCmd cmd = new InitCmd();
            cmd.email = "test@example.com";
            cmd.password = "pass";
            cmd.imapHost = "imap.test";
            cmd.imapPort = 993;
            cmd.smtpHost = "smtp.test";
            cmd.smtpPort = 587;

            cmd.run();

            // Verify config and keys created
            ZkStore store = new ZkStore("test@example.com");
            Config cfg = store.readJson("config.json", Config.class);
            assertNotNull(cfg);
            assertEquals("test@example.com", cfg.email);
            assertTrue(store.exists("keys.json"));
        }
    }

    @Test
    public void testInit_ImapFail() throws Exception {
        try (MockedStatic<ImapClient> mockedImap = mockStatic(ImapClient.class)) {
            mockedImap.when(() -> ImapClient.connect(any())).thenThrow(new RuntimeException("IMAP Fail"));

            InitCmd cmd = new InitCmd();
            cmd.email = "test@example.com";
            cmd.password = "pass";

            cmd.run();

            // Verify config NOT created
            ZkStore store = new ZkStore("test@example.com");
            assertNull(store.readJson("config.json", Config.class));
        }
    }
}

