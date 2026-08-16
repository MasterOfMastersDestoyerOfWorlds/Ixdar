package ixdar.platform.concurrent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@link WorkerPool} backed by a fixed pool of daemon threads. The only class in the codebase that
 * names {@code java.util.concurrent}'s executors, so the browser build never links them.
 */
public final class ThreadWorkerPool implements WorkerPool {

    /** The executor the tasks run on. */
    public final ExecutorService executor;

    /**
     * Build a pool of {@code workerCount} daemon threads.
     *
     * @param workerCount number of threads, at least one
     * @param threadName name given to each thread, for profiler and stack-trace readability
     */
    public ThreadWorkerPool(int workerCount, String threadName) {
        this.executor = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** {@inheritDoc}. */
    @Override
    public void runAll(Runnable[] tasks, String stage) {
        Future<?>[] pending = new Future<?>[tasks.length];
        for (int index = 0; index < tasks.length; index++) {
            pending[index] = executor.submit(tasks[index]);
        }
        for (Future<?> future : pending) {
            try {
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for the " + stage + " workers",
                        interrupted);
            } catch (ExecutionException failure) {
                throw new IllegalStateException("a " + stage + " worker failed", failure);
            }
        }
    }

    /** {@inheritDoc}. */
    @Override
    public void shutdown() {
        executor.shutdown();
    }
}
