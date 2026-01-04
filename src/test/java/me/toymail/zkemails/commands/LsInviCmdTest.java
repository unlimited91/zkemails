package me.toymail.zkemails.commands;

import me.toymail.zkemails.store.InviteStore;
import me.toymail.zkemails.store.ZkStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LsInviCmdTest extends CommandTestBase {

    @Test
    public void testLsInvi_Success() throws Exception {
        setupInitializedProfile("test@example.com");

        // Add invite
        ZkStore store = new ZkStore("test@example.com");
        InviteStore invites = new InviteStore(store);
        invites.ensureIncoming("inv123", "sender@example.com", "test@example.com", "Subject");

        System.err.println("Invites file exists: " + java.nio.file.Files.exists(store.path("invites.json")));
        System.err.println("Invites content: " + java.nio.file.Files.readString(store.path("invites.json")));

        LsInviCmd cmd = new LsInviCmd();
        cmd.limit = 50;

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        cmd.run();

        System.err.println("STDOUT (LsInvi): " + outContent.toString());
        assertTrue(outContent.toString().contains("inv123"));
        assertTrue(outContent.toString().contains("sender@example.com"));
    }

    @Test
    public void testLsaInvi_Success() throws Exception {
        setupInitializedProfile("test@example.com");

        // Add acked invite
        ZkStore store = new ZkStore("test@example.com");
        InviteStore invites = new InviteStore(store);
        invites.ensureIncoming("inv123", "sender@example.com", "test@example.com", "Subject");
        invites.markIncomingAcked("inv123");

        LsaInviCmd cmd = new LsaInviCmd();
        cmd.limit = 50;

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        cmd.run();

        assertTrue(outContent.toString().contains("inv123"));
        assertTrue(outContent.toString().contains("sender@example.com"));
    }

    private void setupInitializedProfile(String email) throws Exception {
        ZkStore store = new ZkStore(email);
        store.ensure();

        java.nio.file.Files.writeString(
                tempDir.resolve(".zkemails").resolve("profile.config"),
                "{\"profiles\":[\"" + email + "\"],\"default\":\"" + email + "\"}"
        );
    }
}

