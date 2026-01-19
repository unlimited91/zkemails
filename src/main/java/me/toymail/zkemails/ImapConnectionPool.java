package me.toymail.zkemails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Connection pool for IMAP connections.
 * Reuses connections to avoid the latency of creating new connections for each operation.
 */
public final class ImapConnectionPool {
    private static final Logger log = LoggerFactory.getLogger(ImapConnectionPool.class);

    // Singleton instance
    private static ImapConnectionPool instance;

    // Pool of connections keyed by "username@host:port:folder"
    private final Map<String, PooledConnection> connections = new ConcurrentHashMap<>();

    // Connection idle timeout (5 minutes)
    private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000;

    // Cleanup scheduler
    private final ScheduledExecutorService cleanupScheduler;

    private ImapConnectionPool() {
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "imap-pool-cleanup");
            t.setDaemon(true);
            return t;
        });

        // Run cleanup every minute
        cleanupScheduler.scheduleAtFixedRate(this::cleanupIdleConnections, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Get the singleton instance.
     */
    public static synchronized ImapConnectionPool getInstance() {
        if (instance == null) {
            instance = new ImapConnectionPool();
        }
        return instance;
    }

    /**
     * Get or create a connection for the given config and folder.
     */
    public ImapClient getConnection(ImapClient.ImapConfig config, String folderName) throws Exception {
        String key = buildKey(config, folderName);

        PooledConnection pooled = connections.get(key);

        if (pooled != null && pooled.isValid()) {
            pooled.markUsed();
            log.debug("Reusing existing IMAP connection for {}", key);
            return pooled.client;
        }

        // Close old connection if exists but invalid
        if (pooled != null) {
            log.debug("Closing invalid connection for {}", key);
            pooled.close();
            connections.remove(key);
        }

        // Create new connection
        log.info("Creating new IMAP connection for {}", key);
        ImapClient client = ImapClient.connect(config, folderName);
        pooled = new PooledConnection(client, key);
        connections.put(key, pooled);

        return client;
    }

    /**
     * Get or create a connection for INBOX.
     */
    public ImapClient getConnection(ImapClient.ImapConfig config) throws Exception {
        return getConnection(config, "INBOX");
    }

    /**
     * Get or create a connection for the sent folder.
     */
    public ImapClient getSentConnection(ImapClient.ImapConfig config) throws Exception {
        String key = buildKey(config, "SENT");

        PooledConnection pooled = connections.get(key);

        if (pooled != null && pooled.isValid()) {
            pooled.markUsed();
            log.debug("Reusing existing IMAP sent connection for {}", key);
            return pooled.client;
        }

        // Close old connection if exists but invalid
        if (pooled != null) {
            log.debug("Closing invalid sent connection for {}", key);
            pooled.close();
            connections.remove(key);
        }

        // Create new connection to sent folder
        log.info("Creating new IMAP sent connection for {}", key);
        ImapClient client = ImapClient.connectToSent(config);
        pooled = new PooledConnection(client, key);
        connections.put(key, pooled);

        return client;
    }

    /**
     * Release a connection back to the pool (mark as available).
     * Note: We don't actually close the connection, just mark it as available for reuse.
     */
    public void releaseConnection(ImapClient client) {
        // Connection stays in pool, just marked as available
        // The cleanup thread will close idle connections
    }

    /**
     * Invalidate and close a specific connection (e.g., after an error).
     */
    public void invalidateConnection(ImapClient client) {
        connections.entrySet().removeIf(entry -> {
            if (entry.getValue().client == client) {
                log.debug("Invalidating connection: {}", entry.getKey());
                entry.getValue().close();
                return true;
            }
            return false;
        });
    }

    /**
     * Close all connections for a specific user (e.g., on profile switch).
     */
    public void closeConnectionsForUser(String username) {
        connections.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(username + "@")) {
                log.debug("Closing connection on profile switch: {}", entry.getKey());
                entry.getValue().close();
                return true;
            }
            return false;
        });
    }

    /**
     * Close all connections and shutdown the pool.
     */
    public void shutdown() {
        log.info("Shutting down IMAP connection pool");
        cleanupScheduler.shutdownNow();
        connections.values().forEach(PooledConnection::close);
        connections.clear();
    }

    /**
     * Cleanup idle connections.
     */
    private void cleanupIdleConnections() {
        long now = System.currentTimeMillis();
        connections.entrySet().removeIf(entry -> {
            PooledConnection pooled = entry.getValue();
            if (now - pooled.lastUsed > IDLE_TIMEOUT_MS) {
                log.debug("Closing idle connection: {}", entry.getKey());
                pooled.close();
                return true;
            }
            if (!pooled.isValid()) {
                log.debug("Removing invalid connection: {}", entry.getKey());
                pooled.close();
                return true;
            }
            return false;
        });
    }

    private String buildKey(ImapClient.ImapConfig config, String folderName) {
        return config.username() + "@" + config.host() + ":" + config.port() + "/" + folderName;
    }

    /**
     * Wrapper for pooled connections with metadata.
     */
    private static class PooledConnection {
        final ImapClient client;
        final String key;
        volatile long lastUsed;

        PooledConnection(ImapClient client, String key) {
            this.client = client;
            this.key = key;
            this.lastUsed = System.currentTimeMillis();
        }

        void markUsed() {
            lastUsed = System.currentTimeMillis();
        }

        boolean isValid() {
            try {
                return client.isConnected();
            } catch (Exception e) {
                return false;
            }
        }

        void close() {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Error closing pooled connection: {}", e.getMessage());
            }
        }
    }
}
