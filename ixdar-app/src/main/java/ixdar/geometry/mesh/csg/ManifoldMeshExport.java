package ixdar.geometry.mesh.csg;

/**
 * One Manifold solid read back as a {@code MeshGL64}: geometry plus the run and face tables
 * naming each triangle's input solid and coplanar input face.
 *
 * <p>A vertex holds {@link #propertiesPerVertex} doubles, the first three being its position and
 * any further channels the interpolated per-vertex properties the boolean carried through.
 */
public final class ManifoldMeshExport {

    /** Coordinates per vertex, and equally corners per triangle. */
    public static final int THREE = 3;

    /** Vertex property table, {@link #propertiesPerVertex} doubles per vertex. */
    public final double[] vertexProperties;

    /** Doubles per vertex in {@link #vertexProperties}, at least {@link #THREE}. */
    public final int propertiesPerVertex;

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
     * Property vertices that are the same point of the surface as {@link #mergeToVertex}'s entry
     * at the same index: the copies a property seam split off. Empty when nothing is split.
     */
    public final long[] mergeFromVertex;

    /** The vertex each {@link #mergeFromVertex} entry welds onto, parallel to it. */
    public final long[] mergeToVertex;

    /**
     * Store one solid's tables.
     *
     * @param vertexProperties vertex property table, {@code propertiesPerVertex} doubles per vertex
     * @param propertiesPerVertex doubles per vertex, position first
     * @param triangleCorners triangle corners, three vertex indices per triangle
     * @param runIndex run start offsets in corners with a trailing end offset
     * @param runOriginalId original id per run
     * @param faceId coplanar source face id per triangle, or empty
     * @param mergeFromVertex split-off property vertices, or empty
     * @param mergeToVertex the vertex each split-off one welds onto, parallel to it
     * @throws IllegalArgumentException when fewer than three channels per vertex are given
     */
    public ManifoldMeshExport(double[] vertexProperties, int propertiesPerVertex,
            long[] triangleCorners, long[] runIndex, int[] runOriginalId, long[] faceId,
            long[] mergeFromVertex, long[] mergeToVertex) {
        if (propertiesPerVertex < THREE) {
            throw new IllegalArgumentException(
                    "a vertex holds at least its three coordinates, got " + propertiesPerVertex);
        }
        this.vertexProperties = vertexProperties;
        this.propertiesPerVertex = propertiesPerVertex;
        this.triangleCorners = triangleCorners;
        this.runIndex = runIndex;
        this.runOriginalId = runOriginalId;
        this.faceId = faceId;
        this.mergeFromVertex = mergeFromVertex;
        this.mergeToVertex = mergeToVertex;
    }

    /**
     * Number of vertices in the export.
     *
     * @return vertex count
     */
    public int vertexCount() {
        return vertexProperties.length / propertiesPerVertex;
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
     * Read one property channel of one triangle corner, the first three channels being its
     * position.
     *
     * @param triangle triangle index
     * @param corner corner of the triangle, 0 to 2
     * @param channel property channel below {@link #propertiesPerVertex}
     * @return the channel's value at that corner
     */
    public double cornerCoordinate(int triangle, int corner, int channel) {
        int vertex = (int) triangleCorners[triangle * THREE + corner];
        return vertexProperties[vertex * propertiesPerVertex + channel];
    }
}
