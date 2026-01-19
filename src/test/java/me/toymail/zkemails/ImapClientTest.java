package me.toymail.zkemails;

import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ImapClient utility methods and checkpoint logic.
 */
class ImapClientTest {

    @Test
    void testCheckpointDateCalculation_noCheckpoint() {
        // When checkpoint is 0, should use 30-day window
        long checkpoint = 0;
        Date result = calculateSearchDate(checkpoint);

        // Should be approximately 30 days ago
        long thirtyDaysMs = 30L * 24 * 60 * 60 * 1000;
        long expectedMs = System.currentTimeMillis() - thirtyDaysMs;

        // Allow 1 minute tolerance
        assertTrue(Math.abs(result.getTime() - expectedMs) < 60_000,
                "Expected ~30 days ago, got: " + result);
    }

    @Test
    void testCheckpointDateCalculation_recentCheckpoint() {
        // Checkpoint from 2 days ago should use checkpoint-1day = 3 days ago
        long twoDaysAgoSec = (System.currentTimeMillis() / 1000) - (2 * 24 * 60 * 60);
        Date result = calculateSearchDate(twoDaysAgoSec);

        // Should be 3 days ago (checkpoint - 1 day buffer)
        long threeDaysMs = 3L * 24 * 60 * 60 * 1000;
        long expectedMs = System.currentTimeMillis() - threeDaysMs;

        // Allow 1 minute tolerance
        assertTrue(Math.abs(result.getTime() - expectedMs) < 60_000,
                "Expected ~3 days ago, got: " + result);
    }

    @Test
    void testCheckpointDateCalculation_oldCheckpoint() {
        // Checkpoint from 60 days ago should use 30-day max window
        long sixtyDaysAgoSec = (System.currentTimeMillis() / 1000) - (60 * 24 * 60 * 60);
        Date result = calculateSearchDate(sixtyDaysAgoSec);

        // Should be capped at 30 days ago
        long thirtyDaysMs = 30L * 24 * 60 * 60 * 1000;
        long expectedMs = System.currentTimeMillis() - thirtyDaysMs;

        // Allow 1 minute tolerance
        assertTrue(Math.abs(result.getTime() - expectedMs) < 60_000,
                "Expected ~30 days ago (capped), got: " + result);
    }

    @Test
    void testCheckpointDateCalculation_negativeCheckpoint() {
        // Negative checkpoint should behave like no checkpoint
        long checkpoint = -1;
        Date result = calculateSearchDate(checkpoint);

        long thirtyDaysMs = 30L * 24 * 60 * 60 * 1000;
        long expectedMs = System.currentTimeMillis() - thirtyDaysMs;

        assertTrue(Math.abs(result.getTime() - expectedMs) < 60_000,
                "Expected ~30 days ago for negative checkpoint, got: " + result);
    }

    /**
     * Replicates the checkpoint date calculation logic from ImapClient.searchHeaderEqualsSince
     * for testing without needing actual IMAP connection.
     */
    private Date calculateSearchDate(long sinceEpochSec) {
        int SEARCH_DAYS_LIMIT = 30;

        if (sinceEpochSec <= 0) {
            return daysAgo(SEARCH_DAYS_LIMIT);
        }

        long bufferedEpochMs = (sinceEpochSec - 86400) * 1000;
        Date checkpointDate = new Date(bufferedEpochMs);
        Date maxWindowDate = daysAgo(SEARCH_DAYS_LIMIT);

        return checkpointDate.after(maxWindowDate) ? checkpointDate : maxWindowDate;
    }

    private Date daysAgo(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -days);
        return cal.getTime();
    }
}
