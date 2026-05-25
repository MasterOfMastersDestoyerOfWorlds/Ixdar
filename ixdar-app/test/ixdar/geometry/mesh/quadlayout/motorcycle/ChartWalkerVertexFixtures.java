package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.HashMap;
import java.util.List;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.CutGraph;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Fan fixtures used by {@link ChartWalkerVertexTest}.
 *
 * <p>
 * Four triangles meet at vertex 0 at the origin; the four outer vertices sit
 * at the four axis-aligned unit positions. Per-face UVs are assigned so that
 * vertex 0's chart angle sums to exactly 2π (regular vertex). No cuts.
 * Optionally, vertex 0 can be marked as a singularity to verify termination
 * behaviour without changing the geometry.
 */
public final class ChartWalkerVertexFixtures {

    /** Active-face index of the +U/+V wedge triangle. */
    public static final int F_Q1 = 0;
    /** Active-face index of the −U/+V wedge triangle. */
    public static final int F_Q2 = 1;
    /** Active-face index of the −U/−V wedge triangle. */
    public static final int F_Q3 = 2;
    /** Active-face index of the +U/−V wedge triangle. */
    public static final int F_Q4 = 3;
    /** Mesh vertex id of the fan center. */
    public static final int CENTER_VERTEX = 0;

    private ChartWalkerVertexFixtures() {
    }

    /**
     * Builds a four-triangle regular-vertex fan with hand-crafted UVs.
     *
     * @param markCenterAsSingularity when true, register vertex 0 as a valence-3
     *                                singularity so {@link ChartWalker#crossVertex}
     *                                terminates there
     * @return seamless parametrization ready for chart-walker probing
     */
    public static SeamlessParameterization buildRegularVertexFan(boolean markCenterAsSingularity) {
        HalfEdgeMesh mesh = fanMesh();
        CrossField crossField = new CrossField(mesh);
        crossField.faceIdToActive = new HashMap<>(mesh.faceCount() * 2);
        for (int faceIndex = 0; faceIndex < mesh.faceCount(); faceIndex++) {
            crossField.faceIdToActive.put(mesh.faceIdAt(faceIndex), faceIndex);
        }
        crossField.edgeIdToActive = new HashMap<>(mesh.edgeCount() * 2);
        for (int edgeIndex = 0; edgeIndex < mesh.edgeCount(); edgeIndex++) {
            crossField.edgeIdToActive.put(mesh.edgeIdAt(edgeIndex), edgeIndex);
        }
        crossField.singularities = markCenterAsSingularity
                ? List.of(new Singularity(CENTER_VERTEX, 1))
                : List.of();

        SeamlessParameterization seamless = new SeamlessParameterization(crossField);
        seamless.uCorner = new float[mesh.faceCount() * SeamlessParameterization.CORNERS_PER_FACE];
        seamless.vCorner = new float[mesh.faceCount() * SeamlessParameterization.CORNERS_PER_FACE];
        assignFanUv(seamless);

        CutGraph cutGraph = new CutGraph(mesh, crossField, seamless);
        cutGraph.isCutEdge = new boolean[mesh.edgeCount()];
        cutGraph.cutRotation = new int[mesh.edgeCount()];
        seamless.cutGraph = cutGraph;
        seamless.cutTranslationS = new float[mesh.edgeCount()];
        seamless.cutTranslationT = new float[mesh.edgeCount()];
        return seamless;
    }

    private static HalfEdgeMesh fanMesh() {
        float[] positions = {
                0f, 0f, 0f,
                1f, 0f, 0f,
                0f, 1f, 0f,
                -1f, 0f, 0f,
                0f, -1f, 0f,
        };
        int[] faceIndices = {
                0, 1, 2,
                0, 2, 3,
                0, 3, 4,
                0, 4, 1,
        };
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
    }

    private static void assignFanUv(SeamlessParameterization seamless) {
        assignFaceUv(seamless, F_Q1, 0f, 0f, 1f, 0f, 0f, 1f);
        assignFaceUv(seamless, F_Q2, 0f, 0f, 0f, 1f, -1f, 0f);
        assignFaceUv(seamless, F_Q3, 0f, 0f, -1f, 0f, 0f, -1f);
        assignFaceUv(seamless, F_Q4, 0f, 0f, 0f, -1f, 1f, 0f);
    }

    private static void assignFaceUv(SeamlessParameterization seamless, int activeFace,
            float u0, float v0, float u1, float v1, float u2, float v2) {
        int base = activeFace * SeamlessParameterization.CORNERS_PER_FACE;
        seamless.uCorner[base] = u0;
        seamless.vCorner[base] = v0;
        seamless.uCorner[base + 1] = u1;
        seamless.vCorner[base + 1] = v1;
        seamless.uCorner[base + 2] = u2;
        seamless.vCorner[base + 2] = v2;
    }
}
