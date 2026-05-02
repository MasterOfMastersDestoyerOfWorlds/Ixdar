package ixdar.geometry.mesh.quadlayout.vectorfield.alignment;

import java.util.ArrayDeque;
import java.util.Deque;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.BaseField;

/**
 * Per-face hard θ constraints for the cross-field optimization, derived from
 * smooth-region principal directions.
 *
 * <h2>Citations</h2>
 * <ul>
 *   <li><b>CIE*16 §4.1</b> "Cross Field Constraints" — "directionally
 *       hard-constrain the optimization of global direction (cross) fields ...
 *       a value of ∞ in the filtered smooth regions". This is a {0, ∞}
 *       weight field.</li>
 *   <li><b>CIE*16 §4.2 ¶3</b> — convention that the cross-field direction
 *       {@code v} aligns with the line field {@code a_min} of minimum
 *       principal curvature direction in filtered smooth regions.</li>
 *   <li><b>BZK09 §4</b> + <b>Campen 2014 thesis §4.2 eq.(4.6)</b> — 4-RoSy
 *       invariance: {@code θ} and {@code θ + k·π/2} are equivalent. The
 *       sign-ambiguity of the line field {@code a_min} is absorbed by the
 *       period-jump integers, so we just emit {@code atan2(v_comp, u_comp)}
 *       without disambiguating the sign.</li>
 * </ul>
 *
 * <p>Master citation index: {@code alignment/PAPERS.md}.
 */
public final class DirectionalConstraints {

    public static final class Result {
        /** Per-face θ constraint angle (radians, in face's local frame). */
        public final double[] thetaConstraint;
        /** {@code constrained[f]=true} ⇔ face f is in a kept smooth region. */
        public final boolean[] constrained;

        public Result(double[] thetaConstraint, boolean[] constrained) {
            this.thetaConstraint = thetaConstraint;
            this.constrained = constrained;
        }
    }

    private DirectionalConstraints() {}

    /**
     * Project per-face {@code a_min} into the local frame
     * {@code (frame_u, frame_v)} carried by {@code frames}, producing a θ
     * constraint per face in {@code regionId[f] >= 0}.
     *
     * <p>{@code mesh} is needed to walk dual-graph adjacency for PATCH-115
     * sign propagation; pass {@code null} to fall back to the old per-face
     * {@code atan2} that ignores line-field sign and emits spurious
     * {@code m_e = ±2} on every region-internal sign flip.
     */
    public static Result compute(ArrayMesh mesh,
                                  BaseField frames, PrincipalCurvatureField pdf,
                                  int[] regionId) {
        int F = frames.faceCount();
        double[] thetaConstraint = new double[F];
        boolean[] constrained = new boolean[F];

        // PATCH-115: line-field sign propagation per region. CIE*16 §3.2 ¶2
        //   represents a_min as a line field (sign-ambiguous). Earlier code
        //   pinned θ = atan2(a_min · frame) per-face directly — adjacent
        //   constrained faces with line-field-flipped a_min then differ by
        //   π in their pinned θ, which the BZK09 system absorbs into
        //   m_e = ±2, manifesting as ±2 spurious singularities at every
        //   intra-region edge with a flip. On rocker-arm-20k this inflated
        //   the singularity count to 735 (paper / metriko: 32-36).
        //
        //   Fix: BFS each region in the dual graph; at each hop choose
        //   sign[nbr] ∈ {±1} so that sign[nbr] · a_min(nbr) aligns with
        //   sign[parent] · a_min(parent) after Levi-Civita transport. Use
        //   the canonical per-face sign in the atan2 below. Region-global
        //   sign is still arbitrary, but 4-RoSy (BZK09 §4) absorbs it.
        float[] sign = mesh != null ? buildPerFaceSign(mesh, pdf, regionId) : null;

        Vector3f aMin = new Vector3f();
        Vector3f frameU = new Vector3f();
        Vector3f frameV = new Vector3f();
        for (int f = 0; f < F; f++) {
            if (regionId[f] < 0) continue;
            pdf.aMin(f, aMin);
            if (sign != null) aMin.mul(sign[f]);
            frames.frameU(f, frameU);
            frames.frameV(f, frameV);
            // CIE*16 §4.1 — express a_min in face local frame.
            //   uComp = a_min · frame_u,  vComp = a_min · frame_v.
            //   θ = atan2(vComp, uComp).
            double uComp = aMin.x * frameU.x + aMin.y * frameU.y + aMin.z * frameU.z;
            double vComp = aMin.x * frameV.x + aMin.y * frameV.y + aMin.z * frameV.z;
            thetaConstraint[f] = Math.atan2(vComp, uComp);
            constrained[f] = true;
        }
        return new Result(thetaConstraint, constrained);
    }

    /**
     * BFS each region in the dual graph; assign per-face {@code sign[f] ∈ {±1}}
     * so that {@code sign[f] · a_min(f)}, after Levi-Civita transport across
     * the entry edge, agrees in sign with the parent's signed {@code a_min}.
     * Faces outside any kept region get {@code sign[f] = +1} (unused).
     */
    private static float[] buildPerFaceSign(ArrayMesh mesh, PrincipalCurvatureField pdf,
                                            int[] regionId) {
        int F = mesh.faceCount();
        float[] sign = new float[F];
        for (int f = 0; f < F; f++) sign[f] = 1f;
        boolean[] visited = new boolean[F];

        Vector3f parentA = new Vector3f();
        Vector3f parentN = new Vector3f();
        Vector3f childA = new Vector3f();
        Vector3f childN = new Vector3f();
        Vector3f parentATransp = new Vector3f();
        Vector3f axisScratch = new Vector3f();
        Vector3f tmpScratch = new Vector3f();

        Deque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < F; seed++) {
            if (visited[seed] || regionId[seed] < 0) continue;
            int rid = regionId[seed];
            visited[seed] = true;
            queue.add(seed);
            while (!queue.isEmpty()) {
                int parent = queue.poll();
                pdf.aMin(parent, parentA);
                if (sign[parent] < 0) parentA.mul(-1f);
                pdf.normal(parent, parentN);
                int n = mesh.faceHalfEdgeCount(parent);
                for (int c = 0; c < n; c++) {
                    int he = mesh.faceHalfEdgeAt(parent, c);
                    int twin = mesh.halfEdgeTwin(he);
                    int child = mesh.halfEdgeFace(twin);
                    if (child < 0 || regionId[child] != rid || visited[child]) continue;
                    pdf.normal(child, childN);
                    pdf.aMin(child, childA);
                    // Transport parent's signed a_min into the child's tangent
                    //   plane. If the dot with the child's raw a_min is
                    //   negative, the child should be sign-flipped.
                    double dotN = Math.max(-1.0, Math.min(1.0, (double) parentN.dot(childN)));
                    GeodesicCurvature.transportLineField(parentA, childN, parentN, dotN,
                            axisScratch, parentATransp, tmpScratch);
                    double align = parentATransp.x * childA.x
                                 + parentATransp.y * childA.y
                                 + parentATransp.z * childA.z;
                    sign[child] = align >= 0 ? 1f : -1f;
                    visited[child] = true;
                    queue.add(child);
                }
            }
        }
        return sign;
    }
}
