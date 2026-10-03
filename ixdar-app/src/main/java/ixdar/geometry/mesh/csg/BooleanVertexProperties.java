package ixdar.geometry.mesh.csg;

import java.util.Arrays;

/**
 * One operand's vertex table as the kernel wants it: the position channels, then the UV channels it
 * interpolates at every vertex the intersection curve creates. A vertex whose corners disagree on a
 * UV splits into one kernel vertex per distinct UV, which the kernel's merge vectors weld back.
 */
public final class BooleanVertexProperties {

    public static final int THREE = QuadTriangulation.THREE;

    public static final int POSITION_AND_UV_CHANNELS = 5;

    public static final int NO_COPY = -1;

    public static final double MISSING_UV_STAND_IN = 0.0;

    /** Operand whose triangles are being handed to the kernel. */
    public final QuadTriangulation operand;

    /** Channels per vertex, {@link #THREE} or {@link #POSITION_AND_UV_CHANNELS}. */
    public final int propertiesPerVertex;

    /** Interleaved vertex table, {@link #propertiesPerVertex} doubles per kernel vertex. */
    public double[] vertexProperties;

    /** Triangle corners as kernel vertex indices, three per triangle. */
    public long[] triangleCorners;

    /**
     * Store the operand and the channel count both operands of one boolean must agree on.
     *
     * @param operand triangulated operand
     * @param propertiesPerVertex channels per vertex, at least {@link #THREE}
     * @throws IllegalArgumentException when fewer than the position channels are asked for
     */
    public BooleanVertexProperties(QuadTriangulation operand, int propertiesPerVertex) {
        if (propertiesPerVertex < THREE) {
            throw new IllegalArgumentException(
                    "a vertex holds at least its three coordinates, got " + propertiesPerVertex);
        }
        this.operand = operand;
        this.propertiesPerVertex = propertiesPerVertex;
    }

    /**
     * Fill {@link #vertexProperties} and {@link #triangleCorners}.
     *
     * @return this
     */
    public BooleanVertexProperties build() {
        int cornerCount = operand.triangles.length;
        triangleCorners = new long[cornerCount];
        if (propertiesPerVertex == THREE || operand.cornerU == null) {
            int vertexCount = operand.positions.length / THREE;
            vertexProperties = new double[vertexCount * propertiesPerVertex];
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                for (int axis = 0; axis < THREE; axis++) {
                    vertexProperties[vertex * propertiesPerVertex + axis] =
                            operand.positions[vertex * THREE + axis];
                }
            }
            for (int corner = 0; corner < cornerCount; corner++) {
                triangleCorners[corner] = operand.triangles[corner];
            }
            return this;
        }

        double[] properties = new double[cornerCount * propertiesPerVertex];
        int[] firstCopy = new int[operand.positions.length / THREE];
        int[] nextCopy = new int[cornerCount];
        Arrays.fill(firstCopy, NO_COPY);
        int splitCount = 0;
        for (int corner = 0; corner < cornerCount; corner++) {
            int vertex = operand.triangles[corner];
            double cornerU = Double.isFinite(operand.cornerU[corner])
                    ? operand.cornerU[corner] : MISSING_UV_STAND_IN;
            double cornerV = Double.isFinite(operand.cornerV[corner])
                    ? operand.cornerV[corner] : MISSING_UV_STAND_IN;
            int found = NO_COPY;
            for (int copy = firstCopy[vertex]; copy != NO_COPY; copy = nextCopy[copy]) {
                if (sameValue(properties[copy * propertiesPerVertex + THREE], cornerU)
                        && sameValue(properties[copy * propertiesPerVertex + THREE + 1], cornerV)) {
                    found = copy;
                    break;
                }
            }
            if (found == NO_COPY) {
                found = splitCount++;
                int base = found * propertiesPerVertex;
                for (int axis = 0; axis < THREE; axis++) {
                    properties[base + axis] = operand.positions[vertex * THREE + axis];
                }
                properties[base + THREE] = cornerU;
                properties[base + THREE + 1] = cornerV;
                nextCopy[found] = firstCopy[vertex];
                firstCopy[vertex] = found;
            }
            triangleCorners[corner] = found;
        }
        vertexProperties = Arrays.copyOf(properties, splitCount * propertiesPerVertex);
        return this;
    }

    /**
     * Whether two channel values are the same bit for bit, which is what makes two corners share a
     * kernel vertex rather than split it.
     *
     * @param stored value already written into the table
     * @param candidate value a further corner carries
     * @return true when the bits match
     */
    private static boolean sameValue(double stored, double candidate) {
        return Double.doubleToRawLongBits(stored) == Double.doubleToRawLongBits(candidate);
    }
}
