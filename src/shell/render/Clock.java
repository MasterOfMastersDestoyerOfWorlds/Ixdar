package shell.render;

public class Clock {
    public static final double TAU = (2 * Math.PI);
    public static final double startTimeMillis = System.currentTimeMillis();
    public static final double startTimeSeconds = startTimeMillis / 1000.0;
    private static float lastFrameRendered = 0.0f;

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
        double timeSeconds = (((double) System.currentTimeMillis()) / 1000.0) - startTimeSeconds;
        return (float) timeSeconds;
    }

    public static void frameRendered() {
        lastFrameRendered = time();
    }

    public static float deltaTime() {
        return time() - lastFrameRendered;
    }

    public static float sin(float offset, float amplitude, float freq, float phase) {
        double timeSeconds = (((double) System.currentTimeMillis()) / 1000.0) - startTimeSeconds;
        return ((float) (amplitude * (Math.sin(freq * timeSeconds + phase) + 1)) / 2f) + offset;
    }

}
