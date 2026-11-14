package ixdar.numeric.cluster;

import java.util.Random;

/**
 * The playground's "Spirals (three arms)" data set: points along three interleaved spiral arms
 * inside the unit square, densest near the centre.
 */
public final class ThreeArmSpirals {
    public static final int ARMS = 3;
    public static final double TURNS = 1.25;
    public static final double OUTER_RADIUS = 0.9;
    public static final double NOISE = 0.035;

    private ThreeArmSpirals() {
    }

    /**
     * Points on the spirals, the same for the same seed. Point i lies on arm {@code i mod 3} at a
     * uniform parameter t: angle {@code 2 pi (arm / 3 + TURNS t)}, radius {@code OUTER_RADIUS t},
     * plus isotropic Gaussian noise of deviation {@code NOISE}.
     *
     * @param count number of points
     * @param seed  random seed
     * @return packed xy, two per point
     */
    public static double[] points(int count, long seed) {
        Random random = new Random(seed);
        double[] packed = new double[2 * count];
        for (int point = 0; point < count; point++) {
            double along = random.nextDouble();
            double angle = 2 * Math.PI * ((point % ARMS) / (double) ARMS + TURNS * along);
            double radius = OUTER_RADIUS * along;
            packed[2 * point] = radius * Math.cos(angle) + NOISE * random.nextGaussian();
            packed[2 * point + 1] = radius * Math.sin(angle) + NOISE * random.nextGaussian();
        }
        return packed;
    }
}
