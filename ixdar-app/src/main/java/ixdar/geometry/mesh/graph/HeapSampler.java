package ixdar.geometry.mesh.graph;

/**
 * Daemon thread polling used heap and keeping the high-water mark since the last reset, which is
 * how a graph run attributes peak memory to the node that was running. The mark is process-wide:
 * it counts what earlier nodes still hold, and a burst freed between two samples can be missed.
 */
public final class HeapSampler {

    /** Milliseconds between heap readings; short enough to catch a node that allocates in bursts. */
    public static final long SAMPLE_INTERVAL_MILLIS = 5;

    /** Bytes in a mebibyte, the unit every heap number is reported in. */
    public static final double BYTES_PER_MIB = 1024.0 * 1024.0;

    /** Highest used-heap reading since {@link #resetPeak()}, in bytes. */
    public volatile long peakUsedBytes;

    /** Whether the sampling thread should keep polling. */
    public volatile boolean running;

    /** The polling thread, or null before {@link #start()} and after {@link #stop()}. */
    public Thread samplerThread;

    /**
     * Used heap right now: what the JVM has committed minus what it reports free.
     *
     * @return used heap in bytes
     */
    public static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * Starts polling, seeding the peak with the current reading. Calling it while already running
     * only reseeds the peak.
     */
    public void start() {
        resetPeak();
        if (running) {
            return;
        }
        running = true;
        samplerThread = new Thread(this::poll, "heap-sampler");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    /** Drops the high-water mark back to the current reading, which starts a fresh window. */
    public void resetPeak() {
        peakUsedBytes = usedHeapBytes();
    }

    /**
     * High-water mark of the current window, folding in a reading taken now so a window shorter
     * than {@link #SAMPLE_INTERVAL_MILLIS} still reports something real.
     *
     * @return peak used heap in bytes since the last reset
     */
    public long peakBytes() {
        long used = usedHeapBytes();
        long peak = peakUsedBytes;
        return Math.max(peak, used);
    }

    /** Stops the polling thread and waits for it to finish. */
    public void stop() {
        running = false;
        Thread thread = samplerThread;
        samplerThread = null;
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(SAMPLE_INTERVAL_MILLIS * 2);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The sampling loop: read used heap, raise the peak, sleep, repeat until stopped. */
    private void poll() {
        while (running) {
            long used = usedHeapBytes();
            if (used > peakUsedBytes) {
                peakUsedBytes = used;
            }
            try {
                Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
