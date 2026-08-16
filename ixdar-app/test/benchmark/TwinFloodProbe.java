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
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.platform.Platforms;

/**
 * Localizes botijo's operator-16 twin-flood tear: replays the contraction one
 * operator at a time, reporting after each when patches 6, 7 and 8 first flood
 * coinciding cells and when any probed arc's raw flank ids diverge from their
 * resolved patches. Pick the mesh with {@code -Dbenchmark.off}.
 */
public final class TwinFloodProbe {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/botijo_in_tri.off";

    /** Operators replayed before the failing collapse, from the B-rewind count. */
    private static final int REPLAYED_OPS = 15;

    /** Patches named in the tear diagnostic. */
    private static final int[] PROBED_PATCHES = { 6, 7, 8 };

    /** Arcs named in the tear diagnostic and its flood boundaries. */
    private static final int[] PROBED_ARCS = { 17, 18, 20, 22, 23, 25 };

    /**
     * Replays the contraction operator by operator with flood-identity and
     * flank-staleness checks between, then steps the failing collapse drag by drag.
     *
     * @throws IOException when the mesh file cannot be read
     */
    @Test
    public void probeTwinFlood() throws IOException {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);
        EmbeddedTMesh tmesh = engine.buildTMesh();
        tmesh.labelPatchCovers();
        for (int op = 1; op <= REPLAYED_OPS; op++) {
            String applied = tmesh.contractStep();
            Platforms.log("[probe] op %d: %s%n", op, applied);
            reportFloodIdentity(tmesh, op);
            reportStaleFlanks(tmesh, op);
        }

        ZeroArcCollapseOperator collapseArc = tmesh.collapseArc;
        int collapsingArcId = collapseArc.mostContendedArc();
        collapseArc.beginCollapse(collapsingArcId);
        Platforms.log("[probe] failing collapse of arc %d: moved node %d (vertex %d) ->"
                + " surviving node %d (vertex %d), channel %s, fan %s%n", collapsingArcId,
                collapseArc.movedNodeId, collapseArc.movedVertex, collapseArc.survivingNodeId,
                collapseArc.targetVertex, collapseArc.channel, collapseArc.fan);
        describeRing(tmesh, collapseArc.targetVertex, "before drags");
        while (collapseArc.dragNextArc()) {
            Platforms.log("[probe] dragged arc %d: previous %s%n", collapseArc.lastDraggedArcId,
                    collapseArc.lastDraggedPreviousPath);
            describeHops(tmesh, collapseArc.lastDraggedArcId);
            describeRing(tmesh, collapseArc.targetVertex,
                    "after drag of arc " + collapseArc.lastDraggedArcId);
        }
        collapseArc.finishCollapse();
        describeRing(tmesh, collapseArc.targetVertex, "after finish");
        reportFloodIdentity(tmesh, REPLAYED_OPS + 1);
        ArrangementDiagnosticException tear = tmesh.flankTearFailure("probe");
        Platforms.log("[probe] tear: %s%n", tear == null ? "none" : tear.getMessage());
    }

    /**
     * Prints each probed patch's flood signature after an operator, flagging any
     * two alive probed patches whose floods coincide.
     *
     * @param tmesh T-mesh being probed
     * @param op    operator ordinal just applied, for the caption
     */
    private void reportFloodIdentity(EmbeddedTMesh tmesh, int op) {
        Set<Integer>[] floods = floodSets(tmesh);
        for (int index = 0; index < PROBED_PATCHES.length; index++) {
            for (int other = index + 1; other < PROBED_PATCHES.length; other++) {
                if (floods[index] != null && floods[index].equals(floods[other])) {
                    Platforms.log("[probe]   op %d TWIN FLOOD: patches %d and %d both flood"
                            + " %d faces%n", op, PROBED_PATCHES[index], PROBED_PATCHES[other],
                            floods[index].size());
                }
            }
        }
    }

    /**
     * The probed patches' current cover floods as sets, null for a patch that is
     * retired, aliased away, or unseedable.
     *
     * @param tmesh T-mesh being probed
     * @return one flood set per entry of {@link #PROBED_PATCHES}
     */
    @SuppressWarnings("unchecked")
    private Set<Integer>[] floodSets(EmbeddedTMesh tmesh) {
        Set<Integer>[] floods = new Set[PROBED_PATCHES.length];
        for (int index = 0; index < PROBED_PATCHES.length; index++) {
            int resolved = tmesh.topology.resolvePatch(PROBED_PATCHES[index]);
            if (resolved != PROBED_PATCHES[index] || !tmesh.patches.get(resolved).alive
                    || !tmesh.splitPatch.corridor.hasSeedableBoundary(resolved)) {
                continue;
            }
            IntIdList faces = tmesh.splitPatch.corridor.patchFaces(resolved);
            floods[index] = new HashSet<>();
            for (int cursor = 0; cursor < faces.size(); cursor++) {
                floods[index].add(faces.get(cursor));
            }
        }
        return floods;
    }

    /**
     * Prints every probed arc whose raw flank ids no longer equal their resolved
     * patches, the staleness operator (3)'s aliasing leaves behind.
     *
     * @param tmesh T-mesh being probed
     * @param op    operator ordinal just applied, for the caption
     */
    private void reportStaleFlanks(EmbeddedTMesh tmesh, int op) {
        for (int arcId : PROBED_ARCS) {
            EmbeddedArc arc = tmesh.arcs.get(arcId);
            if (!arc.alive) {
                continue;
            }
            int resolvedLeft = tmesh.topology.resolvePatch(arc.leftPatchId);
            int resolvedRight = tmesh.topology.resolvePatch(arc.rightPatchId);
            if (resolvedLeft != arc.leftPatchId || resolvedRight != arc.rightPatchId) {
                Platforms.log("[probe]   op %d STALE FLANK: arc %d raw %d|%d resolved"
                        + " %d|%d%n", op, arcId, arc.leftPatchId, arc.rightPatchId,
                        resolvedLeft, resolvedRight);
            }
        }
        for (int patchId : PROBED_PATCHES) {
            int resolved = tmesh.topology.resolvePatch(patchId);
            EmbeddedPatch patch = tmesh.patches.get(resolved);
            if (resolved != patchId) {
                Platforms.log("[probe]   op %d patch %d aliased into %d%n", op, patchId,
                        resolved);
            } else if (!patch.alive) {
                Platforms.log("[probe]   op %d patch %d retired%n", op, patchId);
            }
        }
    }

    /**
     * Prints the claimed spokes and face labels around a vertex in cyclic half-edge
     * order, which is the slot structure the dragged arcs arrive into.
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
                    .append(" f").append(faceId).append("[").append(label).append("]");
            halfEdge = copy.halfEdgeTwin(copy.halfEdgeNext(copy.halfEdgeNext(halfEdge)));
            spokes++;
        } while (halfEdge != start && spokes < copy.vertexEdgeCount(vertexId) + 1);
        Platforms.log("[probe] ring of vertex %d %s (%d spokes):%s%n", vertexId, moment,
                spokes, ring);
    }

    /**
     * Prints an arc's path with the resolved cover labels flanking every hop, the
     * direct evidence of which cells the route ran between.
     *
     * @param tmesh T-mesh being probed
     * @param arcId arc whose hops are labelled
     */
    private void describeHops(EmbeddedTMesh tmesh, int arcId) {
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
        Platforms.log("[probe] arc %d hops:%s%n", arcId, hops);
    }
}
