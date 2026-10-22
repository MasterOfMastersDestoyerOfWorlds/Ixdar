package ixdar.geometry.mesh.data.io;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.CornerUvField;
import ixdar.geometry.mesh.data.bundle.CornerUvSplit;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.nodegraph.field.UvField;

/**
 * Flattens a bundle into what a mesh file can address: triangles carrying one position, normal and
 * UV per vertex. Fans larger faces into triangles and re-splits the vertices whose corners disagree
 * on a UV, which is the inverse of the weld {@link GltfMeshParser} performs on import.
 */
public final class MeshExport {

    public static final int FLOATS_PER_VERTEX = 3;

    public static final int CORNERS_PER_TRIANGLE = 3;

    public static final int UV_COMPONENTS = 2;

    public static final float NORMAL_LENGTH_EPSILON = 1e-20f;

    /** Positions of the exported vertices, {@link #vertexCount()} triples. */
    public float[] positions = new float[0];

    /** Normals of the exported vertices, the same length and order as {@link #positions}. */
    public float[] normals = new float[0];

    /** Vertex index per triangle corner, {@link #triangleCount()} triples. */
    public int[] triangleIndices = new int[0];

    /**
     * Whether vertices whose corners disagree on a UV are split so each vertex holds one UV, as
     * glTF needs; {@code false} keeps the mesh's own vertices and leaves the UVs on the corners.
     */
    public boolean splitSeams = true;

    /**
     * {@code (u, v)} per exported vertex in the bottom-left origin the pipeline uses, or
     * {@code null} when the bundle carried no UV field or {@link #splitSeams} is off.
     */
    public float[] vertexUv;

    /** {@code u} per triangle corner, a non-finite one written as zero; {@code null} without UVs. */
    public double[] cornerU;

    /** {@code v} per triangle corner, alongside {@link #cornerU}. */
    public double[] cornerV;

    /**
     * Per exported vertex, which of the mesh's distinct vertices at that exact position it copies
     * ({@code 0, 1, ...}), so a position weld on import keeps them apart; {@code null} when no two
     * vertices share a position. A repair's pinch split is what produces such twins.
     */
    public float[] weldKey;

    /**
     * Corners whose UV was not finite and was written as zero, which a filled hole produces; the
     * {@code /mesh/export} route reports it as {@code zeroed_uv_corners}.
     */
    public int nonFiniteUvCount;

    private final GeometryBundle bundle;

    private int[] denseOfVertexId = new int[0];

    /**
     * Prepare an export of one bundle; {@link #build} does the work.
     *
     * @param bundle geometry to flatten, whose {@link CornerUvField#SLOT} slot is written when it
     *     holds a {@link UvField}
     */
    public MeshExport(GeometryBundle bundle) {
        this.bundle = bundle;
    }

    /**
     * Vertices the export writes, after the seam split.
     *
     * @return one third of the position array's length
     */
    public int vertexCount() {
        return positions.length / FLOATS_PER_VERTEX;
    }

    /**
     * Triangles the export writes.
     *
     * @return one third of the index array's length
     */
    public int triangleCount() {
        return triangleIndices.length / CORNERS_PER_TRIANGLE;
    }

