package me.toymail.zkemails;

// ...existing imports...
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.toymail.zkemails.commands.ProfileCmd;
import picocli.CommandLine;

@CommandLine.Command(
    // ...existing code...
    subcommands = {
        // ...existing subcommands...
        ProfileCmd.class
    }
)
public class ZkEmails {
    // ...existing code...

    /**
     * Returns the current profile email from ~/.zkemails/profile.config
     */
    public static String getCurrentProfileEmail() {
        try {
            Path profileConfigPath = Paths.get(System.getProperty("user.home"), ".zkemails", "profile.config");
            if (!Files.exists(profileConfigPath)) return null;
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> profileConfig = mapper.readValue(profileConfigPath.toFile(), Map.class);
            return (String) profileConfig.get("default");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the Path to the current profile's data directory ~/.zkemails/{email}
     */
    public static String getCurrentProfileDir() {
        return getCurrentProfileEmail();
//        String email = getCurrentProfileEmail();
//        if (email == null) return null;
//        return Paths.get(System.getProperty("user.home"), ".zkemails", email);
    }
    // ...existing code...
}
