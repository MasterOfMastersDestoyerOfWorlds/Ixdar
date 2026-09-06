package ixdar.geometry.mesh.data;

import java.util.Arrays;

import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * Re-splits a welded mesh so every vertex carries one UV, which is all a vertex buffer or a glTF
 * export can address. The inverse of the weld the glTF reader performs: a vertex appears once per
 * distinct UV its corners hold.
 */
public final class CornerUvSplit {

    /** Empty chain terminator in the per-welded-vertex copy lists. */
    public static final int NO_COPY = -1;

    /** Components written per split vertex into the caller's UV array. */
    public static final int COMPONENTS_PER_VERTEX = 2;

    /** Floats per position and per normal. */
    public static final int FLOATS_PER_VERTEX = 3;

    private CornerUvSplit() {
    }

    /**
     * Longest UV array {@link #split} can fill: one split vertex per corner is the worst case.
     *
     * @param mesh welded triangle mesh
     * @return required length of the caller's UV array
     */
    public static int maxSplitUvLength(ArrayMesh mesh) {
        return mesh.faceCount() * CornerUvField.CORNERS_PER_FACE * COMPONENTS_PER_VERTEX;
    }

    /**
     * Split every vertex whose corners disagree on a UV, keeping vertices whose corners agree, and
     * write each split vertex's UV into {@code splitUv}.
     *
     * @param mesh welded triangle mesh
     * @param uv per-corner UVs over {@code mesh}
     * @param splitUv receives {@code (u, v)} per split vertex; at least
     *     {@link #maxSplitUvLength} long, of which the returned mesh's vertex count is written
     * @return the split mesh, its positions and normals copied from the vertices they came from
     * @throws IllegalArgumentException when {@code splitUv} is too short
     */
    public static ArrayMesh split(ArrayMesh mesh, CornerUvField uv, float[] splitUv) {
        if (splitUv == null || splitUv.length < maxSplitUvLength(mesh)) {
            throw new IllegalArgumentException("splitUv must be at least maxSplitUvLength long");
        }
        int cornerCount = mesh.faceCount() * CornerUvField.CORNERS_PER_FACE;
        int weldedCount = mesh.vertexCount();
        int[] faceIndices = mesh.copyFaceIndices();
        float[] weldedPositions = mesh.copyPositions();
        float[] weldedNormals = mesh.copyNormals();

        int[] firstCopy = new int[weldedCount];
        int[] nextCopy = new int[cornerCount];
        int[] copyWelded = new int[cornerCount];
        int[] cornerVertex = new int[cornerCount];
        Arrays.fill(firstCopy, NO_COPY);

        int splitCount = 0;
        for (int corner = 0; corner < cornerCount; corner++) {
            int welded = faceIndices[corner];
            float cornerU = (float) uv.cornerU[corner];
            float cornerV = (float) uv.cornerV[corner];
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
                copyWelded[found] = welded;
                nextCopy[found] = firstCopy[welded];
                firstCopy[welded] = found;
            }
            cornerVertex[corner] = found;
        }

        float[] positions = new float[splitCount * FLOATS_PER_VERTEX];
        float[] normals = new float[splitCount * FLOATS_PER_VERTEX];
        for (int copy = 0; copy < splitCount; copy++) {
            int welded = copyWelded[copy];
            System.arraycopy(weldedPositions, welded * FLOATS_PER_VERTEX, positions,
                    copy * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
            System.arraycopy(weldedNormals, welded * FLOATS_PER_VERTEX, normals,
                    copy * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
        }
        return new ArrayMesh(positions, normals, cornerVertex, CornerUvField.CORNERS_PER_FACE);
    }
}
