package benchmark;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArrangementDiagnosticException;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;

/**
 * Localizes botijo's operator-830 twin-cell tear: replays the contraction watching the
 * point-embedded separator arc and its two flank patches, reporting the operator that pinched
 * the separator and the first moment the flanks flood one shared cell. Pick the mesh with
 * {@code -Dbenchmark.off}.
 */
public final class TwinCellProbe {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/botijo_in_tri.off";

    /** Operators replayed before the failing collapse, from the progress probe. */
    private static final int REPLAYED_OPS = 1505;

    /** The torn arc, the collapsing arc, the point separator, and both patches' other sides. */
    private static final int[] PROBED_ARCS = { 2653 };

    /** The torn arc's flanks and the twin pair named in the tear diagnostic. */
    private static final int[] PROBED_PATCHES = { 1162, 1135 };

    /** The twin pair whose floods coincide in the tear diagnostic. */
    private static final int[] TWIN_PATCHES = { 1162, 1135 };

    /** Last printed signature per probed arc, so only changes are reported. */
    private final String[] arcSignatures = new String[PROBED_ARCS.length];

    /** Last printed signature per probed patch, so only changes are reported. */
    private final String[] patchSignatures = new String[PROBED_PATCHES.length];

    /** Whether the twin patches' floods have already been reported as coinciding. */
    private boolean twinFloodReported;

    /**
     * Replays the contraction reporting probed changes and the first twin flood, then steps
     * the failing collapse drag by drag.
     *
     * @throws IOException when the mesh file cannot be read
     */
    @Test
    public void probeTwinCell() throws IOException {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);
        EmbeddedTMesh tmesh = engine.buildTMesh();
        tmesh.labelPatchCovers();
        for (int op = 1; op <= REPLAYED_OPS; op++) {
            String applied = tmesh.contractStep();
            reportProbedChanges(tmesh, op, applied);
            reportTwinFlood(tmesh, op);
        }

