package benchmark;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.ArrangementDiagnosticException;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.NetworkContraction;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.platform.Platforms;

/**
 * Localizes botijo's operator-755 mid-path tear: replays the contraction
 * watching the torn arc's hop covers and the named patches for the first
 * divergence, then steps the failing collapse and traces who last owned the
 * mislabeled face. Pick the mesh with {@code -Dbenchmark.off}.
 */
public final class StaleCoverProbe {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/botijo_in_tri.off";

    /** Operators replayed before the failing collapse, from the B-rewind count. */
    private static final int REPLAYED_OPS = 754;

    /**
     * Arcs named in the tear diagnostic: the collapsing arc, the torn arc, its
     * bigon twin.
     */
    private static final int[] PROBED_ARCS = { 1047, 1048, 1049 };

    /** Patches named in the tear diagnostic. */
    private static final int[] PROBED_PATCHES = { 412, 2393 };

    /** Last printed signature per probed arc, so only changes are reported. */
    private final String[] arcSignatures = new String[PROBED_ARCS.length];

    /** Last printed signature per probed patch, so only changes are reported. */
    private final String[] patchSignatures = new String[PROBED_PATCHES.length];

    /**
     * Replays the contraction reporting probed-arc and probed-patch changes, then
     * steps the failing collapse drag by drag with cover forensics on the torn hop.
     *
     * @throws IOException when the mesh file cannot be read
     */
    @Test
    public void probeStaleCover() throws IOException {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);
        ArcNetwork tmesh = engine.buildTMesh();
        NetworkContraction contraction = new NetworkContraction(tmesh);
        tmesh.labelPatchCovers();
        for (int op = 1; op <= REPLAYED_OPS; op++) {
            contraction.contractStep();
            reportProbedChanges(tmesh, op);
        }

