package me.toymail.zkemails.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.*;

public final class ZkStore {
    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path baseDir;

    public ZkStore(String email) {
        String home = System.getProperty("user.home");
        this.baseDir = Paths.get(home, ".zkemails"+"/"+email);
    }

    public Path baseDir() { return baseDir; }

    public void ensure() throws IOException {
        Files.createDirectories(baseDir);
        Files.createDirectories(baseDir.resolve("outbox"));
    }

    public <T> void writeJson(String name, T obj) throws IOException {
        ensure();
        Path p = baseDir.resolve(name);
        byte[] bytes = M.writeValueAsBytes(obj);
        Files.write(p, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public <T> T readJson(String name, Class<T> clazz) throws IOException {
        Path p = baseDir.resolve(name);
        if (!Files.exists(p)) return null;
        return M.readValue(Files.readAllBytes(p), clazz);
    }

    public boolean exists(String name) {
        return Files.exists(baseDir.resolve(name));
    }

    public Path path(String name) {
        return baseDir.resolve(name);
    }
}
