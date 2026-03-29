package me.toymail.zkemails.service;

import me.toymail.zkemails.store.InboxStore;
import me.toymail.zkemails.store.StoreContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for InboxSyncService checkpoint and parallel sync functionality.
 */
class InboxSyncServiceTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;
    private StoreContext context;
    private InboxSyncService syncService;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        context = StoreContext.initialize();
    }

    @AfterEach
    void tearDown() {
        if (syncService != null) {
            syncService.shutdown();
        }
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void testSyncResultRecord() {
        InboxSyncService.SyncResult result = new InboxSyncService.SyncResult(5, 2, List.of(1L, 2L, 3L, 4L, 5L));

        assertEquals(5, result.newMessages());
        assertEquals(2, result.failedMessages());
        assertEquals(5, result.newUids().size());
    }

    @Test
    void testSyncListenerInterface() throws InterruptedException {
        CountDownLatch completeLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<InboxSyncService.SyncResult> resultRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();

        InboxSyncService.SyncListener listener = new InboxSyncService.SyncListener() {
            @Override
            public void onSyncComplete(InboxSyncService.SyncResult result) {
                resultRef.set(result);
                completeLatch.countDown();
            }

            @Override
            public void onSyncError(Exception error) {
                errorRef.set(error);
                errorLatch.countDown();
            }
        };

        // Simulate calling onSyncComplete
        InboxSyncService.SyncResult testResult = new InboxSyncService.SyncResult(3, 0, List.of(10L, 20L, 30L));
        listener.onSyncComplete(testResult);

        assertTrue(completeLatch.await(1, TimeUnit.SECONDS));
        assertEquals(testResult, resultRef.get());

        // Simulate calling onSyncError
        Exception testError = new RuntimeException("Test error");
        listener.onSyncError(testError);

        assertTrue(errorLatch.await(1, TimeUnit.SECONDS));
        assertEquals(testError, errorRef.get());
    }

    @Test
    void testCheckpointFromInboxIndex() throws IOException {
        // Create profile directory structure
        Path profileDir = tempDir.resolve(".zkemails").resolve("test@example.com");
        java.nio.file.Files.createDirectories(profileDir);

        Path inboxDir = profileDir.resolve("inbox");
        java.nio.file.Files.createDirectories(inboxDir);

        // Create an InboxStore and set a checkpoint
        InboxStore inboxStore = new InboxStore(inboxDir);
        InboxStore.InboxIndex index = new InboxStore.InboxIndex();
        long checkpointTime = Instant.now().getEpochSecond() - 3600; // 1 hour ago
        index.lastSyncEpochSec = checkpointTime;
        inboxStore.saveIndex(index);

        // Verify checkpoint is persisted
        InboxStore.InboxIndex loadedIndex = inboxStore.loadIndex();
        assertEquals(checkpointTime, loadedIndex.lastSyncEpochSec);
    }

    @Test
    void testInboxIndexDefaults() {
        InboxStore.InboxIndex index = new InboxStore.InboxIndex();

        assertEquals(0, index.lastSyncEpochSec, "Default checkpoint should be 0");
        assertNotNull(index.threads, "Threads list should be initialized");
        assertTrue(index.threads.isEmpty(), "Threads list should be empty by default");
    }

    @Test
    void testSyncServiceCreation() {
        // Verify sync service can be created
        syncService = new InboxSyncService(context);
        assertNotNull(syncService);
    }

    @Test
    void testSyncServiceShutdown() {
        syncService = new InboxSyncService(context);

        // Should not throw
        assertDoesNotThrow(() -> syncService.shutdown());
    }

    @Test
    void testSyncWithoutActiveProfile() {
        syncService = new InboxSyncService(context);

        // Without an active profile, sync should throw
        assertThrows(Exception.class, () -> syncService.sync("password", 100));
    }

    @Test
    void testParallelTaskResultAggregation() {
        // Simulate collecting results from parallel tasks
        List<CompletableFuture<Boolean>> futures = List.of(
                CompletableFuture.completedFuture(true),
                CompletableFuture.completedFuture(true),
                CompletableFuture.completedFuture(false),
                CompletableFuture.completedFuture(true)
        );

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long successCount = futures.stream()
                .map(CompletableFuture::join)
                .filter(success -> success)
                .count();

        long failCount = futures.size() - successCount;

        assertEquals(3, successCount);
        assertEquals(1, failCount);
    }
}