        ZeroArcCollapseOperator collapseArc = contraction.collapseArc;
        int collapsingArcId = collapseArc.mostContendedArc();
        collapseArc.beginCollapse(collapsingArcId);
        Platforms.log("[probe] failing collapse of arc %d: moved node %d (vertex %d) ->"
                + " surviving node %d (vertex %d), channel %s, fan %s%n", collapsingArcId,
                collapseArc.movedNodeId, collapseArc.movedVertex, collapseArc.survivingNodeId,
                collapseArc.targetVertex, collapseArc.channel, collapseArc.fan);
        Platforms.log("[probe] touched patches:%s%n", touchedPatchText(tmesh, collapseArc));
        for (int arcId : PROBED_ARCS) {
            describeHops(tmesh, arcId, "before drags");
        }
        for (int patchId : PROBED_PATCHES) {
            describeFlood(tmesh, patchId);
        }
        describeRing(tmesh, collapseArc.targetVertex, "before drags");
        describeRing(tmesh, tmesh.arcs.get(PROBED_ARCS[1]).path.copyVertexPath.get(0),
                "bait fixed end, before drags");
        while (true) {
            int routeAttemptsBefore = collapseArc.rerouter.routeAttemptCount;
            int gatePassesBefore = collapseArc.rerouter.gatePassCount;
            int splitsBefore = collapseArc.rerouter.refinedEdgeSplitCount;
            if (!collapseArc.dragNextArc()) {
                break;
            }
            Platforms.log("[probe] dragged arc %d (banned wedges %d, routeAttempts +%d,"
                    + " gatePasses +%d, splits +%d): previous %s%n",
                    collapseArc.lastDraggedArcId, collapseArc.bannedArrivalWedgeCount,
                    collapseArc.rerouter.routeAttemptCount - routeAttemptsBefore,
                    collapseArc.rerouter.gatePassCount - gatePassesBefore,
                    collapseArc.rerouter.refinedEdgeSplitCount - splitsBefore,
                    collapseArc.lastDraggedPreviousPath);
            describeHops(tmesh, collapseArc.lastDraggedArcId, "after its drag");
        }
        collapseArc.finishCollapse();
        Platforms.log("[probe] touched patches after finish:%s%n",
                touchedPatchText(tmesh, collapseArc));
        describeRing(tmesh, collapseArc.movedVertex, "after finish");
        describeRing(tmesh, collapseArc.targetVertex, "after finish");
        for (int arcId : PROBED_ARCS) {
            describeHops(tmesh, arcId, "after finish");
        }
        for (int patchId : PROBED_PATCHES) {
            describeFlood(tmesh, patchId);
        }
        for (int arcId : PROBED_ARCS) {
            describeTornHop(tmesh, arcId, collapseArc);
        }
        ArrangementDiagnosticException tear = tmesh.flankTearFailure("probe");
        Platforms.log("[probe] tear: %s%n", tear == null ? "none" : tear.getMessage());
    }

    /**
     * Prints every probed arc or patch whose signature changed under the operator
     * just applied, keeping the replay log to the moments that matter.
     *
     * @param tmesh T-mesh being probed
     * @param op    operator ordinal just applied, for the caption
     */
    private void reportProbedChanges(ArcNetwork tmesh, int op) {
        for (int index = 0; index < PROBED_ARCS.length; index++) {
            String signature = arcSignature(tmesh, PROBED_ARCS[index]);
            if (!signature.equals(arcSignatures[index])) {
                arcSignatures[index] = signature;
                Platforms.log("[probe] op %d arc %d: %s%n", op, PROBED_ARCS[index], signature);
            }
        }
        for (int index = 0; index < PROBED_PATCHES.length; index++) {
            String signature = patchSignature(tmesh, PROBED_PATCHES[index]);
            if (!signature.equals(patchSignatures[index])) {
                patchSignatures[index] = signature;
                Platforms.log("[probe] op %d patch %d: %s%n", op, PROBED_PATCHES[index],
                        signature);
            }
        }
    }

    /**
     * One probed arc's watch signature: liveness, raw and resolved flanks, path,
     * and the resolved cover labels beside every hop.
     *
     * @param tmesh T-mesh being probed
     * @param arcId arc to sign
     * @return a line that changes exactly when the arc's tear-relevant state does
     */
    private String arcSignature(ArcNetwork tmesh, int arcId) {
        if (arcId >= tmesh.arcs.size()) {
            return "absent";
        }
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        if (!arc.alive) {
            return "dead";
        }
        return "flanks " + arc.leftPatchId + "|" + arc.rightPatchId + " resolved "
                + tmesh.topology.resolvePatch(arc.leftPatchId) + "|"
                + tmesh.topology.resolvePatch(arc.rightPatchId) + " path "
                + arc.path.copyVertexPath + " hops" + hopText(tmesh, arcId);
    }

    /**
     * One probed patch's watch signature: its alias target and liveness.
     *
     * @param tmesh   T-mesh being probed
     * @param patchId patch to sign
     * @return a line that changes exactly when the patch's identity does
     */
    private String patchSignature(ArcNetwork tmesh, int patchId) {
        if (patchId >= tmesh.patches.size()) {
            return "absent";
        }
        int resolved = tmesh.topology.resolvePatch(patchId);
        EmbeddedPatch patch = tmesh.patches.get(resolved);
        return "resolved " + resolved + (patch.alive ? " alive" : " retired");
    }

    /**
     * The collapse's touched-patch union with each entry's current alias
     * resolution, the set the finishing relabel repaints.
     *
     * @param tmesh       T-mesh being probed
     * @param collapseArc operator holding the touched union
     * @return text listing raw ids and their resolutions
     */
    private String touchedPatchText(ArcNetwork tmesh, ZeroArcCollapseOperator collapseArc) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < collapseArc.touchedPatchCount; index++) {
            int patchId = collapseArc.touchedPatches[index];
            text.append(" ").append(patchId).append("->")
                    .append(tmesh.topology.resolvePatch(patchId));
        }
        return text.toString();
    }

    /**
     * Prints a probed patch's current cover flood as its face ids, or why it cannot
     * flood.
     *
     * @param tmesh   T-mesh being probed
     * @param patchId patch to flood
     */
    private void describeFlood(ArcNetwork tmesh, int patchId) {
        int resolved = tmesh.topology.resolvePatch(patchId);
        if (resolved != patchId || !tmesh.patches.get(resolved).alive
                || !tmesh.corridor.hasSeedableBoundary(resolved)) {
            Platforms.log("[probe] patch %d flood: unavailable (resolved %d, alive %s)%n",
                    patchId, resolved, tmesh.patches.get(resolved).alive);
            return;
        }
        IntIdList faces = tmesh.corridor.patchFaces(resolved);
        StringBuilder text = new StringBuilder();
        for (int cursor = 0; cursor < faces.size(); cursor++) {
            text.append(" ").append(faces.get(cursor));
        }
        Platforms.log("[probe] patch %d flood (%d faces):%s%n", patchId, faces.size(), text);
    }

    /**
     * Finds an arc's first hop whose resolved cover labels disagree with its
     * resolved flanks and runs forensics on both flanking faces: raw label,
     * membership in the finished collapse's touched union, and every alive patch
     * whose flood holds the face.
     *
     * @param tmesh       T-mesh being probed
     * @param arcId       arc to scan
     * @param collapseArc operator holding the finished collapse's touched union
     */
    private void describeTornHop(ArcNetwork tmesh, int arcId,
            ZeroArcCollapseOperator collapseArc) {
        if (arcId >= tmesh.arcs.size() || !tmesh.arcs.get(arcId).alive) {
            return;
        }
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        int left = tmesh.topology.resolvePatch(arc.leftPatchId);
        int right = tmesh.topology.resolvePatch(arc.rightPatchId);
        HalfEdgeMesh copy = tmesh.topology.copy;
        List<Integer> path = arc.path.copyVertexPath;
        for (int hop = 0; hop < path.size() - 1; hop++) {
            int edgeId = tmesh.topology.edgeBetween(path.get(hop), path.get(hop + 1));
            int halfEdge = copy.edgeHalfEdge(edgeId);
            if (copy.halfEdgeVertex(halfEdge) != path.get(hop)) {
                halfEdge = copy.halfEdgeTwin(halfEdge);
            }
            int[] faces = { copy.halfEdgeFace(halfEdge),
                    copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge)) };
            int[] expected = { left, right };
            for (int side = 0; side < faces.length; side++) {
                int faceId = faces[side];
                int label = tmesh.topology.patchLabelOf(faceId);
                int resolved = tmesh.topology.resolvePatch(label);
                if (resolved == expected[side]) {
                    continue;
                }
                Platforms.log("[probe] arc %d torn at hop %d: face %d labeled %d"
                        + " (resolved %d) but this side's flank is %d, expected patch touched"
                        + " by the collapse: %s, floods holding the face:%s%n", arcId, hop,
                        faceId, label, resolved, expected[side],
                        touchedHoldsLabel(tmesh, collapseArc, expected[side]),
                        floodsHoldingFace(tmesh, faceId));
            }
        }
    }

    /**
     * Whether the finished collapse's touched union resolves onto the given patch,
     * which decides whether the finishing relabel repainted its cover.
     *
     * @param tmesh       T-mesh being probed
     * @param collapseArc operator holding the touched union
     * @param resolved    resolved patch id to look for
     * @return true when some touched entry resolves to it
     */
    private boolean touchedHoldsLabel(ArcNetwork tmesh, ZeroArcCollapseOperator collapseArc,
            int resolved) {
        for (int index = 0; index < collapseArc.touchedPatchCount; index++) {
            if (tmesh.topology.resolvePatch(collapseArc.touchedPatches[index]) == resolved) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every alive, unaliased, seedable patch whose cover flood holds a face — the
     * face's actual owners, independent of the possibly stale label painted on it.
     *
     * @param tmesh  T-mesh being probed
     * @param faceId copy face to locate
     * @return text listing the owning patch ids, or {@code none}
     */
    private String floodsHoldingFace(ArcNetwork tmesh, int faceId) {
        StringBuilder owners = new StringBuilder();
        for (int patchId = 0; patchId < tmesh.patches.size(); patchId++) {
            if (tmesh.topology.resolvePatch(patchId) != patchId
                    || !tmesh.patches.get(patchId).alive
                    || !tmesh.corridor.hasSeedableBoundary(patchId)) {
                continue;
            }
            IntIdList faces = tmesh.corridor.patchFaces(patchId);
            for (int cursor = 0; cursor < faces.size(); cursor++) {
                if (faces.get(cursor) == faceId) {
                    owners.append(" ").append(patchId);
                    break;
                }
            }
        }
        return owners.length() == 0 ? " none" : owners.toString();
    }

    /**
     * Prints an arc's path with the resolved cover labels flanking every hop, the
     * direct evidence of which cells the route ran between.
     *
     * @param tmesh  T-mesh being probed
     * @param arcId  arc whose hops are labelled
     * @param moment caption for when the hops were read
     */
    private void describeHops(ArcNetwork tmesh, int arcId, String moment) {
        if (arcId >= tmesh.arcs.size() || !tmesh.arcs.get(arcId).alive) {
            Platforms.log("[probe] arc %d %s: dead or absent%n", arcId, moment);
            return;
        }
        Platforms.log("[probe] arc %d hops %s:%s%n", arcId, moment, hopText(tmesh, arcId));
    }

    /**
     * An arc's hops with the resolved cover labels flanking each, as reusable text.
     *
     * @param tmesh T-mesh being probed
     * @param arcId arc whose hops are labelled
     * @return one {@code from-(left|right)-to} token per hop
     */
    private String hopText(ArcNetwork tmesh, int arcId) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        List<Integer> path = tmesh.arcs.get(arcId).path.copyVertexPath;
        StringBuilder hops = new StringBuilder();
        for (int hop = 0; hop < path.size() - 1; hop++) {
            int edgeId = tmesh.topology.edgeBetween(path.get(hop), path.get(hop + 1));
            int halfEdge = copy.edgeHalfEdge(edgeId);
            if (copy.halfEdgeVertex(halfEdge) != path.get(hop)) {
                halfEdge = copy.halfEdgeTwin(halfEdge);
            }
            int left = tmesh.topology.resolvePatch(
                    tmesh.topology.patchLabelOf(copy.halfEdgeFace(halfEdge)));
            int right = tmesh.topology.resolvePatch(tmesh.topology.patchLabelOf(
                    copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge))));
            hops.append(" ").append(path.get(hop)).append("-(").append(left).append("|")
                    .append(right).append(")-").append(path.get(hop + 1));
        }
        return hops.toString();
    }

    /**
     * Prints the claimed spokes and face labels around a vertex in cyclic half-edge
     * order, which is the slot structure the dragged arcs arrive into.
     *
     * @param tmesh    T-mesh being probed
     * @param vertexId copy vertex whose ring is walked
     * @param moment   caption for when the ring was read
     */
    private void describeRing(ArcNetwork tmesh, int vertexId, String moment) {
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
                    .append(" f").append(faceId).append("[").append(label).append("]");
            halfEdge = copy.halfEdgeTwin(copy.halfEdgeNext(copy.halfEdgeNext(halfEdge)));
            spokes++;
        } while (halfEdge != start && spokes < copy.vertexEdgeCount(vertexId) + 1);
        Platforms.log("[probe] ring of vertex %d %s (%d spokes):%s%n", vertexId, moment,
                spokes, ring);
    }
}
