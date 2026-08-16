package ixdar.platform.concurrent;

/**
 * {@link WorkerPool} that runs every task on the calling thread, for backends without threads.
 */
public final class InlineWorkerPool implements WorkerPool {

    /** {@inheritDoc}. */
    @Override
    public void runAll(Runnable[] tasks, String stage) {
        for (Runnable task : tasks) {
            task.run();
        }
    }

    /** {@inheritDoc}. */
    @Override
    public void shutdown() {
    }
}
