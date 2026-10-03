package ixdar.geometry.mesh.quadlayout.solver;

/**
 * What a singular solver system turned out to be: failing pivot, null-vector support, cause.
 *
 * <p>The {@code support*} arrays are parallel and hold at most {@link #MAX_RECORDED_SUPPORT} of
 * the largest entries; {@link #supportSize} is the true count.
 */
public final class SingularSystemDiagnosis {

    public static final int CLASSIFICATION_UNKNOWN = 0;

    public static final int CLASSIFICATION_CHART_GAUGE = 1;

    public static final int CLASSIFICATION_SLIVER_CLUSTER = 2;

    public static final int CLASSIFICATION_DUPLICATE_ROW = 3;

    public static final int MAX_RECORDED_SUPPORT = 4096;

    public static final int LOGGED_VERTEX_COUNT = 20;

    public static final int POSITION_COMPONENTS = 3;

    /** Row the backend stopped on, or {@link SingularSystemException#UNKNOWN_PIVOT}. */
    public int pivotIndex = SingularSystemException.UNKNOWN_PIVOT;

    /** Number of free variables in the factored system. */
    public int dimension;

    /** Diagonal shift the backend ladder added to factorize; zero when it never had to. */
    public double appliedDiagonalShift;

    /** Rayleigh quotient of the recovered null vector, {@code zᵀAz / zᵀz}; near zero when real. */
    public double nullVectorEnergy;

    /** One of the {@code CLASSIFICATION_*} constants. */
    public int classification = CLASSIFICATION_UNKNOWN;

    /** Number of DOFs in the null vector's support, counting entries beyond the recorded ones. */
    public int supportSize;

    /** Distinct charts the support touches; 1 is the chart-gauge signature. */
    public int distinctChartCount;

    /** Support entries whose mesh vertex sits on a (near) zero-area face. */
    public int degenerateVertexCount;

    /**
     * Free DOFs whose matrix row holds a NaN or an infinity. Nonzero means the assembly itself is
     * broken, so the support lists those rows instead of a recovered null direction.
     */
    public int nonFiniteDofCount;

    /** Final-DOF index of each recorded support entry. */
    public int[] supportDof = new int[0];

    /** Null-vector component of each recorded support entry, normalized to a unit maximum. */
    public double[] supportWeight = new double[0];

    /** Chart owning each recorded support entry, or -1 when the DOF maps to no chart. */
    public int[] supportChartId = new int[0];

    /** Mesh vertex of each recorded support entry, or -1 when the DOF maps to no vertex. */
    public int[] supportVertexId = new int[0];

    /** Position of each recorded support entry's vertex, {@link #POSITION_COMPONENTS} per entry. */
    public double[] supportVertexPosition = new double[0];

    /**
     * Human-readable name of a classification code.
     *
     * @param classification one of the {@code CLASSIFICATION_*} constants
     * @return the hyphenated name the log line and the automation route both use
     */
    public static String classificationName(int classification) {
        return switch (classification) {
        case CLASSIFICATION_CHART_GAUGE -> "chart-gauge";
        case CLASSIFICATION_SLIVER_CLUSTER -> "sliver-cluster";
        case CLASSIFICATION_DUPLICATE_ROW -> "duplicate-row";
        default -> "unknown";
        };
    }

    /**
     * The {@code [seamless] singular} log line: classification, support size, applied shift, and
     * the first {@link #LOGGED_VERTEX_COUNT} offending vertex positions.
     *
     * @return one line of text, no trailing newline
     */
    public String logLine() {
        StringBuilder line = new StringBuilder("[seamless] singular ")
                .append(classificationName(classification))
                .append(" pivot ").append(pivotIndex)
                .append(" of ").append(dimension)
                .append(", support ").append(supportSize)
                .append(" DOFs over ").append(distinctChartCount)
                .append(" charts (").append(degenerateVertexCount)
                .append(" on zero-area faces, ").append(nonFiniteDofCount)
                .append(" non-finite), shift ").append(appliedDiagonalShift)
                .append(", null energy ").append(nullVectorEnergy)
                .append(", vertices");
        int printed = Math.min(LOGGED_VERTEX_COUNT, supportDof.length);
        for (int entry = 0; entry < printed; entry++) {
            line.append(' ').append(supportVertexId[entry]).append('(')
                    .append((float) supportVertexPosition[entry * POSITION_COMPONENTS]).append(',')
                    .append((float) supportVertexPosition[entry * POSITION_COMPONENTS + 1])
                    .append(',')
                    .append((float) supportVertexPosition[entry * POSITION_COMPONENTS + 2])
                    .append(')');
        }
        if (printed == 0) {
            line.append(" none");
        }
        return line.toString();
    }
}
