package ixdar.geometry.mesh.csg;

/**
 * One Manifold solid read back as a {@code MeshGL64}: geometry plus the run and face tables
 * naming each triangle's input solid and coplanar input face.
 */
public final class ManifoldMeshExport {

    /** Coordinates per vertex, and equally corners per triangle. */
    public static final int THREE = 3;

    /** Vertex positions, three doubles per vertex. */
    public final double[] vertexPositions;

    /** Triangle corners as vertex indices, three per triangle. */
    public final long[] triangleCorners;

    /** Corner offset where each run starts, plus one trailing end offset; empty when untracked. */
    public final long[] runIndex;

    /** The originating solid's original id per run, parallel to the runs of {@link #runIndex}. */
    public final int[] runOriginalId;

    /**
     * Per triangle, Manifold's id of the coplanar input face it lies on, or empty when the kernel
     * did not track faces. The id is a triangle index into the originating solid's own export.
     */
    public final long[] faceId;

    /**
     * Store one solid's tables.
     *
     * @param vertexPositions vertex positions, three per vertex
     * @param triangleCorners triangle corners, three vertex indices per triangle
     * @param runIndex run start offsets in corners with a trailing end offset
     * @param runOriginalId original id per run
     * @param faceId coplanar source face id per triangle, or empty
     */
    public ManifoldMeshExport(double[] vertexPositions, long[] triangleCorners, long[] runIndex,
            int[] runOriginalId, long[] faceId) {
        this.vertexPositions = vertexPositions;
        this.triangleCorners = triangleCorners;
        this.runIndex = runIndex;
        this.runOriginalId = runOriginalId;
        this.faceId = faceId;
    }

    /**
     * Number of vertices in the export.
     *
     * @return vertex count
     */
    public int vertexCount() {
        return vertexPositions.length / THREE;
    }

    /**
     * Number of triangles in the export.
     *
     * @return triangle count
     */
    public int triangleCount() {
        return triangleCorners.length / THREE;
    }

    /**
     * Number of runs the export is partitioned into.
     *
     * @return run count, zero when the kernel tracked none
     */
    public int runCount() {
        return runOriginalId.length;
    }

    /**
     * First triangle of a run.
     *
     * @param run run index below {@link #runCount()}
     * @return the run's first triangle index
     */
    public int runFirstTriangle(int run) {
        return (int) (runIndex[run] / THREE);
    }

    /**
     * One past the last triangle of a run, clamped to the triangle count.
     *
     * @param run run index below {@link #runCount()}
     * @return the run's exclusive end triangle index
     */
    public int runEndTriangle(int run) {
        return (int) Math.min(triangleCount(), runIndex[run + 1] / THREE);
    }

    /**
     * Read one coordinate of one triangle corner.
     *
     * @param triangle triangle index
     * @param corner corner of the triangle, 0 to 2
     * @param axis coordinate axis, 0 to 2
     * @return the coordinate
     */
    public double cornerCoordinate(int triangle, int corner, int axis) {
        return vertexPositions[(int) triangleCorners[triangle * THREE + corner] * THREE + axis];
    }
}