    /**
     * Fill the flat arrays: dense vertices, fanned triangles, weld keys, then with
     * {@link #splitSeams} one vertex per distinct corner UV. Leaves them empty when the bundle holds
     * no faces, and computes the normals when the mesh carries none, since a zero normal is not
     * something a mesh file may hold.
     *
     * @throws IllegalStateException when a face with more than three corners meets a per-corner UV
     *     field, which no UV field in the pipeline describes
     */
    public void build() {
        MeshTopology mesh = bundle == null ? null : bundle.mesh();
        if (mesh == null || mesh.vertexCount() == 0 || mesh.faceCount() == 0) {
            return;
        }
        UvField uv = bundle.slots().get(CornerUvField.SLOT) instanceof UvField field ? field : null;

        // Copy the mesh's vertices into dense arrays, recording where each vertex id landed so the
        // faces can be re-indexed.
        int count = mesh.vertexCount();
        int maxVertexId = 0;
        for (int index = 0; index < count; index++) {
            maxVertexId = Math.max(maxVertexId, mesh.vertexIdAt(index));
        }
        denseOfVertexId = new int[maxVertexId + 1];
        positions = new float[count * FLOATS_PER_VERTEX];
        normals = new float[count * FLOATS_PER_VERTEX];
        Vector3f scratch = new Vector3f();
        for (int index = 0; index < count; index++) {
            int vertexId = mesh.vertexIdAt(index);
            denseOfVertexId[vertexId] = index;
            int offset = index * FLOATS_PER_VERTEX;
            mesh.vertexPosition(vertexId, scratch);
            positions[offset] = scratch.x;
            positions[offset + 1] = scratch.y;
            positions[offset + 2] = scratch.z;
            mesh.vertexNormal(vertexId, scratch);
            normals[offset] = scratch.x;
            normals[offset + 1] = scratch.y;
            normals[offset + 2] = scratch.z;
        }

        // Fan every face into triangles, collecting the corner UVs alongside so a seam can be
        // split back out. A non-finite UV becomes zero and is counted: the hole fills a repair
        // mints carry no texture coordinates, and glTF accessors may not hold NaN.
        int faceCount = mesh.faceCount();
        int corners = 0;
        for (int index = 0; index < faceCount; index++) {
            int arity = mesh.faceVertexCount(mesh.faceIdAt(index));
            if (arity > CORNERS_PER_TRIANGLE && uv != null) {
                throw new IllegalStateException("per-corner UVs are triangle-only, but face "
                        + mesh.faceIdAt(index) + " has " + arity + " corners");
            }
            if (arity >= CORNERS_PER_TRIANGLE) {
                corners += (arity - 2) * CORNERS_PER_TRIANGLE;
            }
        }
        triangleIndices = new int[corners];
        if (uv != null) {
            cornerU = new double[corners];
            cornerV = new double[corners];
        }
        int cursor = 0;
        int[] faceCorners = new int[0];
        for (int index = 0; index < faceCount; index++) {
            int faceId = mesh.faceIdAt(index);
            int arity = mesh.faceVertexCount(faceId);
            if (faceCorners.length < CORNERS_PER_TRIANGLE * arity) {
                faceCorners = new int[CORNERS_PER_TRIANGLE * arity];
            }
            int triangles = mesh.faceTriangleCorners(faceId, TriangulationRule.FAN, faceCorners);
            for (int slot = 0; slot < CORNERS_PER_TRIANGLE * triangles; slot++) {
                cursor = writeCorner(mesh, uv, faceId, faceCorners[slot], cursor);
            }
        }

        boolean anyNormal = false;
        for (int slot = 0; slot < normals.length && !anyNormal; slot++) {
            anyNormal = normals[slot] != 0;
        }
        if (!anyNormal && triangleCount() > 0) {
            // Each vertex takes the normalized sum of its triangles' unit normals.
            Vector3f corner0 = new Vector3f();
            Vector3f edgeA = new Vector3f();
            Vector3f edgeB = new Vector3f();
            for (int triangle = 0; triangle < triangleCount(); triangle++) {
                int first = triangle * CORNERS_PER_TRIANGLE;
                int offset0 = triangleIndices[first] * FLOATS_PER_VERTEX;
                int offsetA = triangleIndices[first + 1] * FLOATS_PER_VERTEX;
                int offsetB = triangleIndices[first + 2] * FLOATS_PER_VERTEX;
                corner0.set(positions[offset0], positions[offset0 + 1], positions[offset0 + 2]);
                edgeA.set(positions[offsetA], positions[offsetA + 1], positions[offsetA + 2]).sub(corner0);
                edgeB.set(positions[offsetB], positions[offsetB + 1], positions[offsetB + 2]).sub(corner0);
                edgeA.cross(edgeB);
                float length = edgeA.length();
                if (length > NORMAL_LENGTH_EPSILON) {
                    edgeA.mul(1.0f / length);
                } else {
                    edgeA.set(0f, 1f, 0f);
                }
                for (int corner = first; corner < first + CORNERS_PER_TRIANGLE; corner++) {
                    int offset = triangleIndices[corner] * FLOATS_PER_VERTEX;
                    normals[offset] += edgeA.x;
                    normals[offset + 1] += edgeA.y;
                    normals[offset + 2] += edgeA.z;
                }
            }
            for (int offset = 0; offset < normals.length; offset += FLOATS_PER_VERTEX) {
                float inverse = 1.0f / Math.max(NORMAL_LENGTH_EPSILON, (float) Math.sqrt(
                        normals[offset] * normals[offset] + normals[offset + 1] * normals[offset + 1]
                                + normals[offset + 2] * normals[offset + 2]));
                normals[offset] *= inverse;
                normals[offset + 1] *= inverse;
                normals[offset + 2] *= inverse;
            }
        }

        // Number the vertices that share a bitwise-identical position 0, 1, ... in vertex order,
        // leaving weldKey null when every position is unique.
        Integer[] order = new Integer[count];
        for (int vertex = 0; vertex < count; vertex++) {
            order[vertex] = vertex;
        }
        Arrays.sort(order, (left, right) -> {
            for (int axis = 0; axis < FLOATS_PER_VERTEX; axis++) {
                int compared = Integer.compare(
                        Float.floatToRawIntBits(positions[left * FLOATS_PER_VERTEX + axis]),
                        Float.floatToRawIntBits(positions[right * FLOATS_PER_VERTEX + axis]));
                if (compared != 0) {
                    return compared;
                }
            }
            return Integer.compare(left, right);
        });
        float[] keys = new float[count];
        boolean anyTwin = false;
        for (int rank = 1; rank < count; rank++) {
            int previous = order[rank - 1];
            int vertex = order[rank];
            boolean same = true;
            for (int axis = 0; axis < FLOATS_PER_VERTEX && same; axis++) {
                same = Float.floatToRawIntBits(positions[previous * FLOATS_PER_VERTEX + axis])
                        == Float.floatToRawIntBits(positions[vertex * FLOATS_PER_VERTEX + axis]);
            }
            if (same) {
                keys[vertex] = keys[previous] + 1;
                anyTwin = true;
            }
        }
        weldKey = anyTwin ? keys : null;

        if (!splitSeams || cornerU == null || triangleCount() == 0) {
            return;
        }
        // Give every corner UV a vertex of its own, duplicating the positions and normals the
        // seam shares, so a per-vertex file format can hold the field.
        float[] splitUv = new float[CornerUvSplit.maxSplitUvLength(triangleCount())];
        int[] splitCorners = new int[triangleIndices.length];
        int[] weldedOfSplit = new int[triangleIndices.length];
        // Mesh face order, so split corner i is still dense corner i for the weld keys below.
        int splitCount = CornerUvSplit.split(triangleIndices, new CornerUvField(cornerU, cornerV), null,
                splitUv, splitCorners, weldedOfSplit);
        int[] denseCorners = triangleIndices;
        float[] splitPositions = new float[splitCount * FLOATS_PER_VERTEX];
        float[] splitNormals = new float[splitCount * FLOATS_PER_VERTEX];
        for (int copy = 0; copy < splitCount; copy++) {
            System.arraycopy(positions, weldedOfSplit[copy] * FLOATS_PER_VERTEX, splitPositions,
                    copy * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
            System.arraycopy(normals, weldedOfSplit[copy] * FLOATS_PER_VERTEX, splitNormals,
                    copy * FLOATS_PER_VERTEX, FLOATS_PER_VERTEX);
        }
        positions = splitPositions;
        normals = splitNormals;
        triangleIndices = splitCorners;
        vertexUv = Arrays.copyOf(splitUv, splitCount * UV_COMPONENTS);
        if (weldKey != null) {
            float[] splitKey = new float[splitCount];
            for (int corner = 0; corner < triangleIndices.length; corner++) {
                splitKey[triangleIndices[corner]] = weldKey[denseCorners[corner]];
            }
            weldKey = splitKey;
        }
    }

    /**
     * Write one face corner into the flat triangle arrays.
     *
     * @param mesh geometry being exported
     * @param uv per-corner UVs over {@code mesh}, or {@code null}
     * @param faceId face the corner belongs to
     * @param corner corner index within that face
     * @param cursor slot to write
     * @return the next free slot
     */
    private int writeCorner(MeshTopology mesh, UvField uv, int faceId, int corner, int cursor) {
        triangleIndices[cursor] = denseOfVertexId[mesh.faceVertexAt(faceId, corner)];
        if (uv != null) {
            double u = uv.u(faceId, corner);
            double v = uv.v(faceId, corner);
            if (!Double.isFinite(u) || !Double.isFinite(v)) {
                u = 0;
                v = 0;
                nonFiniteUvCount++;
            }
            cornerU[cursor] = u;
            cornerV[cursor] = v;
        }
        return cursor + 1;
    }
}
