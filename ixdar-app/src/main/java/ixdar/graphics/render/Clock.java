package ixdar.graphics.render;

public class Clock {
    public static final double NUM_1000_0 = 1000.0;
    public static final float NUM_2 = 2f;
    public static final int NUM_1000 = 1000;
    public static final double NUM_1000000000_0 = 1000000000.0;
    public static final int NUM_60 = 60;
    public static final double TAU = (2 * Math.PI);
    public static final double startTimeMillis = System.currentTimeMillis();
    public static final double startTimeSeconds = startTimeMillis / 1000.0;
    public static final long startTimeNanoSeconds = System.nanoTime();

    public static Long lastFrameDouble = 0L;

    public static Long lastFrameDouble2 = 0L;

    static int frameNum;

    static float lastFullSecond;
    static float lastFPS;
    static int samples;

    /**
     * TODO: document {@code oscillate}.
     *
     * @param offset TODO: describe
     * @param range TODO: describe
     * @param radsPerSecond TODO: describe
     * @return TODO: describe
     */
    public static float oscillate(double offset, double range, double radsPerSecond) {
        return oscillate((float) offset, (float) range, (float) radsPerSecond);
    }

    /**
     * TODO: document {@code oscillate}.
     *
     * @param offset TODO: describe
     * @param range TODO: describe
     * @param radsPerSecond TODO: describe
     * @return TODO: describe
     */
    public static float oscillate(float offset, float range, float radsPerSecond) {
        double timeSeconds = (((double) System.currentTimeMillis()) / NUM_1000_0) - startTimeSeconds;
        return ((float) (range * (Math.sin(radsPerSecond * timeSeconds) + 1)) / NUM_2) + offset;
    }

    /**
     * TODO: document {@code spin}.
     *
     * @param radsPerSecond TODO: describe
     * @return TODO: describe
     */
    public static float spin(float radsPerSecond) {
        double timeSeconds = (((double) System.currentTimeMillis()) / NUM_1000_0) - startTimeSeconds;
        return ((float) ((radsPerSecond * timeSeconds) % TAU));

    }

    /**
     * TODO: document {@code spin}.
     *
     * @param radsPerSecond TODO: describe
     * @param range TODO: describe
     * @return TODO: describe
     */
    public static float spin(float radsPerSecond, float range) {
        double timeSeconds = (((double) System.currentTimeMillis()) / NUM_1000_0) - startTimeSeconds;
        return ((float) (range * ((((radsPerSecond * timeSeconds) % TAU)) / TAU)));

    }

    /**
     * TODO: document {@code spin}.
     *
     * @param radsPerSecond TODO: describe
     * @param range TODO: describe
     * @param offset TODO: describe
     * @return TODO: describe
     */
    public static float spin(float radsPerSecond, float range, float offset) {
        double timeSeconds = (((double) System.currentTimeMillis()) / NUM_1000_0) - startTimeSeconds;
        return ((float) (range * ((((radsPerSecond * timeSeconds) % TAU)) / TAU))) + offset;
    }

    /**
     * TODO: document {@code spinTick}.
     *
     * @param radsPerSecond TODO: describe
     * @return TODO: describe
     */
    public static float spinTick(float radsPerSecond) {
        double time = System.currentTimeMillis();
        double timeSeconds = (double) (((long) time) / NUM_1000) - startTimeSeconds;
        float retVal = (float) ((radsPerSecond * timeSeconds) % TAU);
        return retVal;
    }

    /**
     * TODO: document {@code time}.
     *
     * @return TODO: describe
     */
    public static float time() {
        double timeSeconds = ((double) (System.nanoTime() - startTimeNanoSeconds) / NUM_1000000000_0);
        return (float) timeSeconds;
    }

    /**
     * TODO: document {@code frameRendered}.
     */
    public static void frameRendered() {
        lastFrameDouble2 = lastFrameDouble;
        lastFrameDouble = System.nanoTime();
        frameNum = (frameNum + 1) % NUM_60;
    }

    /**
     * TODO: document {@code deltaTime}.
     *
     * @return TODO: describe
     */
    public static double deltaTime() {
        return (double) (System.nanoTime() - lastFrameDouble2) / NUM_1000000000_0;
    }

    /**
     * TODO: document {@code sin}.
     *
     * @param offset TODO: describe
     * @param amplitude TODO: describe
     * @param freq TODO: describe
     * @param phase TODO: describe
     * @return TODO: describe
     */
    public static float sin(float offset, float amplitude, float freq, float phase) {
        double timeSeconds = (((double) System.currentTimeMillis()) / NUM_1000_0) - startTimeSeconds;
        return ((float) (amplitude * (Math.sin(freq * timeSeconds + phase) + 1)) / NUM_2) + offset;
    }

    /**
     * TODO: document {@code fps}.
     *
     * @return TODO: describe
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
