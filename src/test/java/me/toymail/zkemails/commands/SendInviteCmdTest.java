package me.toymail.zkemails.commands;

import me.toymail.zkemails.SmtpClient;
import me.toymail.zkemails.crypto.IdentityKeys;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ZkStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class SendInviteCmdTest extends CommandTestBase {

    @Test
    public void testSendInvite_Success() throws Exception {
        setupInitializedProfile("sender@example.com");
        reinitializeContext();

        try (MockedStatic<SmtpClient> mockedSmtp = mockStatic(SmtpClient.class)) {
            SmtpClient smtp = mock(SmtpClient.class);
            mockedSmtp.when(() -> SmtpClient.connect(any())).thenReturn(smtp);

            SendInviteCmd cmd = new SendInviteCmd(context);
            cmd.password = "pass";
            cmd.to = "recipient@example.com";

            cmd.run();

            verify(smtp).sendInvite(
                    eq("sender@example.com"),
                    eq("recipient@example.com"),
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
        cfg.smtp.host = "smtp.test";
        cfg.smtp.port = 587;
        cfg.smtp.username = email;
        store.writeJson("config.json", cfg);

        IdentityKeys.KeyBundle keys = IdentityKeys.generate();
        store.writeJson("keys.json", keys);
    }
}

