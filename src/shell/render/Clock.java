package shell.render;

public class Clock {
    public static final double TAU = (2 * Math.PI);
    public static final double startTimeMillis = System.currentTimeMillis();
    public static final double startTimeSeconds = startTimeMillis / 1000.0;
    public static final long startTimeNanoSeconds = System.nanoTime();

    public static float oscillate(double offset, double range, double radsPerSecond) {
        return oscillate((float) offset, (float) range, (float) radsPerSecond);
    }

    public static float oscillate(float offset, float range, float radsPerSecond) {
        double timeSeconds = (((double) System.currentTimeMillis()) / 1000.0) - startTimeSeconds;
        return ((float) (range * (Math.sin(radsPerSecond * timeSeconds) + 1)) / 2f) + offset;
    }

    public static float spin(float radsPerSecond) {
        double timeSeconds = (((double) System.currentTimeMillis()) / 1000.0) - startTimeSeconds;
        return ((float) ((radsPerSecond * timeSeconds) % TAU));

    }

    public static float spin(float radsPerSecond, float range) {
        double timeSeconds = (((double) System.currentTimeMillis()) / 1000.0) - startTimeSeconds;
        return ((float) (range * ((((radsPerSecond * timeSeconds) % TAU)) / TAU)));

    }

    public static float spin(float radsPerSecond, float range, float offset) {
        double timeSeconds = (((double) System.currentTimeMillis()) / 1000.0) - startTimeSeconds;
        return ((float) (range * ((((radsPerSecond * timeSeconds) % TAU)) / TAU))) + offset;
    }

    public static float spinTick(float radsPerSecond) {
        double time = System.currentTimeMillis();
        double timeSeconds = (double) (((long) time) / 1000) - startTimeSeconds;
        float retVal = (float) ((radsPerSecond * timeSeconds) % TAU);
        return retVal;
    }

    public static float time() {
        double timeSeconds = ((double) (System.nanoTime() - startTimeNanoSeconds) / 1000000000.0);
        return (float) timeSeconds;
    }

    static int frameNum;

    public static Long lastFrameDouble = 0L;

    public static Long lastFrameDouble2 = 0L;

    public static void frameRendered() {
        lastFrameDouble2 = lastFrameDouble;
        lastFrameDouble = System.nanoTime();
        frameNum = (frameNum + 1) % 60;
    }

    public static double deltaTime() {
        return (double) (System.nanoTime() - lastFrameDouble2) / 1000000000.0;
    }

    public static float sin(float offset, float amplitude, float freq, float phase) {
        double timeSeconds = (((double) System.currentTimeMillis()) / 1000.0) - startTimeSeconds;
        return ((float) (amplitude * (Math.sin(freq * timeSeconds + phase) + 1)) / 2f) + offset;
    }

    static float lastFullSecond;
    static float lastFPS;
    static int samples;

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
