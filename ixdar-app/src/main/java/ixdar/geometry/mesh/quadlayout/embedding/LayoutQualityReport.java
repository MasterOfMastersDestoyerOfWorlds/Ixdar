package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;

/**
 * Shape statistics of a conforming layout: how rectangular its patches actually are on the
 * surface, and how much bookkeeping structure is left in the T-mesh.
 *
 * <p>A patch is four-sided by construction, so only measurement says whether it reads as a
 * rectangle or as a triangle with one collapsed side.
 */
public final class LayoutQualityReport {

    /** A side shorter than this fraction of the patch's longest side counts as collapsed. */
    public static final double COLLAPSED_SIDE_FRACTION = 0.1;

    /** Aspect ratio above which a patch counts as a ribbon rather than a rectangle. */
    public static final double RIBBON_ASPECT = 4.0;

    /** Worst patches listed individually. */
    public static final int WORST_PATCHES_LISTED = 5;

    /** Degrees in a straight angle, for reporting corner turns. */
    public static final double STRAIGHT_DEGREES = 180.0;

    /** Format of a parametric length in the per-patch lines. */
    public static final String PARAMETRIC_FORMAT = "%.3f";

    public final EmbeddedTMesh tmesh;

    /** Stage name printed with the summary, so two reports can be compared. */
    public final String stage;

    /** Parametric length of each arc, indexed by arc id. */
    public final double[] lengthByArc;

    /** The motorcycle graph the arcs were traced from, for their original iso-line lengths. */
    public final MotorcycleGraph motorcycleGraph;

    /** Live patches measured. */
    public int patchCount;

    /** Patches with a side shorter than {@link #COLLAPSED_SIDE_FRACTION} of their longest. */
    public int collapsedSidePatchCount;

    /** Patches whose two directions differ by more than {@link #RIBBON_ASPECT}. */
    public int ribbonPatchCount;

    /** Largest ratio between a patch's two mean side lengths. */
    public double worstAspect;

    /** Smallest ratio between a patch's shortest and longest side. */
    public double worstSideRatio = 1.0;

    /** Live nodes whose degree is two, so they subdivide a side without carrying a separatrix. */
    public int degreeTwoNodeCount;

    /** Live patch sides carrying more than one arc. */
    public int multiArcSideCount;

    /** Live patches whose quantized area is more than one quad. */
    public int multiQuadPatchCount;

    /**
     * Stores the layout to measure.
     *
     * @param tmesh           embedded T-mesh, at any point after the arrangement is built
     * @param stage           stage name printed with the summary
     * @param lengthByArc     parametric length of each arc, indexed by arc id
     * @param motorcycleGraph the graph the arcs were traced from
     */
    public LayoutQualityReport(EmbeddedTMesh tmesh, String stage, double[] lengthByArc,
            MotorcycleGraph motorcycleGraph) {
        this.tmesh = tmesh;
        this.stage = stage;
        this.lengthByArc = lengthByArc;
        this.motorcycleGraph = motorcycleGraph;
    }

