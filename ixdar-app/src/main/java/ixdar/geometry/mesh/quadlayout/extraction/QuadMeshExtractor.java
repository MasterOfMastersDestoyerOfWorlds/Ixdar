package ixdar.geometry.mesh.quadlayout.extraction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;

/**
 * QEx (Ebke 2013) quad mesh extractor — orchestrator for Stages 1-4.
 *
 * <p>Takes a triangle mesh, a per-corner UV map (the output of the
 * seamless integer-grid parametrization stage), and the matching state
 * from the combed cross field, and produces an explicit quad mesh by
 * locating every integer (u, v) preimage on the surface and connecting
 * them along iso-lines.
 *
 * <p>Phase B/C: handles quads spanning multiple input triangles via
 * cross-edge tracing using the per-half-edge {@link TransitionMatrix}.
 * VERT and EDGE QVerts emit proper per-face wedge ports so quads anchored
 * at mesh vertices and edges connect correctly.
 *
 * <p>Tests / callers without a meaningful CombedField (uniform UV across
 * faces) can pass {@code null}; the extractor treats matching as 0
 * everywhere, which is correct when the UV map is globally consistent
 * (e.g. a flat planar test mesh).
 */
public final class QuadMeshExtractor {
    public static final int NUM_4 = 4;
    public static final int NUM_3 = 3;

    private QuadMeshExtractor() {}

    /**
     * Convenience overload for matching-free inputs (planar tests). Delegates
     * to the four-arg form with a {@code null} {@link CombedField}.
     *
     * @param mesh underlying triangle mesh
     * @param uCorner per-corner u, length {@code 3 * F}
     * @param vCorner per-corner v, length {@code 3 * F}
     * @return extraction result (intermediate stage data + final quad mesh)
     */
    public static Result extract(ArrayMesh mesh, float[] uCorner, float[] vCorner) {
        return extract(mesh, uCorner, vCorner, null);
    }

    /**
     * Extract a quad mesh from {@code mesh} + per-corner UVs.
     *
     * @param mesh underlying triangle mesh
     * @param uCorner per-corner u, length {@code 3 * F}
     * @param vCorner per-corner v, length {@code 3 * F}
     * @param combed combed cross field providing per-edge matching; may be
     *               {@code null} for tests where matching is identically 0
     * @return extraction result with all four QEx stages' outputs and the
     *         compactified quad mesh
     */
    public static Result extract(ArrayMesh mesh, float[] uCorner, float[] vCorner,
                                  CombedField combed) {
        // Stage 0: precompute TRS per half-edge.
        TransitionMatrix trs = TransitionMatrix.compute(mesh, uCorner, vCorner, combed);

        // Stage 1.
        QuadVertexGenerator.Result qVerts =
                QuadVertexGenerator.generate(mesh, uCorner, vCorner);

        // Stage 2.
        QuadPortGenerator.Result portResult =
                QuadPortGenerator.generate(mesh, qVerts, uCorner, vCorner);

        // Stage 3.
        List<QEdge> edges = QuadEdgeGenerator.generate(
                mesh, portResult.ports(), uCorner, vCorner, trs);

        // Stage 4.
        List<QFace> faces = QuadFaceGenerator.generate(portResult.ports(), edges);

        // Compactify QVerts → unique quad-mesh vertex slots.
        HashMap<Integer, Integer> qVertToOutput = new HashMap<>();
        ArrayList<Float> outPos = new ArrayList<>();
        ArrayList<Integer> outFaces = new ArrayList<>();
        List<QVert> allQVerts = collectAllQVerts(qVerts);
        for (QFace f : faces) {
            int[] outIdxs = new int[NUM_4];
            for (int i = 0; i < NUM_4; i++) {
                int qvId = f.cornerQVerts()[i];
                Integer existing = qVertToOutput.get(qvId);
                if (existing == null) {
                    QVert qv = allQVerts.get(qvId);
                    int newId = outPos.size() / NUM_3;
                    outPos.add(qv.position().x);
                    outPos.add(qv.position().y);
                    outPos.add(qv.position().z);
                    qVertToOutput.put(qvId, newId);
                    existing = newId;
                }
                outIdxs[i] = existing;
            }
            for (int i : outIdxs) outFaces.add(i);
        }

        float[] posArr = new float[outPos.size()];
        for (int i = 0; i < posArr.length; i++) posArr[i] = outPos.get(i);
        int[] faceArr = outFaces.stream().mapToInt(Integer::intValue).toArray();

        ArrayMesh quadMesh = posArr.length > 0 && faceArr.length > 0
                ? new ArrayMesh(posArr, null, faceArr, NUM_4)
                : null;

        return new Result(qVerts, portResult.ports(), edges, faces,
                posArr, faceArr, quadMesh);
    }

    private static List<QVert> collectAllQVerts(QuadVertexGenerator.Result r) {
        ArrayList<QVert> all = new ArrayList<>(r.total());
        all.addAll(r.vertQVerts());
        all.addAll(r.edgeQVerts());
        all.addAll(r.faceQVerts());
        all.sort((a, b) -> Integer.compare(a.id(), b.id()));
        return all;
    }

    /** Result of an extraction pass: full intermediate state + final quad mesh. */
    public record Result(QuadVertexGenerator.Result qVerts,
                         List<QPort> ports,
                         List<QEdge> edges,
                         List<QFace> faces,
                         /** vertices[i*3..i*3+2] = position of quad vertex i. */
                         float[] quadVertexPositions,
                         /** faces[i*4..i*4+3] = the 4 quad-vertex ids of quad i (CCW). */
                         int[] quadFaceIndices,
                         /** Optional ArrayMesh ready for rendering. */
                         ArrayMesh quadMesh) {
    }
}
