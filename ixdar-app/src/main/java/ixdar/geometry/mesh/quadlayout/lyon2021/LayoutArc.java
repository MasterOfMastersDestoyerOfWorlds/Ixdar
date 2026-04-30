package ixdar.geometry.mesh.quadlayout.lyon2021;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;

/**
 * Lyon 2021 §6 ¶1 — a single arc of the conforming quad layout.
 *
 * <p>A {@code LayoutArc} is one of three variants:
 * <ul>
 *   <li>{@link Variant#INHERITED} — wraps an existing {@link TArc} fully. The
 *       layout arc IS that T-mesh arc (the common case before any T-junction
 *       extension fires). {@code underlyingTArcId} is set; {@code interiorPolyline}
 *       is empty.</li>
 *   <li>{@link Variant#DERIVED} — wraps a sub-range {@code [t0, t1] ⊂ [0, 1]}
 *       of an existing {@link TArc}. Created by PATCH-79 when an arc-splitting
 *       T-junction extension cuts a T-arc.</li>
 *   <li>{@link Variant#INTERIOR} — a brand-new arc connecting a T-junction node
 *       to its match on the opposite side of a patch. Has no underlying T-arc;
 *       the geometric path is stored as {@code interiorPolyline} (a list of
 *       per-face {@link SplitEdge}s in the same format {@link SplitArcTracer}
 *       emits).</li>
 * </ul>
 *
 * <p>{@code direction} is the cardinal axis of the seamless parametrization
 * the arc rides ({@code 0..3 = +u, +v, -u, -v}). For INHERITED arcs it equals
 * the underlying TArc's direction; for INTERIOR it equals the cardinal axis
 * of the side that was being bridged when the arc was created.
 */
public record LayoutArc(int id,
                        Variant variant,
                        int startNodeId,
                        int endNodeId,
                        int direction,
                        int underlyingTArcId,
                        float underlyingT0,
                        float underlyingT1,
                        List<SplitEdge> interiorPolyline,
                        float parametricLength) {

    public enum Variant { INHERITED, DERIVED, INTERIOR }

    /** Factory: a layout arc that fully wraps a T-mesh arc. */
    public static LayoutArc inherited(int id, TArc tArc) {
        return new LayoutArc(id, Variant.INHERITED,
                tArc.startNode(), tArc.endNode(),
                tArc.direction(),
                tArc.id(), 0f, 1f,
                Collections.emptyList(),
                tArc.parametricLength());
    }

    /** Factory: a layout arc covering a sub-range of a T-mesh arc (PATCH-79). */
    public static LayoutArc derived(int id, TArc tArc,
                                     int startNodeId, int endNodeId,
                                     float t0, float t1) {
        float len = Math.abs(t1 - t0) * tArc.parametricLength();
        return new LayoutArc(id, Variant.DERIVED,
                startNodeId, endNodeId,
                tArc.direction(),
                tArc.id(), t0, t1,
                Collections.emptyList(),
                len);
    }

    /** Factory: a brand-new arc traversing patch interior, traced by
     *  {@link SplitArcTracer}. {@code parametricLength} is computed by
     *  summing the polyline segment lengths in the seamless domain. */
    public static LayoutArc interior(int id,
                                      int startNodeId, int endNodeId,
                                      int direction,
                                      List<SplitEdge> polyline) {
        double len = 0;
        for (SplitEdge e : polyline) {
            double du = e.u2() - e.u1();
            double dv = e.v2() - e.v1();
            len += Math.hypot(du, dv);
        }
        return new LayoutArc(id, Variant.INTERIOR,
                startNodeId, endNodeId,
                direction,
                -1, 0f, 0f,
                List.copyOf(polyline),
                (float) len);
    }

    public boolean ridesOnTArc() { return variant != Variant.INTERIOR; }

    /**
     * Re-derive the per-face (faceId, uIn, vIn, uOut, vOut) polyline. Used by
     * {@link LyonMetrics} and downstream rendering. INHERITED reads stepUvs +
     * meshFaceCrossings of the underlying TArc; INTERIOR returns the cached
     * polyline. DERIVED clips the underlying TArc's polyline to {@code [t0, t1]}
     * (PATCH-79 will land the precise clip; for now returns the full polyline
     * — never reached in PATCH-77 since DERIVED is never produced here).
     */
    public List<SplitEdge> tracePolyline(TMesh tmesh) {
        if (variant == Variant.INTERIOR) return interiorPolyline;
        TArc tarc = tmesh.arcs().get(underlyingTArcId);
        List<SplitEdge> out = new ArrayList<>();
        var crossings = tarc.meshFaceCrossings();
        var steps = tarc.stepUvs();
        for (int i = 0; i < steps.size(); i++) {
            float[] s = steps.get(i);
            int faceId = crossings.get(i)[0];
            out.add(new SplitEdge(faceId, s[0], s[1], s[2], s[3]));
        }
        return out;
    }
}
