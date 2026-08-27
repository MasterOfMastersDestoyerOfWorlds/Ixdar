package ixdar.geometry.mesh.quadlayout;

import java.util.Arrays;

import ixdar.geometry.mesh.quadlayout.gridmap.IntegerGridMap;

/**
 * Charts covering a surface and the transition across each chart boundary: a
 * quarter-turn rotation plus a translation carrying chart A's coordinates into
 * chart B's. One concrete table each chart-producing stage fills, so consumers
 * read adjacency and transitions the same way everywhere.
 */
public final class ChartAtlas {

    /** Absent chart, boundary side or transition. */
    public static final int NONE = -1;

    /** Entries of a transition triple: quarter turns, u translation, v translation. */
    public static final int TRANSITION_ENTRIES = 3;

    /** The number of charts. */
    public final int chartCount;

    /** Chart owning each face; {@link #NONE} for a face outside every chart. */
    public final int[] chartOfFace;

    /** Per boundary, the chart the stored transition maps from. */
    public final int[] chartA;

    /**
     * Per boundary, the chart the stored transition maps into; {@link #NONE} on a
     * surface boundary with only one side.
     */
    public final int[] chartB;

    /**
     * Per boundary, the transition's quarter turns; {@link #NONE} where no
     * transition exists.
     */
    public final int[] quarterTurns;

    /** Per boundary, the transition's u translation. */
    public final double[] translationU;

    /** Per boundary, the transition's v translation. */
    public final double[] translationV;

    /** Whether every translation is integral, making the transitions grid automorphisms. */
    public final boolean integral;

    /**
     * Allocates the atlas with every chart, side and transition {@link #NONE}.
     *
     * @param chartCount    number of charts
     * @param faceCount     number of faces the charts cover
     * @param boundaryCount number of chart boundaries
     * @param integral      whether every translation is integral
     */
    public ChartAtlas(int chartCount, int faceCount, int boundaryCount, boolean integral) {
        this.chartCount = chartCount;
        this.chartOfFace = new int[faceCount];
        this.chartA = new int[boundaryCount];
        this.chartB = new int[boundaryCount];
        this.quarterTurns = new int[boundaryCount];
        this.translationU = new double[boundaryCount];
        this.translationV = new double[boundaryCount];
        this.integral = integral;
        Arrays.fill(chartOfFace, NONE);
        Arrays.fill(chartA, NONE);
        Arrays.fill(chartB, NONE);
        Arrays.fill(quarterTurns, NONE);
    }

    /**
     * The chart on the other side of a boundary.
     *
     * @param boundary  boundary crossed
     * @param fromChart chart being left
     * @throws IllegalStateException when the chart bounds neither side
     * @return the opposite chart, possibly {@link #NONE}
     */
    public int chartAcross(int boundary, int fromChart) {
        return storedForward(boundary, fromChart) ? chartB[boundary] : chartA[boundary];
    }

    /**
     * Whether a boundary carries a transition.
     *
     * @param boundary boundary to test
     * @return whether the transition exists
     */
    public boolean hasTransition(int boundary) {
        return quarterTurns[boundary] != NONE;
    }

    /**
     * Maps a chart point across a boundary's transition, in place.
     *
     * @param boundary  boundary crossed
     * @param fromChart chart the point is currently expressed in
     * @param pointUv   the point, replaced by its image in the opposite chart
     * @throws IllegalStateException when the chart bounds neither side
     */
    public void mapPoint(int boundary, int fromChart, double[] pointUv) {
        int turns = quarterTurns[boundary];
        if (storedForward(boundary, fromChart)) {
            IntegerGridMap.rotate(turns, pointUv[0], pointUv[1], pointUv);
            pointUv[0] += translationU[boundary];
            pointUv[1] += translationV[boundary];
        } else {
            IntegerGridMap.rotate((IntegerGridMap.QUARTER_TURNS - turns)
                    % IntegerGridMap.QUARTER_TURNS,
                    pointUv[0] - translationU[boundary],
                    pointUv[1] - translationV[boundary], pointUv);
        }
    }

    /**
     * Maps a direction's quarter turns across a boundary's transition.
     *
     * @param boundary  boundary crossed
     * @param fromChart chart the direction is currently expressed in
     * @param turns     direction as quarter turns
     * @throws IllegalStateException when the chart bounds neither side
     * @return the direction's quarter turns in the opposite chart
     */
    public int mapTurns(int boundary, int fromChart, int turns) {
        if (storedForward(boundary, fromChart)) {
            return (turns + quarterTurns[boundary]) % IntegerGridMap.QUARTER_TURNS;
        }
        return (turns + IntegerGridMap.QUARTER_TURNS - quarterTurns[boundary])
                % IntegerGridMap.QUARTER_TURNS;
    }

    /**
     * The transition carrying a chart's coordinates across a boundary, as a
     * {@code {quarterTurns, translationU, translationV}} triple into the opposite
     * chart.
     *
     * @param boundary  boundary crossed
     * @param fromChart chart the triple maps from
     * @throws IllegalStateException when the chart bounds neither side
     * @return the transition triple
     */
    public double[] transition(int boundary, int fromChart) {
        double[] forward = { quarterTurns[boundary], translationU[boundary],
                translationV[boundary] };
        return storedForward(boundary, fromChart) ? forward : invert(forward);
    }

    /**
     * The inverse of a transition triple.
     *
     * @param transition the triple {@code {quarterTurns, translationU, translationV}}
     * @return its inverse in the same encoding
     */
    public static double[] invert(double[] transition) {
        int turns = (IntegerGridMap.QUARTER_TURNS - (int) transition[0])
                % IntegerGridMap.QUARTER_TURNS;
        double[] rotated = new double[IntegerGridMap.GRID_COORDINATES];
        IntegerGridMap.rotate(turns, transition[1], transition[2], rotated);
        return new double[] { turns, -rotated[0], -rotated[1] };
    }

    /**
     * The composition applying the inner transition first, then the outer.
     *
     * @param outer the transition applied second
     * @param inner the transition applied first
     * @return the composed triple in the same encoding
     */
    public static double[] compose(double[] outer, double[] inner) {
        double[] rotated = new double[IntegerGridMap.GRID_COORDINATES];
        IntegerGridMap.rotate((int) outer[0], inner[1], inner[2], rotated);
        return new double[] { ((int) outer[0] + (int) inner[0]) % IntegerGridMap.QUARTER_TURNS,
                outer[1] + rotated[0], outer[2] + rotated[1] };
    }

    /**
     * Whether the stored transition runs out of the given chart, which is the side
     * test every crossing shares.
     *
     * @param boundary  boundary crossed
     * @param fromChart chart being left
     * @throws IllegalStateException when the chart bounds neither side
     * @return true leaving chart A, false leaving chart B
     */
    private boolean storedForward(int boundary, int fromChart) {
        if (chartA[boundary] == fromChart) {
            return true;
        }
        if (chartB[boundary] == fromChart) {
            return false;
        }
        throw new IllegalStateException(
                "boundary " + boundary + " does not bound chart " + fromChart);
    }
}
