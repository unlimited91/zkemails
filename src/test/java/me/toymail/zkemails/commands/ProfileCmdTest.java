package me.toymail.zkemails.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.toymail.zkemails.store.ZkStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProfileCmdTest extends CommandTestBase {

    @Test
    public void testProfileLs_NoProfiles() {
        ProfileCmd cmd = new ProfileCmd(context);

        // Capture stdout
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        executeCommand(cmd, "ls");

        assertTrue(outContent.toString().contains("No profiles found"));
    }

    @Test
    public void testProfileLs_WithProfiles() throws Exception {
        // Setup profile config
        ZkStore store = new ZkStore("test@example.com");
        store.ensure();
        java.nio.file.Files.writeString(
                tempDir.resolve(".zkemails").resolve("profile.config"),
                "{\"profiles\":[\"test@example.com\"],\"default\":\"test@example.com\"}"
        );
        reinitializeContext();

        ProfileCmd cmd = new ProfileCmd(context);

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        executeCommand(cmd, "ls");

        assertTrue(outContent.toString().contains("* test@example.com"));
    }

    @Test
    public void testProfileUse_Success() throws Exception {
        // Setup profile config
        ZkStore store = new ZkStore("test@example.com");
        store.ensure();
        ZkStore store2 = new ZkStore("other@example.com");
        store2.ensure();
        java.nio.file.Files.writeString(
                tempDir.resolve(".zkemails").resolve("profile.config"),
                "{\"profiles\":[\"test@example.com\",\"other@example.com\"],\"default\":\"test@example.com\"}"
        );
        reinitializeContext();

        ProfileCmd cmd = new ProfileCmd(context);

        executeCommand(cmd, "set", "other@example.com");

        // Verify current profile changed
        String content = java.nio.file.Files.readString(tempDir.resolve(".zkemails").resolve("profile.config"));

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(content, Map.class);
        assertTrue("other@example.com".equals(map.get("default")));
    }
}

