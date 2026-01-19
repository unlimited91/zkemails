package me.toymail.zkemails.gui.util;

import javafx.application.Platform;
import javafx.concurrent.Task;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility for running background tasks with JavaFX UI thread updates.
 */
public final class TaskRunner {
    private static final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("zke-bg-" + t.getId());
        return t;
    });

    /**
     * Callback interface for task results.
     */
    public interface TaskCallback<T> {
        void onSuccess(T result);
        default void onError(Throwable error) {
            error.printStackTrace();
        }
        default void onProgress(String message) {}
    }

    /**
     * Run a task in the background.
     * @param description short description for logging
     * @param task the callable task
     * @param callback callback for results (called on JavaFX thread)
     */
    public static <T> void run(String description, Callable<T> task, TaskCallback<T> callback) {
        Task<T> fxTask = new Task<>() {
            @Override
            protected T call() throws Exception {
                updateMessage("Running: " + description);
                return task.call();
            }
        };

        fxTask.setOnSucceeded(e ->
            Platform.runLater(() -> callback.onSuccess(fxTask.getValue()))
        );

        fxTask.setOnFailed(e ->
            Platform.runLater(() -> callback.onError(fxTask.getException()))
        );

        fxTask.messageProperty().addListener((obs, old, msg) ->
            Platform.runLater(() -> callback.onProgress(msg))
        );

        executor.submit(fxTask);
    }

    /**
     * Run a task in the background with just success/error callbacks.
     */
    public static <T> void run(Callable<T> task, TaskCallback<T> callback) {
        run("background task", task, callback);
    }

    /**
     * Shutdown the executor. Call this when the application exits.
     */
    public static void shutdown() {
        executor.shutdownNow();
    }
}
