package ixdar.geometry.mesh.data.bundle;

import java.util.Arrays;

/**
 * Re-splits a welded triangle buffer so every vertex carries one UV, which is all a vertex buffer
 * or a glTF export can address. The inverse of the weld the glTF reader performs: a vertex appears
 * once per distinct UV its corners hold.
 */
public final class CornerUvSplit {

    public static final int NO_COPY = -1;

    public static final int COMPONENTS_PER_VERTEX = 2;

    private CornerUvSplit() {
    }

    /**
     * Longest UV array {@link #split} can fill: one split vertex per corner is the worst case.
     *
     * @param triangleCount triangles in the welded buffer
     * @return required length of the caller's UV array
     */
    public static int maxSplitUvLength(int triangleCount) {
        return triangleCount * CornerUvField.CORNERS_PER_FACE * COMPONENTS_PER_VERTEX;
    }

    /**
     * Split every vertex whose corners disagree on a UV, keeping vertices whose corners agree. Faces
     * come out in {@code faceOrder}, so a draw can group them by the material they name.
     *
     * @param triangleCorners welded vertex index of every corner, three per triangle
     * @param uv per-corner UVs over the same triangles
     * @param faceOrder triangles in the order to emit them, or null for buffer order
     * @param splitUv receives {@code (u, v)} per split vertex; at least {@link #maxSplitUvLength}
     *     long
     * @param splitCorners receives the split vertex of every emitted corner; one slot per corner
     * @param weldedOfSplit receives the welded vertex each split vertex copies; one slot per corner
     * @return the number of split vertices written
     * @throws IllegalArgumentException when an output array is too short
     */
    public static int split(int[] triangleCorners, CornerUvField uv, int[] faceOrder, float[] splitUv,
            int[] splitCorners, int[] weldedOfSplit) {
        int cornerCount = triangleCorners.length;
        if (splitUv == null || splitUv.length < cornerCount * COMPONENTS_PER_VERTEX
                || splitCorners.length < cornerCount || weldedOfSplit.length < cornerCount) {
            throw new IllegalArgumentException("split outputs must hold one entry per corner");
        }
        int weldedCount = 0;
        for (int welded : triangleCorners) {
            weldedCount = Math.max(weldedCount, welded + 1);
        }
        int[] firstCopy = new int[weldedCount];
        int[] nextCopy = new int[cornerCount];
        Arrays.fill(firstCopy, NO_COPY);

        int splitCount = 0;
        for (int corner = 0; corner < cornerCount; corner++) {
            int source = faceOrder == null ? corner
                    : faceOrder[corner / CornerUvField.CORNERS_PER_FACE]
                            * CornerUvField.CORNERS_PER_FACE
                            + corner % CornerUvField.CORNERS_PER_FACE;
            int welded = triangleCorners[source];
            float cornerU = (float) uv.cornerU[source];
            float cornerV = (float) uv.cornerV[source];
            int found = NO_COPY;
            for (int copy = firstCopy[welded]; copy != NO_COPY; copy = nextCopy[copy]) {
                if (Float.floatToRawIntBits(splitUv[copy * COMPONENTS_PER_VERTEX])
                            == Float.floatToRawIntBits(cornerU)
                        && Float.floatToRawIntBits(splitUv[copy * COMPONENTS_PER_VERTEX + 1])
                            == Float.floatToRawIntBits(cornerV)) {
                    found = copy;
                    break;
                }
            }
            if (found == NO_COPY) {
                found = splitCount++;
                splitUv[found * COMPONENTS_PER_VERTEX] = cornerU;
                splitUv[found * COMPONENTS_PER_VERTEX + 1] = cornerV;
                weldedOfSplit[found] = welded;
                nextCopy[found] = firstCopy[welded];
                firstCopy[welded] = found;
            }
            splitCorners[corner] = found;
        }
        return splitCount;
    }
}
