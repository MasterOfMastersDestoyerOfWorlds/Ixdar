package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.List;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * The length of every embedded arc measured in the seamless parametrization, by integrating the
 * chart along the arc's edge path.
 *
 * <p>LCK21a §6 sizes each patch's quad grid from the patch's parametric extent, which the arcs no
 * longer carry once the operators have rerouted and split them.
 */
public final class ArcParametricLength {

    /** Corners of a triangle; the source mesh is triangulated. */
    public static final int TRIANGLE_CORNERS = 3;

    /** Doubles per face in {@link SeamlessParameterization#faceCornerUv}. */
    public static final int CORNER_UV_SIZE = 6;

    /** Detour above which an arc's path no longer realizes the iso-line it was traced from. */
    public static final double DETOUR_THRESHOLD = 2.0;

    public final EmbeddedTMesh tmesh;
    public final SeamlessParameterization seamless;

    /** The graph the arcs were traced from, for the length each arc is meant to realize. */
    public final MotorcycleGraph motorcycleGraph;

    /** Stage name printed with the summary, so successive stages can be compared. */
    public final String stage;

    /** Sum over live arcs of parametric length, which rerouting can only grow. */
    public double totalLength;

    /** Live arcs measured. */
    public int arcCount;

    /**
     * How far each arc's embedded path overshoots the iso-line it was traced from, indexed by arc
     * id: its measured length over its {@code TraceArc}'s. One is a faithful realization; a large
     * value means the path took a detour the parametrization never asked for.
     */
    public double[] detourByArc;

    /** Largest {@link #detourByArc} over arcs that still know which trace they came from. */
    public double worstDetour;

    /** Live arcs whose detour exceeds {@link #DETOUR_THRESHOLD}. */
    public int detouringArcCount;

    /** Live arcs an operator minted, which have no traced length to compare against. */
    public int mintedArcCount;

    /** Parametric length of each arc, indexed by arc id; zero for a retired arc. */
    public double[] lengthByArc;

    /** Path steps whose two endpoints share no source face, so the chart could not be read. */
    public int unmeasuredStepCount;

    /**
     * Stores the layout and the parametrization its arcs are measured in.
     *
     * @param tmesh           embedded T-mesh whose arcs are measured
     * @param seamless        parametrization the arcs were traced in
     * @param motorcycleGraph the graph the arcs were traced from
     * @param stage           stage name printed with the summary
     */
    public ArcParametricLength(EmbeddedTMesh tmesh, SeamlessParameterization seamless,
            MotorcycleGraph motorcycleGraph, String stage) {
        this.tmesh = tmesh;
        this.seamless = seamless;
        this.motorcycleGraph = motorcycleGraph;
        this.stage = stage;
    }

    /**
     * Measures every live arc.
     *
     * @return this, populated
     */
    public ArcParametricLength build() {
        lengthByArc = new double[tmesh.arcs.size()];
        detourByArc = new double[tmesh.arcs.size()];
        double[] cornerUv = new double[CORNER_UV_SIZE];
        double[] here = new double[2];
        double[] previous = new double[2];
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!arc.alive) {
                continue;
            }
            List<Integer> path = arc.path.copyVertexPath;
            double length = 0.0;
            for (int step = 1; step < path.size(); step++) {
                int sourceFace = sharedSourceFace(path.get(step - 1), path.get(step));
                if (sourceFace == EmbeddedMeshTopology.UNCLAIMED) {
                    unmeasuredStepCount++;
                    continue;
                }
                seamless.faceCornerUv(sourceFace, cornerUv);
                chartPosition(sourceFace, path.get(step - 1), cornerUv, previous);
                chartPosition(sourceFace, path.get(step), cornerUv, here);
                length += Math.hypot(here[0] - previous[0], here[1] - previous[1]);
            }
            lengthByArc[arc.arcId] = length;
            totalLength += length;
            arcCount++;
            if (arc.sourceArcId == EmbeddedTMesh.NONE) {
                mintedArcCount++;
                continue;
            }
            double traced = motorcycleGraph.arcs.get(arc.sourceArcId).parametricLength;
            if (traced <= 0.0) {
                continue;
            }
            detourByArc[arc.arcId] = length / traced;
            worstDetour = Math.max(worstDetour, detourByArc[arc.arcId]);
            detouringArcCount += detourByArc[arc.arcId] > DETOUR_THRESHOLD ? 1 : 0;
        }
        System.out.printf("[arc-parametric] %s arcs=%d totalLength=%.1f meanLength=%.3f"
                + " worstDetour=%.1f detouring=%d minted=%d unmeasuredSteps=%d%n", stage, arcCount,
                totalLength, totalLength / Math.max(1, arcCount), worstDetour, detouringArcCount,
                mintedArcCount, unmeasuredStepCount);
        return this;
    }

    /**
     * A source face whose closure holds both endpoints of one path step, so their chart positions
     * are comparable without a transition.
     *
     * @param fromVertex copy vertex the step leaves
     * @param toVertex   copy vertex the step arrives at
     * @return the source face index, or {@link EmbeddedMeshTopology#UNCLAIMED} when there is none
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
     * The chart position of a copy vertex inside a source face, by its barycentric coordinates.
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
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            out[0] += barycentric[corner] * cornerUv[corner * 2];
            out[1] += barycentric[corner] * cornerUv[corner * 2 + 1];
        }
    }
}
