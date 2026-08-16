package ixdar.platform.concurrent;

/**
 * Fan-out/join over a fixed set of workers, supplied by the platform so that {@code
 * java.util.concurrent} stays out of the browser build, where JavaScript has no threads.
 */
public interface WorkerPool {

    /**
     * Run every task, returning once all have finished. Tasks partition disjoint slices of shared
     * arrays, so they may run in any order and on any thread.
     *
     * @param tasks one task per worker
     * @param stage label naming the waiting stage in failure messages
     * @throws IllegalStateException if a task fails or the wait is interrupted
     */
    void runAll(Runnable[] tasks, String stage);

    /** Release any threads the pool holds. Safe to call once the last {@link #runAll} has returned. */
    void shutdown();
}