        ZeroArcCollapseOperator collapseArc = tmesh.collapseArc;
        int collapsingArcId = collapseArc.mostContendedArc();
        collapseArc.beginCollapse(collapsingArcId);
        System.out.printf("[probe] failing collapse of arc %d: moved node %d (vertex %d) ->"
                + " surviving node %d (vertex %d), channel %s, fan %s%n", collapsingArcId,
                collapseArc.movedNodeId, collapseArc.movedVertex, collapseArc.survivingNodeId,
                collapseArc.targetVertex, collapseArc.channel, collapseArc.fan);
        describeRing(tmesh, collapseArc.targetVertex, "the collapse target, before drags");
        for (int arcId : PROBED_ARCS) {
            List<Integer> path = tmesh.arcs.get(arcId).path.copyVertexPath;
            describeRing(tmesh, path.get(0), "arc " + arcId + " first end, before drags");
            describeRing(tmesh, path.get(path.size() - 1),
                    "arc " + arcId + " last end, before drags");
        }
        try {
            while (collapseArc.dragNextArc()) {
                System.out.printf("[probe] dragged arc %d (banned wedges %d): previous %s%n",
                        collapseArc.lastDraggedArcId, collapseArc.bannedArrivalWedgeCount,
                        collapseArc.lastDraggedPreviousPath);
            }
            collapseArc.finishCollapse();
        } catch (RuntimeException failure) {
            System.out.printf("[probe] DRAG FAILED (arrival wedges banned %d, departure banned"
                    + " %d): %s%n", collapseArc.bannedArrivalWedgeCount,
                    collapseArc.bannedDepartureWedgeCount, failure.getMessage());
        }
        for (int arcId : PROBED_ARCS) {
            System.out.printf("[probe] arc %d after finish: %s%n", arcId,
                    arcSignature(tmesh, arcId));
            describeArcCovers(tmesh, arcId);
        }
        for (int patchId : PROBED_PATCHES) {
            describeFlood(tmesh, patchId);
        }
        ArrangementDiagnosticException tear = tmesh.flankTearFailure("probe");
        System.out.printf("[probe] tear: %s%n", tear == null ? "none" : tear.getMessage());
    }

    /**
     * Prints every probed arc or patch whose signature changed under the operator just
     * applied, naming that operator.
     *
     * @param tmesh   T-mesh being probed
     * @param op      operator ordinal just applied
     * @param applied one-line description of the operator, from {@code contractStep}
     */
    private void reportProbedChanges(EmbeddedTMesh tmesh, int op, String applied) {
        for (int index = 0; index < PROBED_ARCS.length; index++) {
            String signature = arcSignature(tmesh, PROBED_ARCS[index]);
            if (!signature.equals(arcSignatures[index])) {
                arcSignatures[index] = signature;
                System.out.printf("[probe] op %d (%s) arc %d: %s%n", op, applied,
                        PROBED_ARCS[index], signature);
            }
        }
        for (int index = 0; index < PROBED_PATCHES.length; index++) {
            String signature = patchSignature(tmesh, PROBED_PATCHES[index]);
            if (!signature.equals(patchSignatures[index])) {
                patchSignatures[index] = signature;
                System.out.printf("[probe] op %d (%s) patch %d: %s%n", op, applied,
                        PROBED_PATCHES[index], signature);
            }
        }
    }

    /**
     * Reports the first operator after which the twin patches flood identical face sets, the
     * merged-cell moment the tear later reads.
     *
     * @param tmesh T-mesh being probed
     * @param op    operator ordinal just applied, for the caption
     */
    private void reportTwinFlood(EmbeddedTMesh tmesh, int op) {
        if (twinFloodReported) {
            return;
        }
        Set<Integer> firstFlood = null;
        for (int patchId : TWIN_PATCHES) {
            int resolved = tmesh.topology.resolvePatch(patchId);
            if (resolved != patchId || !tmesh.patches.get(resolved).alive
                    || !tmesh.splitPatch.corridor.hasSeedableBoundary(resolved)) {
                return;
            }
            IntIdList faces = tmesh.splitPatch.corridor.patchFaces(resolved);
            Set<Integer> flood = new HashSet<>();
            for (int cursor = 0; cursor < faces.size(); cursor++) {
                flood.add(faces.get(cursor));
            }
            if (firstFlood == null) {
                firstFlood = flood;
            } else if (firstFlood.equals(flood)) {
                twinFloodReported = true;
                System.out.printf("[probe] op %d TWIN FLOOD: patches %d and %d both flood %d"
                        + " faces%n", op, TWIN_PATCHES[0], TWIN_PATCHES[1], flood.size());
            }
        }
    }

    /**
     * One probed arc's watch signature: liveness, raw and resolved flanks, and its path.
     *
     * @param tmesh T-mesh being probed
     * @param arcId arc to sign
     * @return a line that changes exactly when the arc's tear-relevant state does
     */
    private String arcSignature(EmbeddedTMesh tmesh, int arcId) {
        if (arcId >= tmesh.arcs.size()) {
            return "absent";
        }
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        if (!arc.alive) {
            return "dead";
        }
        List<Integer> path = arc.path.copyVertexPath;
        return "q=" + arc.quantizedLength + " flanks " + arc.leftPatchId + "|"
                + arc.rightPatchId + " resolved "
                + tmesh.topology.resolvePatch(arc.leftPatchId) + "|"
                + tmesh.topology.resolvePatch(arc.rightPatchId) + " nodes " + arc.startNodeId
                + "->" + arc.endNodeId + " path(" + path.size() + ") "
                + (path.size() <= 8 ? path.toString()
                        : path.subList(0, 4) + "..." + path.get(path.size() - 1));
    }

    /**
     * One probed patch's watch signature: its alias target, liveness, and side arcs.
     *
     * @param tmesh   T-mesh being probed
     * @param patchId patch to sign
     * @return a line that changes exactly when the patch's identity or boundary does
     */
    private String patchSignature(EmbeddedTMesh tmesh, int patchId) {
        if (patchId >= tmesh.patches.size()) {
            return "absent";
        }
        int resolved = tmesh.topology.resolvePatch(patchId);
        if (!tmesh.patches.get(resolved).alive) {
            return "resolved " + resolved + " retired";
        }
        return "resolved " + resolved + " alive, sides "
                + tmesh.patches.get(resolved).sideArcIds;
    }

    /**
     * Prints the resolved cover labels beside every hop of an arc's path as runs, then the
     * ring at each vertex where the label pair changes — the pinch candidates.
     *
     * @param tmesh T-mesh being probed
     * @param arcId arc whose path is walked
     */
    private void describeArcCovers(EmbeddedTMesh tmesh, int arcId) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        if (!arc.alive || arc.path.copyVertexPath.size() < 2) {
            return;
        }
        HalfEdgeMesh copy = tmesh.topology.copy;
        List<Integer> path = arc.path.copyVertexPath;
        StringBuilder runs = new StringBuilder();
        String previousPair = null;
        int runStart = 0;
        for (int hop = 0; hop < path.size() - 1; hop++) {
            int edgeId = tmesh.topology.edgeBetween(path.get(hop), path.get(hop + 1));
            int halfEdge = copy.edgeHalfEdge(edgeId);
            if (copy.halfEdgeVertex(halfEdge) != path.get(hop)) {
                halfEdge = copy.halfEdgeTwin(halfEdge);
            }
            int coverLeft = tmesh.topology.resolvePatch(
                    tmesh.topology.patchLabelOf(copy.halfEdgeFace(halfEdge)));
            int coverRight = tmesh.topology.resolvePatch(tmesh.topology.patchLabelOf(
                    copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge))));
            String pair = coverLeft + "|" + coverRight;
            if (!pair.equals(previousPair)) {
                if (previousPair != null) {
                    runs.append(" [").append(runStart).append("..").append(hop - 1)
                            .append("]=").append(previousPair);
                    describeRing(tmesh, path.get(hop),
                            "arc " + arcId + " cover change at hop " + hop);
                }
                previousPair = pair;
                runStart = hop;
            }
        }
        runs.append(" [").append(runStart).append("..").append(path.size() - 2).append("]=")
                .append(previousPair);
        System.out.printf("[probe] arc %d cover runs:%s%n", arcId, runs);
    }

    /**
     * Prints the claimed spokes and face labels around a vertex in cyclic half-edge order,
     * which is the slot structure the dragged arcs depart from and arrive into.
     *
     * @param tmesh    T-mesh being probed
     * @param vertexId copy vertex whose ring is walked
     * @param moment   caption for when the ring was read
     */
    private void describeRing(EmbeddedTMesh tmesh, int vertexId, String moment) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        int firstEdge = copy.vertexEdgeAt(vertexId, 0);
        int halfEdge = copy.edgeHalfEdge(firstEdge);
        if (copy.halfEdgeVertex(halfEdge) != vertexId) {
            halfEdge = copy.halfEdgeTwin(halfEdge);
        }
        StringBuilder ring = new StringBuilder();
        int start = halfEdge;
        int spokes = 0;
        do {
            int edgeId = copy.halfEdgeEdge(halfEdge);
            int owner = tmesh.topology.ownerArcByCopyEdge[edgeId];
            int faceId = copy.halfEdgeFace(halfEdge);
            int label = tmesh.topology.resolvePatch(tmesh.topology.patchLabelOf(faceId));
            ring.append(" e").append(edgeId)
                    .append(owner == EmbeddedMeshTopology.UNCLAIMED ? "" : "(arc " + owner + ")")
                    .append("->v").append(tmesh.topology.otherEndpoint(edgeId, vertexId))
                    .append(" f").append(faceId).append("[").append(label).append("]");
            halfEdge = copy.halfEdgeTwin(copy.halfEdgeNext(copy.halfEdgeNext(halfEdge)));
            spokes++;
        } while (halfEdge != start && spokes < copy.vertexEdgeCount(vertexId) + 1);
        System.out.printf("[probe] ring of vertex %d %s (%d spokes):%s%n", vertexId, moment,
                spokes, ring);
    }

    /**
     * Prints a probed patch's current cover flood size and bounding arcs, or why it cannot
     * flood.
     *
     * @param tmesh   T-mesh being probed
     * @param patchId patch to flood
     */
    private void describeFlood(EmbeddedTMesh tmesh, int patchId) {
        int resolved = tmesh.topology.resolvePatch(patchId);
        if (resolved != patchId || !tmesh.patches.get(resolved).alive
                || !tmesh.splitPatch.corridor.hasSeedableBoundary(resolved)) {
            System.out.printf("[probe] patch %d flood: unavailable (resolved %d)%n", patchId,
                    resolved);
            return;
        }
        IntIdList faces = tmesh.splitPatch.corridor.patchFaces(resolved);
        System.out.printf("[probe] patch %d floods %d faces, bounded by arcs %s%n", patchId,
                faces.size(), tmesh.splitPatch.corridor.boundingArcsOfLastFlood());
    }
}