    /**
     * Measures every live patch and prints a summary with the worst offenders.
     *
     * @return this, measured
     */
    public LayoutQualityReport build() {
        for (EmbeddedNode node : tmesh.nodes) {
            if (node.alive && tmesh.degree(node.nodeId) == 2) {
                degreeTwoNodeCount++;
            }
        }
        List<double[]> worst = new ArrayList<>();
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            patchCount++;
            double[] sideLength = new double[EmbeddedPatch.SIDES];
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                sideLength[side] = sideSurfaceLength(patch, side);
                if (patch.sideArcIds.get(side).size() > 1) {
                    multiArcSideCount++;
                }
            }
            if (tmesh.sideQuantizedLength(patch.patchId, 0)
                    * tmesh.sideQuantizedLength(patch.patchId, 1) > 1) {
                multiQuadPatchCount++;
            }
            double longest = Math.max(Math.max(sideLength[0], sideLength[1]),
                    Math.max(sideLength[2], sideLength[3]));
            double shortest = Math.min(Math.min(sideLength[0], sideLength[1]),
                    Math.min(sideLength[2], sideLength[3]));
            double sideRatio = longest == 0.0 ? 0.0 : shortest / longest;
            double acrossU = 0.5 * (sideLength[0] + sideLength[2]);
            double acrossV = 0.5 * (sideLength[1] + sideLength[3]);
            double aspect = Math.min(acrossU, acrossV) == 0.0 ? Double.POSITIVE_INFINITY
                    : Math.max(acrossU, acrossV) / Math.min(acrossU, acrossV);
            if (sideRatio < COLLAPSED_SIDE_FRACTION) {
                collapsedSidePatchCount++;
            }
            if (aspect > RIBBON_ASPECT) {
                ribbonPatchCount++;
            }
            worstAspect = Math.max(worstAspect, aspect);
            worstSideRatio = Math.min(worstSideRatio, sideRatio);
            worst.add(new double[] { sideRatio, aspect, patch.patchId, sideLength[0],
                sideLength[1], sideLength[2], sideLength[3] });
        }
        worst.sort(Comparator.comparingDouble(entry -> entry[0]));
        System.out.printf(
                "[layout-quality] %s patches=%d collapsedSide=%d ribbon=%d worstSideRatio=%.4f"
                        + " worstAspect=%.1f degree2Nodes=%d multiArcSides=%d multiQuadPatches=%d%n",
                stage, patchCount, collapsedSidePatchCount, ribbonPatchCount, worstSideRatio,
                worstAspect, degreeTwoNodeCount, multiArcSideCount, multiQuadPatchCount);
        for (int index = 0; index < Math.min(WORST_PATCHES_LISTED, worst.size()); index++) {
            double[] entry = worst.get(index);
            System.out.printf(
                    "[layout-quality]   %s patch %d sideRatio=%.4f aspect=%.1f sides=%.4f %.4f"
                            + " %.4f %.4f parametric=%s traced=%s quantized=%dx%d angles=%s"
                            + " corners=%s%n",
                    stage, (int) entry[2], entry[0], entry[1], entry[3], entry[4], entry[5],
                    entry[6], sideParametricLengths(tmesh.patches.get((int) entry[2])),
                    sideTracedLengths(tmesh.patches.get((int) entry[2])),
                    tmesh.sideQuantizedLength((int) entry[2], 0),
                    tmesh.sideQuantizedLength((int) entry[2], 1),
                    cornerAngles(tmesh.patches.get((int) entry[2])),
                    cornerNodes(tmesh.patches.get((int) entry[2])));
        }
        return this;
    }

    /**
     * The 3D length of one side of a patch, summed along its arcs' edge paths.
     *
     * @param patch patch to measure
     * @param side  side index in {@code [0, 4)}
     * @return the side's length on the surface
     */
    private double sideSurfaceLength(EmbeddedPatch patch, int side) {
        double length = 0.0;
        Vector3f here = new Vector3f();
        Vector3f previous = new Vector3f();
        for (int arcId : patch.sideArcIds.get(side)) {
            List<Integer> path = tmesh.arcs.get(arcId).path.copyVertexPath;
            tmesh.topology.copy.vertexPosition(path.get(0), previous);
            for (int step = 1; step < path.size(); step++) {
                tmesh.topology.copy.vertexPosition(path.get(step), here);
                length += previous.distance(here);
                previous.set(here);
            }
        }
        return length;
    }

    /**
     * The four corner turn angles of a patch in degrees, measured between the first edges of the
     * sides meeting there. A rectangle turns ninety degrees at each corner; a straight turn means
     * the patch reads as a triangle.
     *
     * @param patch patch to measure
     * @return the four angles, formatted
     */
    private String cornerAngles(EmbeddedPatch patch) {
        StringBuilder angles = new StringBuilder();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            if (side > 0) {
                angles.append('/');
            }
            int previousSide = (side + EmbeddedPatch.SIDES - 1) % EmbeddedPatch.SIDES;
            Vector3f incoming = sideEndDirection(patch, previousSide);
            Vector3f outgoing = sideStartDirection(patch, side);
            double turn = STRAIGHT_DEGREES;
            if (incoming.lengthSquared() > 0f && outgoing.lengthSquared() > 0f) {
                turn = Math.toDegrees(incoming.angle(outgoing));
            }
            angles.append(String.format("%.0f", turn));
        }
        return angles.toString();
    }

    /**
     * The four sides' lengths in the seamless parametrization. A side that is short on the
     * surface but a full quantum here is only distortion; one that is short in both was
     * quantized above its own extent.
     *
     * @param patch patch to measure
     * @return the four parametric lengths, formatted
     */
    private String sideParametricLengths(EmbeddedPatch patch) {
        StringBuilder lengths = new StringBuilder();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            if (side > 0) {
                lengths.append('/');
            }
            double length = 0.0;
            for (int arcId : patch.sideArcIds.get(side)) {
                length += lengthByArc[arcId];
            }
            lengths.append(String.format(PARAMETRIC_FORMAT, length));
        }
        return lengths.toString();
    }

    /**
     * The four sides' lengths as originally traced along iso-lines, before any operator moved
     * them. A side short here as well was short in the input; one short only now was squeezed by
     * the contraction.
     *
     * @param patch patch to measure
     * @return the four traced lengths, formatted, with a dash for an arc an operator minted
     */
    private String sideTracedLengths(EmbeddedPatch patch) {
        StringBuilder lengths = new StringBuilder();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            if (side > 0) {
                lengths.append('/');
            }
            double length = 0.0;
            boolean minted = false;
            for (int arcId : patch.sideArcIds.get(side)) {
                int sourceArcId = tmesh.arcs.get(arcId).sourceArcId;
                minted |= sourceArcId == EmbeddedTMesh.NONE;
                if (sourceArcId != EmbeddedTMesh.NONE) {
                    length += motorcycleGraph.arcs.get(sourceArcId).parametricLength;
                }
            }
            lengths.append(minted ? "-" : String.format(PARAMETRIC_FORMAT, length));
        }
        return lengths.toString();
    }

    /**
     * The four corner nodes of a patch with their degree and whether the quantization must hold
     * them apart — a needle patch whose short sides join two critical nodes is a close
     * singularity pair the layout is forced to separate, not a broken cell.
     *
     * @param patch patch to read
     * @return the four corners, formatted
     */
    private String cornerNodes(EmbeddedPatch patch) {
        StringBuilder corners = new StringBuilder();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            if (side > 0) {
                corners.append('/');
            }
            EmbeddedNode node = tmesh.nodes.get(patch.cornerNodeId(side));
            corners.append(node.nodeId).append(node.critical ? 'c' : '-').append('d')
                    .append(tmesh.degree(node.nodeId));
        }
        return corners.toString();
    }

    /**
     * The direction a side leaves its start corner in.
     *
     * @param patch patch to read
     * @param side  side index in {@code [0, 4)}
     * @return the unit direction, or a zero vector when the side has no extent
     */
    private Vector3f sideStartDirection(EmbeddedPatch patch, int side) {
        List<Integer> path = orientedSidePath(patch, side);
        Vector3f start = tmesh.topology.copy.vertexPosition(path.get(0));
        for (int step = 1; step < path.size(); step++) {
            Vector3f next = tmesh.topology.copy.vertexPosition(path.get(step));
            if (next.distanceSquared(start) > 0f) {
                return next.sub(start).normalize();
            }
        }
        return new Vector3f();
    }

    /**
     * The direction a side arrives at its end corner from, pointing back along the side.
     *
     * @param patch patch to read
     * @param side  side index in {@code [0, 4)}
     * @return the unit direction, or a zero vector when the side has no extent
     */
    private Vector3f sideEndDirection(EmbeddedPatch patch, int side) {
        List<Integer> path = orientedSidePath(patch, side);
        Vector3f end = tmesh.topology.copy.vertexPosition(path.get(path.size() - 1));
        for (int step = path.size() - 2; step >= 0; step--) {
            Vector3f previous = tmesh.topology.copy.vertexPosition(path.get(step));
            if (previous.distanceSquared(end) > 0f) {
                return previous.sub(end).normalize();
            }
        }
        return new Vector3f();
    }

    /**
     * The copy vertices along one side of a patch, in the side's walking order.
     *
     * @param patch patch to read
     * @param side  side index in {@code [0, 4)}
     * @return the side's copy vertex ids from its start corner to the next
     */
    private List<Integer> orientedSidePath(EmbeddedPatch patch, int side) {
        List<Integer> sideArcs = patch.sideArcIds.get(side);
        List<Integer> sideNodes = patch.sideNodeIds.get(side);
        List<Integer> vertices = new ArrayList<>();
        vertices.add(tmesh.nodes.get(sideNodes.get(0)).copyVertex);
        for (int arcIndex = 0; arcIndex < sideArcs.size(); arcIndex++) {
            EmbeddedArc arc = tmesh.arcs.get(sideArcs.get(arcIndex));
            List<Integer> path = arc.path.copyVertexPath;
            boolean forward = arc.startNodeId == sideNodes.get(arcIndex);
            for (int step = 1; step < path.size(); step++) {
                vertices.add(forward ? path.get(step) : path.get(path.size() - 1 - step));
            }
        }
        return vertices;
    }
}
