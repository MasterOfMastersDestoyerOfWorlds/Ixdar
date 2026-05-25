package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.HashMap;
import java.util.List;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Cube-corner test geometry for QEx Algorithm 4.
 *
 * <p>
 * A full unit cube (12 triangles) builds a cross field with eight corner
 * singularities, but {@link SeamlessParameterization#build()} fails integer
 * rounding on that mesh. This fixture uses one topological cube corner — three
 * triangles meeting at a valence-3 singularity ({@code index4 = +1}, three
 * ports).
 */
public final class CubeCornerFixtures {

    /** Center vertex of the three-triangle corner fan. */
    public static final int CORNER_VERTEX = 0;
    /** Expected singularity index4 (valence 3). */
    public static final int CORNER_INDEX4 = 1;
    /** Expected QEx port count at the corner. */
    public static final int PORTS_PER_CORNER = 3;

    private CubeCornerFixtures() {
    }

    /**
     * Builds a minimal seamless parametrization for one cube corner.
     *
     * @return seamless state with one corner singularity and UV corners
     */
    public static SeamlessParameterization buildSeamless() {
        HalfEdgeMesh mesh = cubeCornerFan();
        CrossField crossField = new CrossField(mesh);
        crossField.faceIdToActive = new HashMap<>(mesh.faceCount() * 2);
        for (int faceIndex = 0; faceIndex < mesh.faceCount(); faceIndex++) {
            crossField.faceIdToActive.put(mesh.faceIdAt(faceIndex), faceIndex);
        }
        crossField.edgeIdToActive = new HashMap<>(mesh.edgeCount() * 2);
        for (int edgeIndex = 0; edgeIndex < mesh.edgeCount(); edgeIndex++) {
            crossField.edgeIdToActive.put(mesh.edgeIdAt(edgeIndex), edgeIndex);
        }
        crossField.singularities = List.of(new Singularity(CORNER_VERTEX, CORNER_INDEX4));

        SeamlessParameterization seamless = new SeamlessParameterization(crossField);
        seamless.uCorner = new float[mesh.faceCount() * SeamlessParameterization.CORNERS_PER_FACE];
        seamless.vCorner = new float[mesh.faceCount() * SeamlessParameterization.CORNERS_PER_FACE];
        assignHandCraftedUv(seamless);
        return seamless;
    }

    /**
     * Three triangles sharing vertex {@link #CORNER_VERTEX}, matching one cube
     * corner.
     *
     * @return triangle mesh for the corner fan
     */
    static HalfEdgeMesh cubeCornerFan() {
        float[] positions = {
                0f, 0f, 0f,
                1f, 0f, 0f,
                1f, 1f, 0f,
                0f, 1f, 0f,
        };
        int[] faceIndices = {
                0, 1, 2,
                0, 2, 3,
                0, 3, 1,
        };
        return HalfEdgeMeshEngine.buildFromIndexedMesh(positions, faceIndices);
    }

    private static void assignHandCraftedUv(SeamlessParameterization seamless) {
        // valence-3 singularity (index4=+1) has chart cone 3π/2 (270°), three
        // 90° wedges from 0° → 90° → 180° → 270° around the centre vertex.
        // The three outgoing axis-aligned directions are +U, +V, -U.
        assignFaceUv(seamless, 0, 0f, 0f, 1f, 0f, 0f, 1f);
        assignFaceUv(seamless, 1, 0f, 0f, 0f, 1f, -1f, 0f);
        assignFaceUv(seamless, 2, 0f, 0f, -1f, 0f, 0f, -1f);
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
