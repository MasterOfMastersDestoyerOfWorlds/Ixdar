package ixdar.graphics.render;

/**
 * Wall-clock helpers for the renderer: monotonic time, frame deltas, and
 * driver functions (sine oscillation, modulo "spin", FPS counter) used by
 * shaders and animations across the graphics layer.
 */
public class Clock {
    public static final double MILLIS_PER_SECOND = 1000.0;
    public static final double NANOS_PER_SECOND = 1000000000.0;
    public static final int FRAME_COUNTER_MODULUS = 60;
    public static final double TAU = (2 * Math.PI);
    public static final double START_TIME_MILLIS = System.currentTimeMillis();
    public static final double START_TIME_SECONDS = START_TIME_MILLIS / 1000.0;
    public static final long START_TIME_NANOSECONDS = System.nanoTime();

    public static Long lastFrameDouble = 0L;

    public static Long lastFrameDouble2 = 0L;

    /**
     * Frames completed since startup, never wrapped. Automation waits on this to know a
     * synthesized input has actually been drawn.
     */
    public static volatile long framesRendered;

    static int frameNum;

    static float lastFullSecond;
    static float lastFPS;
    static int samples;

    /**
     * Double-precision overload of {@link #oscillate(float, float, float)}.
     *
     * @param offset value at the bottom of the swing
     * @param range peak-to-peak amplitude
     * @param radsPerSecond angular frequency
     * @return offset plus a cosine-shifted sine in [0, range]
     */
    public static float oscillate(double offset, double range, double radsPerSecond) {
        return oscillate((float) offset, (float) range, (float) radsPerSecond);
    }

    /**
     * Sample a sine wave that swings from {@code offset} to
     * {@code offset + range} at the given angular frequency, anchored to
     * Clock startup time.
     *
     * @param offset value at the bottom of the swing
     * @param range peak-to-peak amplitude
     * @param radsPerSecond angular frequency
     * @return offset plus a cosine-shifted sine in [0, range]
     */
    public static float oscillate(float offset, float range, float radsPerSecond) {
        double timeSeconds = (((double) System.currentTimeMillis()) / MILLIS_PER_SECOND) - START_TIME_SECONDS;
        return ((float) (range * (Math.sin(radsPerSecond * timeSeconds) + 1)) / 2f) + offset;
    }

    /**
     * Monotonic angular position modulo 2pi at the given rate, anchored to
     * Clock startup time.
     *
     * @param radsPerSecond angular rate
     * @return current angle in radians, wrapped to [0, 2pi)
     */
    public static float spin(float radsPerSecond) {
        double timeSeconds = (((double) System.currentTimeMillis()) / MILLIS_PER_SECOND) - START_TIME_SECONDS;
        return ((float) ((radsPerSecond * timeSeconds) % TAU));

    }

    /**
     * {@link #spin(float)} mapped onto a [0, range) sawtooth.
     *
     * @param radsPerSecond angular rate
     * @param range output amplitude
     * @return current sawtooth phase in [0, range)
     */
    public static float spin(float radsPerSecond, float range) {
        double timeSeconds = (((double) System.currentTimeMillis()) / MILLIS_PER_SECOND) - START_TIME_SECONDS;
        return ((float) (range * ((((radsPerSecond * timeSeconds) % TAU)) / TAU)));

    }

    /**
     * {@link #spin(float, float)} shifted by {@code offset}.
     *
     * @param radsPerSecond angular rate
     * @param range output amplitude
     * @param offset additive bias
     * @return offset plus current sawtooth phase in [0, range)
     */
    public static float spin(float radsPerSecond, float range, float offset) {
        double timeSeconds = (((double) System.currentTimeMillis()) / MILLIS_PER_SECOND) - START_TIME_SECONDS;
        return ((float) (range * ((((radsPerSecond * timeSeconds) % TAU)) / TAU))) + offset;
    }

    /**
     * Variant of {@link #spin(float)} quantized to whole-second ticks.
     * Useful for blink-style animations.
     *
     * @param radsPerSecond angular rate
     * @return current angle in radians, wrapped to [0, TAU), advancing once per second
     */
    public static float spinTick(float radsPerSecond) {
        double time = System.currentTimeMillis();
        double timeSeconds = (double) (((long) time) / 1000) - START_TIME_SECONDS;
        float retVal = (float) ((radsPerSecond * timeSeconds) % TAU);
        return retVal;
    }

    /**
     * Monotonic seconds elapsed since Clock startup, computed from
     * {@link System#nanoTime()}.
     *
     * @return seconds since startup as a float
     */
    public static float time() {
        double timeSeconds = ((double) (System.nanoTime() - START_TIME_NANOSECONDS) / NANOS_PER_SECOND);
        return (float) timeSeconds;
    }

    /**
     * Mark the end of a rendered frame. Shifts the previous frame timestamp
     * into the prior slot so deltaTime reports the elapsed gap, and advances
     * the modulo-60 frame counter.
     */
    public static void frameRendered() {
        lastFrameDouble2 = lastFrameDouble;
        lastFrameDouble = System.nanoTime();
        frameNum = (frameNum + 1) % FRAME_COUNTER_MODULUS;
        framesRendered++;
    }

    /**
     * Frames drawn since startup, monotonic and never wrapped.
     *
     * @return the running count of completed {@link #frameRendered()} calls
     */
    public static long framesRendered() {
        return framesRendered;
    }

    /**
     * Seconds elapsed between the two most recent {@link #frameRendered()}
     * calls, computed from {@link System#nanoTime()}.
     *
     * @return delta time of the last completed frame in seconds
     */
    public static double deltaTime() {
        return (double) (System.nanoTime() - lastFrameDouble2) / NANOS_PER_SECOND;
    }

    /**
     * Like {@link #oscillate(float, float, float)} with an explicit phase
     * offset. Samples a sine wave that swings from {@code offset} to
     * {@code offset + amplitude} at angular frequency {@code freq}, anchored
     * to Clock startup time and shifted by {@code phase} radians.
     *
     * @param offset value at the bottom of the swing
     * @param amplitude peak-to-peak amplitude
     * @param freq angular frequency in radians/sec
     * @param phase phase offset in radians
     * @return offset plus a cosine-shifted sine in [0, amplitude]
     */
    public static float sin(float offset, float amplitude, float freq, float phase) {
        double timeSeconds = (((double) System.currentTimeMillis()) / MILLIS_PER_SECOND) - START_TIME_SECONDS;
        return ((float) (amplitude * (Math.sin(freq * timeSeconds + phase) + 1)) / 2f) + offset;
    }

    /**
     * Running frames-per-second estimate. Each call increments a sample
     * counter; once a wall-clock second elapses, the counter is published as
     * the new FPS value and reset. Call once per rendered frame.
     *
     * @return frames-per-second observed during the previous full second
     */
    public static float fps() {
        int timeSeconds = (int) time();
        if (timeSeconds > lastFullSecond) {
            lastFPS = samples;
            lastFullSecond = timeSeconds;
            samples = 0;
        }
        samples++;
        return lastFPS;
    }

}
