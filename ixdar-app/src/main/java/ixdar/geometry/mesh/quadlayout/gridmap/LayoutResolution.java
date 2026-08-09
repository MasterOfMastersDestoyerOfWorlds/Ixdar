package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * The number of quads each layout arc carries, measured from the seamless
 * parametrization. LCK21a's quantized lengths fix connectivity, not resolution.
 *
 * <p>
 * See also: LCBK19 Section 6.2
 */
public final class LayoutResolution {

    /** Corner {@code (u, v)} pairs one source face's chart is read into. */
    public static final int CORNER_UV_SIZE = 6;

    /** Strip id of an arc not yet assigned to one. */
    public static final int UNASSIGNED = -1;

    /** Worst-proportioned patches named individually in the sizing report. */
    public static final int WORST_PATCHES_LISTED = 5;

    /** Guard against dividing by a strip whose arcs all measured zero. */
    private static final double MINIMUM_MEAN_LENGTH = 1.0e-9;

    public final EmbeddedTMesh tmesh;

    /** The parametrization the arcs' extents are measured in. */
    public final SeamlessParameterization seamless;

    /** Parametric length one quad edge should span. */
    public final double targetEdgeLength;

    /** Parametric length of each arc, indexed by arc id. */
    public double[] parametricLengthByArc;

    /**
     * Strip each arc belongs to, indexed by arc id; {@link #UNASSIGNED} for a
     * retired arc. Opposite sides of a patch carry one count, so a strip is the
     * transitive closure of that rule and the unit sizing works on.
     */
    public int[] stripByArc;

    /** Strips found. */
    public int stripCount;

    /** Arc path steps whose two ends hold one chart position, so they measure zero. */
    public int collapsedStepCount;

    /** The first collapsed step found, described for the failure message. */
    public String firstCollapsedStep;

    /** The largest ratio of a strip's longest arc to that strip's mean length. */
    public double worstStripSpread;

    /**
     * Stores the layout whose arcs are measured and the parametrization to measure
     * them in.
     *
     * @param tmesh            conforming T-mesh whose live arcs are sized
     * @param seamless         the parametrization the arcs' extents are measured in
     * @param targetEdgeLength parametric length one quad edge should span
     */
    public LayoutResolution(EmbeddedTMesh tmesh, SeamlessParameterization seamless,
            double targetEdgeLength) {
        this.tmesh = tmesh;
        this.seamless = seamless;
        this.targetEdgeLength = targetEdgeLength;
    }

    /**
     * Measures every live arc and writes each one's quad count into
     * {@link EmbeddedArc#quadCount}.
     *
     * @throws IllegalStateException when a patch side carries more than one arc, or
     *                               an arc's path has an unmeasurable step
     * @return this, measured
     */
    public LayoutResolution build() {
        requireSingleArcSides();
        measureArcLengths();
        sizeStrips();
        System.out.printf("[grid-sizing] strips=%d target=%.4f worstStripSpread=%.1f%n",
                stripCount, targetEdgeLength, worstStripSpread);
        reportWorstSizedPatches();
        return this;
    }

