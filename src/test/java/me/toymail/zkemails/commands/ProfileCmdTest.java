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
        ProfileCmd.Ls cmd = new ProfileCmd.Ls();

        // Capture stdout
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        cmd.run();

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

        ProfileCmd.Ls cmd = new ProfileCmd.Ls();

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        cmd.run();

        assertTrue(outContent.toString().contains("* test@example.com"));
    }

    @Test
    public void testProfileUse_Success() throws Exception {
        // Setup profile config
        ZkStore store = new ZkStore("test@example.com");
        store.ensure();
        java.nio.file.Files.writeString(
                tempDir.resolve(".zkemails").resolve("profile.config"),
                "{\"profiles\":[\"test@example.com\",\"other@example.com\"],\"default\":\"test@example.com\"}"
        );

        ProfileCmd.Use cmd = new ProfileCmd.Use();
        cmd.profileId = "other@example.com";

        cmd.run();

        // Verify current profile changed
        String content = java.nio.file.Files.readString(tempDir.resolve(".zkemails").resolve("profile.config"));
        System.err.println("Profile config content: " + content);

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(content, Map.class);
        assertTrue("other@example.com".equals(map.get("default")));
    }
}

