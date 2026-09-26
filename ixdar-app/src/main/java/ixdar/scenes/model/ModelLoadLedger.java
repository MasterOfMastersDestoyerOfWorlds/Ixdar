package ixdar.scenes.model;

/**
 * Numbers each model switch a {@link ModelScene} is asked for and records how the latest finished
 * one ended, so an automation caller can wait for its switch and read the outcome.
 */
public final class ModelLoadLedger {

    private static final double NANOS_PER_SECOND = 1e9;

    private static final long POLL_MILLIS = 5L;

    /** Serial of the latest requested switch; {@code 0} before the first request. */
    public volatile int requestedSerial;

    /** Serial of the latest switch that finished, loaded or failed; {@code 0} before the first. */
    public volatile int finishedSerial;

    /** Failure of the switch numbered {@link #finishedSerial} as the exception's text, or {@code null} when it loaded. */
    public volatile String finishedFailure;

    /** Wall time of the switch numbered {@link #finishedSerial}, in seconds. */
    public volatile double finishedSeconds;

    /**
     * Issue the serial of a new switch request. Render thread only.
     *
     * @return the serial that {@link #finishedSerial} reaches once this switch has finished
     */
    public int request() {
        int serial = requestedSerial + 1;
        requestedSerial = serial;
        return serial;
    }

    /**
     * Record the outcome of the switch numbered {@code serial}, writing the serial last so a waiter
     * that sees it also sees the failure and the time.
     *
     * @param serial serial the switch was requested under
     * @param failure what the load threw, or {@code null} when it loaded
     * @param startedNanos {@link System#nanoTime()} sampled as the load began
     */
    public void finish(int serial, Exception failure, long startedNanos) {
        finishedFailure = failure == null ? null : failure.toString();
        finishedSeconds = (System.nanoTime() - startedNanos) / NANOS_PER_SECOND;
        finishedSerial = serial;
    }

    /**
     * Block until the switch numbered {@code serial}, or a later one that replaced it before it was
     * applied, has finished. Never call on the render thread, which is what finishes switches.
     *
     * @param serial serial returned by {@link #request()} for the awaited switch
     * @param timeoutMillis longest to wait
     * @return {@code true} once it finished, {@code false} on timeout or interrupt
     */
    public boolean awaitFinished(int serial, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (finishedSerial < serial) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
