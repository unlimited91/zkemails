package me.toymail.zkemails.commands;

import picocli.CommandLine;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@Command(name = "prof", description = "List or switch profiles", subcommands = {ProfileCmd.Ls.class, ProfileCmd.Use.class})
public class ProfileCmd implements Runnable {
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "ls", description = "List profiles")
    public static class Ls implements Runnable {
        @Override
        public void run() {
            try {
                Path profileConfigPath = Paths.get(System.getProperty("user.home"), ".zkemails", "profile.config");
                ObjectMapper mapper = new ObjectMapper();
                if (!Files.exists(profileConfigPath)) {
                    System.out.println("No profiles found.");
                    return;
                }
                Map<String, Object> profileConfig = mapper.readValue(profileConfigPath.toFile(), Map.class);
                List<String> profiles = (List<String>) profileConfig.getOrDefault("profiles", new ArrayList<>());
                String def = (String) profileConfig.getOrDefault("default", "");
                for (String p : profiles) {
                    if (p.equals(def)) {
                        System.out.println("* " + p + " (default)");
                    } else {
                        System.out.println("  " + p);
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to list profiles: " + e.getMessage());
            }
        }
    }

    @Command(name = "set", description = "Set current profile (prof <profile-id>)")
    public static class Use implements Runnable {
        @Parameters(index = "0", description = "Profile id (email)")
        String profileId;

        @Override
        public void run() {
            try {
                Path profileConfigPath = Paths.get(System.getProperty("user.home"), ".zkemails", "profile.config");
                ObjectMapper mapper = new ObjectMapper();
                if (!Files.exists(profileConfigPath)) {
                    System.out.println("No profiles found.");
                    return;
                }
                Map<String, Object> profileConfig = mapper.readValue(profileConfigPath.toFile(), Map.class);
                List<String> profiles = (List<String>) profileConfig.getOrDefault("profiles", new ArrayList<>());
                if (!profiles.contains(profileId)) {
                    System.out.println("Profile not found: " + profileId);
                    return;
                }
                profileConfig.put("default", profileId);
                mapper.writerWithDefaultPrettyPrinter().writeValue(profileConfigPath.toFile(), profileConfig);
                System.out.println("Set default profile to: " + profileId);
            } catch (Exception e) {
                System.err.println("Failed to set profile: " + e.getMessage());
            }
        }
    }
}

