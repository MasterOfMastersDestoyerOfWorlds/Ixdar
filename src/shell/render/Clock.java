package shell.render;

public class Clock {
    public static final double TAU = (2 * Math.PI);
    public static final double startTimeMillis = System.currentTimeMillis();
    public static final double startTimeSeconds = startTimeMillis / 1000.0;

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

    public static float spinTick(float radsPerSecond) {
        double time = System.currentTimeMillis();
        double timeSeconds = (double) (((long) time) / 1000) - startTimeSeconds;
        float retVal = (float) ((radsPerSecond * timeSeconds) % TAU);
        return retVal;
    }

}
