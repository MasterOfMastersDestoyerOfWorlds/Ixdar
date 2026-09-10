package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.graph.HeapSampler;

/** The per-node heap sampler the DSL graph runtime attributes peak memory with. */
class HeapSamplerTest {

    /** Bytes the allocation probe holds live, comfortably above one sampling interval's noise. */
    private static final int PROBE_BYTES = 64 << 20;

    /** Readings taken while checking that the peak never falls inside one window. */
    private static final int MONOTONE_READINGS = 20;

    @Test
    void peakNeverFallsWithinOneWindow() {
        HeapSampler sampler = new HeapSampler();
        sampler.start();
        try {
            long previous = sampler.peakBytes();
            for (int reading = 0; reading < MONOTONE_READINGS; reading++) {
                long current = sampler.peakBytes();
                assertTrue(current >= previous,
                        "the high-water mark never drops between reads of one window");
                previous = current;
            }
        } finally {
            sampler.stop();
        }
    }

    @Test
    void peakRisesWithALiveAllocationAndResetsWithTheWindow() {
        HeapSampler sampler = new HeapSampler();
        sampler.start();
        try {
            long beforeAllocation = sampler.peakBytes();
            byte[] probe = new byte[PROBE_BYTES];
            probe[0] = 1;
            probe[probe.length - 1] = 1;
            long withAllocation = sampler.peakBytes();
            assertTrue(withAllocation >= beforeAllocation + PROBE_BYTES / 2,
                    "a live 64MiB array shows up in the peak: " + beforeAllocation
                            + " -> " + withAllocation);
            sampler.resetPeak();
            assertTrue(sampler.peakBytes() <= withAllocation + PROBE_BYTES,
                    "the reset drops the mark back onto the current reading");
        } finally {
            sampler.stop();
        }
    }

    @Test
    void stopEndsTheSamplingThread() throws InterruptedException {
        HeapSampler sampler = new HeapSampler();
        sampler.start();
        Thread thread = sampler.samplerThread;
        sampler.stop();
        thread.join(HeapSampler.SAMPLE_INTERVAL_MILLIS * MONOTONE_READINGS);

        assertTrue(!thread.isAlive(), "the sampler thread does not outlive the graph run");
    }
}
