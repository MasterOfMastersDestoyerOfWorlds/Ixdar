package ixdar.geometry.mesh.quadlayout.vectorfield.alignment;

import org.joml.Vector3f;

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
     */
    public static Result compute(BaseField frames, PrincipalCurvatureField pdf,
                                  int[] regionId) {
        int F = frames.faceCount();
        double[] thetaConstraint = new double[F];
        boolean[] constrained = new boolean[F];
        Vector3f aMin = new Vector3f();
        Vector3f frameU = new Vector3f();
        Vector3f frameV = new Vector3f();
        for (int f = 0; f < F; f++) {
            if (regionId[f] < 0) continue;
            pdf.aMin(f, aMin);
            frames.frameU(f, frameU);
            frames.frameV(f, frameV);
            // CIE*16 §4.1 — express a_min in face local frame.
            //   uComp = a_min · frame_u,  vComp = a_min · frame_v.
            //   θ = atan2(vComp, uComp).
            // 4-RoSy (BZK09 §4): θ + k·π/2 equivalent → line-field sign
            //   ambiguity is absorbed by the period-jump integers, no need to
            //   disambiguate here.
            double uComp = aMin.x * frameU.x + aMin.y * frameU.y + aMin.z * frameU.z;
            double vComp = aMin.x * frameV.x + aMin.y * frameV.y + aMin.z * frameV.z;
            thetaConstraint[f] = Math.atan2(vComp, uComp);
            constrained[f] = true;
        }
        return new Result(thetaConstraint, constrained);
    }
}
