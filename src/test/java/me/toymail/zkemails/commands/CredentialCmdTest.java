package me.toymail.zkemails.commands;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;
import me.toymail.zkemails.store.Config;
import me.toymail.zkemails.store.ZkStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CredentialCmdTest extends CommandTestBase {

    @Test
    public void testCredentialStatus_KeychainNotAvailable() {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            mockedKeyring.when(Keyring::create).thenThrow(new BackendNotSupportedException("No backend"));
            reinitializeContext();

            CredentialCmd cmd = new CredentialCmd(context);

            ByteArrayOutputStream outContent = new ByteArrayOutputStream();
            System.setOut(new PrintStream(outContent));

            executeCommand(cmd, "status");

            assertTrue(outContent.toString().contains("not available"));
        }
    }

    @Test
    public void testCredentialStatus_NoActiveProfile() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);
            reinitializeContext();

            CredentialCmd cmd = new CredentialCmd(context);

            ByteArrayOutputStream outContent = new ByteArrayOutputStream();
            System.setOut(new PrintStream(outContent));

            executeCommand(cmd, "status");

            assertTrue(outContent.toString().contains("No active profile"));
        }
    }

    @Test
    public void testCredentialStatus_PasswordStored() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);
            when(keyring.getPassword("zkemails", "test@example.com")).thenReturn("stored-password");

            // Setup profile
            setupProfile("test@example.com");
            reinitializeContext();

            CredentialCmd cmd = new CredentialCmd(context);

            ByteArrayOutputStream outContent = new ByteArrayOutputStream();
            System.setOut(new PrintStream(outContent));

            executeCommand(cmd, "status");

            String output = outContent.toString();
            assertTrue(output.contains("Yes"), "Expected 'Yes' in output: " + output);
        }
    }

    @Test
    public void testCredentialStatus_PasswordNotStored() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);
            when(keyring.getPassword("zkemails", "test@example.com")).thenReturn(null);

            // Setup profile
            setupProfile("test@example.com");
            reinitializeContext();

            CredentialCmd cmd = new CredentialCmd(context);

            ByteArrayOutputStream outContent = new ByteArrayOutputStream();
            System.setOut(new PrintStream(outContent));

            executeCommand(cmd, "status");

            String output = outContent.toString();
            assertTrue(output.contains("No"), "Expected 'No' in output: " + output);
        }
    }

    @Test
    public void testCredentialDelete_Success() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);

            // Setup profile
            setupProfile("test@example.com");
            reinitializeContext();

            CredentialCmd cmd = new CredentialCmd(context);

            ByteArrayOutputStream outContent = new ByteArrayOutputStream();
            System.setOut(new PrintStream(outContent));

            executeCommand(cmd, "delete");

            verify(keyring).deletePassword("zkemails", "test@example.com");
            assertTrue(outContent.toString().contains("removed"));
        }
    }

    @Test
    public void testCredentialDelete_KeychainNotAvailable() {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            mockedKeyring.when(Keyring::create).thenThrow(new BackendNotSupportedException("No backend"));
            reinitializeContext();

            CredentialCmd cmd = new CredentialCmd(context);

            ByteArrayOutputStream outContent = new ByteArrayOutputStream();
            System.setOut(new PrintStream(outContent));

            executeCommand(cmd, "delete");

            assertTrue(outContent.toString().contains("not available"));
        }
    }

    @Test
    public void testCredentialDelete_NoPasswordStored() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);
            doThrow(new PasswordAccessException("Not found"))
                    .when(keyring).deletePassword("zkemails", "test@example.com");

            // Setup profile
            setupProfile("test@example.com");
            reinitializeContext();

            CredentialCmd cmd = new CredentialCmd(context);

            ByteArrayOutputStream outContent = new ByteArrayOutputStream();
            System.setOut(new PrintStream(outContent));

            executeCommand(cmd, "delete");

            assertTrue(outContent.toString().contains("No password was stored"));
        }
    }

    @Test
    public void testCredentialCmd_ShowsUsageWhenNoSubcommand() throws Exception {
        try (MockedStatic<Keyring> mockedKeyring = mockStatic(Keyring.class)) {
            Keyring keyring = mock(Keyring.class);
            mockedKeyring.when(Keyring::create).thenReturn(keyring);
            reinitializeContext();

            CredentialCmd cmd = new CredentialCmd(context);

            ByteArrayOutputStream outContent = new ByteArrayOutputStream();
            System.setOut(new PrintStream(outContent));

            cmd.run();

            // Should show usage/help
            assertTrue(outContent.toString().contains("credential"));
        }
    }

    private void setupProfile(String email) throws Exception {
        // Create profile directory and config
        ZkStore store = new ZkStore(email);
        store.ensure();

        Config cfg = new Config();
        cfg.email = email;
        cfg.imap.host = "imap.test";
        cfg.imap.port = 993;
        cfg.imap.ssl = true;
        cfg.imap.username = email;
        cfg.smtp.host = "smtp.test";
        cfg.smtp.port = 587;
        cfg.smtp.username = email;
        store.writeJson("config.json", cfg);

        // Create profile.config
        java.nio.file.Files.writeString(
                tempDir.resolve(".zkemails").resolve("profile.config"),
                "{\"profiles\":[\"" + email + "\"],\"default\":\"" + email + "\"}"
        );
    }
}