    /**
     * Reports the patches whose rectangle aspect disagrees most with the aspect
     * their sides actually measure. A patch listed here is asked to map a shape onto
     * a rectangle of the wrong proportions, which its Tutte map can only absorb as
     * crowding.
     */
    private void reportWorstSizedPatches() {
        List<double[]> byMismatch = new ArrayList<>();
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            double measuredWidth = sideParametricLength(patch, 0);
            double measuredHeight = sideParametricLength(patch, 1);
            int quadWidth = tmesh.sideQuadCount(patch.patchId, 0);
            int quadHeight = tmesh.sideQuadCount(patch.patchId, 1);
            double measuredAspect = measuredWidth / Math.max(MINIMUM_MEAN_LENGTH, measuredHeight);
            double quadAspect = quadWidth / (double) quadHeight;
            double mismatch = Math.max(measuredAspect / quadAspect, quadAspect / measuredAspect);
            byMismatch.add(new double[] { mismatch, patch.patchId, measuredWidth, measuredHeight,
                    quadWidth, quadHeight });
        }
        byMismatch.sort((first, second) -> Double.compare(second[0], first[0]));
        System.out.println("[grid-sizing] worst aspect mismatches:");
        for (int index = 0; index < Math.min(WORST_PATCHES_LISTED, byMismatch.size()); index++) {
            double[] entry = byMismatch.get(index);
            System.out.printf("[grid-sizing]   patch %d: measured %.2fx%.2f -> rectangle %dx%d"
                    + " (aspect off by %.1fx)%n", (int) entry[1], entry[2], entry[3],
                    (int) entry[4], (int) entry[5], entry[0]);
        }
    }

    /**
     * The parametric length of one side of a patch.
     *
     * @param patch patch to measure
     * @param side  side index in {@code [0, 4)}
     * @return the summed parametric length of the side's arcs
     */
    private double sideParametricLength(EmbeddedPatch patch, int side) {
        double total = 0.0;
        for (int arcId : patch.sideArcIds.get(side)) {
            total += parametricLengthByArc[arcId];
        }
        return total;
    }

    /**
     * Checks every live patch side carries exactly one arc, which the strip flood
     * relies on: it steps from a side's single arc to the opposite side's, so a
     * multi-arc side would leave the two sides free to disagree.
     *
     * @throws IllegalStateException when a side carries more than one arc
     */
    private void requireSingleArcSides() {
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                int arcCount = patch.sideArcIds.get(side).size();
                if (arcCount != 1) {
                    throw new IllegalStateException("patch " + patch.patchId + " side " + side
                            + " carries " + arcCount + " arcs; the resolution is only well"
                            + " defined once every side is one arc, so a degree-two node"
                            + " interior to a side has to be merged away first");
                }
            }
        }
    }

    /**
     * Measures every live arc in the seamless parametrization. Chart positions come
     * from double barycentric coordinates, not float surface positions.
     *
     * @throws IllegalStateException when a step spans two source faces or has zero
     *                               length
     */
    private void measureArcLengths() {
        parametricLengthByArc = new double[tmesh.arcs.size()];
        double[] cornerUv = new double[CORNER_UV_SIZE];
        double[] here = new double[2];
        double[] previous = new double[2];
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!arc.alive) {
                continue;
            }
            List<Integer> path = arc.path.copyVertexPath;
            double walked = 0.0;
            for (int step = 1; step < path.size(); step++) {
                int sourceFace = sharedSourceFace(path.get(step - 1), path.get(step));
                if (sourceFace == EmbeddedMeshTopology.UNCLAIMED) {
                    throw new IllegalStateException("arc " + arc.arcId + " steps from copy vertex "
                            + path.get(step - 1) + " to " + path.get(step) + " with no source face"
                            + " holding both, so the step has no length in the parametrization;"
                            + " the carve left a path step crossing a transition");
                }
                seamless.faceCornerUv(sourceFace, cornerUv);
                chartPosition(sourceFace, path.get(step - 1), cornerUv, previous);
                chartPosition(sourceFace, path.get(step), cornerUv, here);
                double stepLength = Math.hypot(here[0] - previous[0], here[1] - previous[1]);
                if (stepLength <= 0.0) {
                    collapsedStepCount++;
                    if (firstCollapsedStep == null) {
                        firstCollapsedStep = describeCollapsedStep(arc.arcId, path.get(step - 1),
                                path.get(step), sourceFace, cornerUv);
                    }
                }
                walked += stepLength;
            }
            parametricLengthByArc[arc.arcId] = walked;
        }
        if (collapsedStepCount > 0) {
            throw new IllegalStateException(collapsedStepCount + " arc path steps measure zero"
                    + " across the layout, so two copy vertices hold one point and the working"
                    + " copy is degenerate. First: " + firstCollapsedStep);
        }
    }

    /**
     * Describes a path step that measures zero, separating the two causes: a source
     * face whose chart has collapsed, or two path vertices closer than double
     * precision resolves.
     *
     * @param arcId      arc whose path holds the step
     * @param fromVertex copy vertex the step leaves
     * @param toVertex   copy vertex the step arrives at
     * @param sourceFace source face both vertices lie in
     * @param cornerUv   that face's three corner {@code (u, v)} pairs
     * @return the exception message
     */
    private String describeCollapsedStep(int arcId, int fromVertex, int toVertex, int sourceFace,
            double[] cornerUv) {
        double[] fromBarycentric = tmesh.topology.barycentricOf(sourceFace, fromVertex);
        double[] toBarycentric = tmesh.topology.barycentricOf(sourceFace, toVertex);
        double widest = 0.0;
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            widest = Math.max(widest, Math.abs(fromBarycentric[corner] - toBarycentric[corner]));
        }
        double chartArea = 0.5 * ((cornerUv[2] - cornerUv[0]) * (cornerUv[5] - cornerUv[1])
                - (cornerUv[4] - cornerUv[0]) * (cornerUv[3] - cornerUv[1]));
        return "arc " + arcId + " steps from copy vertex " + fromVertex + " to " + toVertex
                + " with zero length in source face " + sourceFace
                + "; chart area " + chartArea + " over corners " + Arrays.toString(cornerUv)
                + "; barycentrics " + Arrays.toString(fromBarycentric) + " and "
                + Arrays.toString(toBarycentric) + " differ by at most " + widest
                + ". A near-zero chart area is a Stage 0 injectivity failure; a healthy area with"
                + " a barycentric gap near 1e-16 means the carve split an edge onto a vertex"
                + " already there";
    }

    /**
     * A source face whose closure holds both endpoints of one path step, so their
     * chart positions are comparable without a transition.
     *
     * @param fromVertex copy vertex the step leaves
     * @param toVertex   copy vertex the step arrives at
     * @return the source face index, or {@link EmbeddedMeshTopology#UNCLAIMED} when
     *         there is none
     */
    private int sharedSourceFace(int fromVertex, int toVertex) {
        EmbeddedMeshTopology topology = tmesh.topology;
        HalfEdgeMesh copy = topology.copy;
        int copyEdge = topology.edgeBetween(fromVertex, toVertex);
        if (copyEdge == EmbeddedMeshTopology.UNCLAIMED) {
            return EmbeddedMeshTopology.UNCLAIMED;
        }
        int halfEdge = copy.edgeHalfEdge(copyEdge);
        for (int side = 0; side < 2; side++) {
            int copyFace = copy.halfEdgeFace(side == 0 ? halfEdge : copy.halfEdgeTwin(halfEdge));
            if (copyFace == EmbeddedMeshTopology.UNCLAIMED) {
                continue;
            }
            int sourceFace = topology.sourceFaceByCopyFace[copyFace];
            if (topology.barycentricOf(sourceFace, fromVertex) != null
                    && topology.barycentricOf(sourceFace, toVertex) != null) {
                return sourceFace;
            }
        }
        return EmbeddedMeshTopology.UNCLAIMED;
    }

    /**
     * The chart position of a copy vertex inside a source face, by its barycentric
     * coordinates.
     *
     * @param sourceFace source face whose chart is read
     * @param copyVertex copy vertex lying in that face's closure
     * @param cornerUv   the face's three corner {@code (u, v)} pairs
     * @param out        receives the {@code (u, v)} position
     */
    private void chartPosition(int sourceFace, int copyVertex, double[] cornerUv, double[] out) {
        double[] barycentric = tmesh.topology.barycentricOf(sourceFace, copyVertex);
        out[0] = 0.0;
        out[1] = 0.0;
        for (int corner = 0; corner < HalfEdgeMesh.TRIANGLE_CORNERS; corner++) {
            out[0] += barycentric[corner] * cornerUv[corner * 2];
            out[1] += barycentric[corner] * cornerUv[corner * 2 + 1];
        }
    }

    /**
     * Gives each opposite-side strip one compatible quad count from its arcs' mean
     * parametric length and the target edge length. Rounding the mean is the
     * least-squares choice: a strip is one free variable.
     */
    private void sizeStrips() {
        stripByArc = new int[tmesh.arcs.size()];
        Arrays.fill(stripByArc, UNASSIGNED);
        List<List<Integer>> arcsByStrip = new ArrayList<>();
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!arc.alive || stripByArc[arc.arcId] != UNASSIGNED) {
                continue;
            }
            arcsByStrip.add(floodStrip(arc.arcId, stripCount++));
        }
        for (List<Integer> members : arcsByStrip) {
            double total = 0.0;
            double longest = 0.0;
            for (int arcId : members) {
                total += parametricLengthByArc[arcId];
                longest = Math.max(longest, parametricLengthByArc[arcId]);
            }
            double mean = total / members.size();
            int quads = Math.max(1, (int) Math.round(mean / targetEdgeLength));
            worstStripSpread = Math.max(worstStripSpread,
                    longest / Math.max(MINIMUM_MEAN_LENGTH, mean));
            for (int arcId : members) {
                tmesh.arcs.get(arcId).quadCount = quads;
            }
        }
    }

    /**
     * Collects one strip: the arcs reachable from a seed by stepping to the
     * opposite side of an incident patch, which are exactly the arcs a rectangle
     * forces to carry the same count.
     *
     * @param seedArcId arc to start from
     * @param strip     strip id to stamp
     * @return the strip's member arc ids
     */
    private List<Integer> floodStrip(int seedArcId, int strip) {
        List<Integer> frontier = new ArrayList<>();
        stripByArc[seedArcId] = strip;
        frontier.add(seedArcId);
        for (int cursor = 0; cursor < frontier.size(); cursor++) {
            EmbeddedArc arc = tmesh.arcs.get(frontier.get(cursor));
            for (int patchId : new int[] { arc.leftPatchId, arc.rightPatchId }) {
                if (patchId == EmbeddedTMesh.NONE || !tmesh.patches.get(patchId).alive) {
                    continue;
                }
                EmbeddedPatch patch = tmesh.patches.get(patchId);
                for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                    if (patch.sideArcIds.get(side).get(0) != arc.arcId) {
                        continue;
                    }
                    int oppositeArcId = patch.sideArcIds
                            .get((side + 2) % EmbeddedPatch.SIDES).get(0);
                    if (stripByArc[oppositeArcId] == UNASSIGNED) {
                        stripByArc[oppositeArcId] = strip;
                        frontier.add(oppositeArcId);
                    }
                }
            }
        }
        return frontier;
    }
}
