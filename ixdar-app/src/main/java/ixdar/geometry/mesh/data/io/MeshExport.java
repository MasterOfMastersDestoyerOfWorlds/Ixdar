package ixdar.geometry.mesh.data.io;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.CornerUvField;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.bundle.CornerUvSplit;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;
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

    /** Positions of the exported vertices, {@link #vertexCount()} of them. */
    public PackedPoints positions = new PackedPoints(0);

    /** Normals of the exported vertices, the same count and order as {@link #positions}. */
    public PackedPoints normals = new PackedPoints(0);

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
     * @return the number of positions
     */
    public int vertexCount() {
        return positions.size();
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

        int count = mesh.vertexCount();
        denseOfVertexId = new int[mesh.vertexIdBound()];
        positions = mesh.packedPositions(denseOfVertexId);
        normals = new PackedPoints(count);
        Vector3f scratch = new Vector3f();
        for (int index = 0; index < count; index++) {
            normals.set(index, mesh.vertexNormal(mesh.vertexIdAt(index), scratch));
        }

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
        for (int vertex = 0; vertex < count && !anyNormal; vertex++) {
            anyNormal = normals.get(vertex, 0) != 0 || normals.get(vertex, 1) != 0 || normals.get(vertex, 2) != 0;
        }
        if (!anyNormal && triangleCount() > 0) {
            PackedPoints triangleNormals = TriangleGeometry.normals(positions, triangleIndices, new Vector3f(0f, 1f, 0f));
            for (int triangle = 0; triangle < triangleCount(); triangle++) {
                Vector3f normal = triangleNormals.at(triangle);
                for (int corner = 0; corner < CORNERS_PER_TRIANGLE; corner++) {
                    normals.add(triangleIndices[triangle * CORNERS_PER_TRIANGLE + corner], normal);
                }
            }
            normals.normalizeEach();
        }

        Integer[] order = new Integer[count];
        for (int vertex = 0; vertex < count; vertex++) {
            order[vertex] = vertex;
        }
        Arrays.sort(order, (left, right) -> {
            for (int axis = 0; axis < FLOATS_PER_VERTEX; axis++) {
                int compared = Integer.compare(
                        Float.floatToRawIntBits(positions.get(left, axis)),
                        Float.floatToRawIntBits(positions.get(right, axis)));
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
                same = Float.floatToRawIntBits(positions.get(previous, axis))
                        == Float.floatToRawIntBits(positions.get(vertex, axis));
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
        float[] splitUv = new float[CornerUvSplit.maxSplitUvLength(triangleCount())];
        int[] splitCorners = new int[triangleIndices.length];
        int[] weldedOfSplit = new int[triangleIndices.length];
        int splitCount = CornerUvSplit.split(triangleIndices, new CornerUvField(cornerU, cornerV), null,
                splitUv, splitCorners, weldedOfSplit);
        int[] denseCorners = triangleIndices;
        int[] welded = Arrays.copyOf(weldedOfSplit, splitCount);
        positions = positions.gather(welded);
        normals = normals.gather(welded);
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
