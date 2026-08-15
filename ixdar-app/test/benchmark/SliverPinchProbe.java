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
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;

/**
 * Dumps the structure around botijo's sliver-cover tear at arc collapse 3: the patches and arcs at
 * the pinch, the cyclic ring around the surviving vertex before and after each drag, and the
 * per-hop covers of every dragged arc. Pick the mesh with {@code -Dbenchmark.off}.
 */
public final class SliverPinchProbe {

    private static final String OFF_PROPERTY = "benchmark.off";
    private static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/botijo_in_tri.off";

    /** Operators replayed before the failing collapse, from the B-rewind count. */
    private static final int REPLAYED_OPS = 2;

    /** Patches named in the tear diagnostic. */
    private static final int[] PROBED_PATCHES = { 0, 56, 109 };

    /** Arcs named in the tear diagnostic and its flood boundaries. */
    private static final int[] PROBED_ARCS = { 1, 2, 3, 148, 149, 150, 277, 278, 281 };

    /**
     * Replays to just before the failing collapse, dumps the pinch structure, then steps the
     * collapse drag by drag with ring and cover dumps between.
     *
     * @throws IOException when the mesh file cannot be read
     */
    @Test
    public void probeSliverPinch() throws IOException {
        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        HalfEdgeMesh mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, QuadLayoutEngine.DEFAULT_ALPHA_RADIANS);
        EmbeddedTMesh tmesh = engine.buildTMesh();
        tmesh.labelPatchCovers();
        for (int op = 0; op < REPLAYED_OPS; op++) {
            tmesh.contractStep();
        }

        ZeroArcCollapseOperator collapseArc = tmesh.collapseArc;
        int collapsingArcId = collapseArc.mostContendedArc();
        collapseArc.beginCollapse(collapsingArcId);
        System.out.printf("[probe] collapsing arc %d: moved node %d (vertex %d) -> surviving"
                + " node %d (vertex %d), channel %s, fan %s%n", collapsingArcId,
                collapseArc.movedNodeId, collapseArc.movedVertex, collapseArc.survivingNodeId,
                collapseArc.targetVertex, collapseArc.channel, collapseArc.fan);
        StringBuilder touched = new StringBuilder();
        for (int index = 0; index < collapseArc.touchedPatchCount; index++) {
            touched.append(index == 0 ? "" : ", ").append(collapseArc.touchedPatches[index]);
        }
        System.out.printf("[probe] touched union [%s]%n", touched);

        for (int patchId : PROBED_PATCHES) {
            describePatch(tmesh, patchId, collapseArc.targetVertex);
        }
        for (int arcId : PROBED_ARCS) {
            describeArc(tmesh, arcId, collapseArc.targetVertex);
        }
        describeRing(tmesh, collapseArc.targetVertex, "before drags");

        while (collapseArc.dragNextArc()) {
            int draggedArcId = collapseArc.lastDraggedArcId;
            System.out.printf("[probe] dragged arc %d: previous %s%n", draggedArcId,
                    collapseArc.lastDraggedPreviousPath);
            describeHops(tmesh, draggedArcId);
            describeRing(tmesh, collapseArc.targetVertex, "after drag of arc " + draggedArcId);
        }
        collapseArc.finishCollapse();
        for (int patchId : PROBED_PATCHES) {
            describePatch(tmesh, patchId, collapseArc.targetVertex);
        }
        describeRing(tmesh, collapseArc.targetVertex, "after finish");
        ArrangementDiagnosticException tear = tmesh.flankTearFailure("probe");
        System.out.printf("[probe] tear: %s%n", tear == null ? "none" : tear.getMessage());
    }

    /**
     * Prints one patch's sides, per-side quantized lengths, cover size, and whether its cover
     * touches the surviving vertex.
     *
     * @param tmesh        T-mesh being probed
     * @param patchId      patch to describe
     * @param targetVertex the surviving node's copy vertex
     */
    private void describePatch(EmbeddedTMesh tmesh, int patchId, int targetVertex) {
        EmbeddedPatch patch = tmesh.patches.get(tmesh.topology.resolvePatch(patchId));
        StringBuilder sides = new StringBuilder();
        for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
            sides.append(side == 0 ? "" : " | ").append(patch.sideArcIds.get(side))
                    .append("=").append(tmesh.sideQuantizedLength(patch.patchId, side));
        }
        IntIdList cover = tmesh.splitPatch.corridor.hasSeedableBoundary(patch.patchId)
                ? tmesh.splitPatch.corridor.patchFaces(patch.patchId)
                : new IntIdList(0);
        boolean coverAtTarget = false;
        HalfEdgeMesh copy = tmesh.topology.copy;
        for (int index = 0; index < cover.size() && !coverAtTarget; index++) {
            int faceId = cover.get(index);
            for (int corner = 0; corner < copy.faceHalfEdgeCount(faceId); corner++) {
                coverAtTarget |= copy.faceVertexAt(faceId, corner) == targetVertex;
            }
        }
        System.out.printf("[probe] patch %d (resolved %d, alive %b): sides %s"
                + " | cover %d faces, touchesTarget %b%n", patchId, patch.patchId, patch.alive,
                sides, cover.size(), coverAtTarget);
    }

    /**
     * Prints one arc's endpoints, quantized length, flanks, path extent and whether the path
     * reaches the surviving vertex.
     *
     * @param tmesh        T-mesh being probed
     * @param arcId        arc to describe
     * @param targetVertex the surviving node's copy vertex
     */
    private void describeArc(EmbeddedTMesh tmesh, int arcId, int targetVertex) {
        EmbeddedArc arc = tmesh.arcs.get(arcId);
        List<Integer> path = arc.path.copyVertexPath;
        System.out.printf("[probe] arc %d (alive %b): q=%d nodes %d->%d flanks %d|%d"
                + " path %d verts [%d..%d] reachesTarget %b%n", arcId, arc.alive,
                arc.quantizedLength, arc.startNodeId, arc.endNodeId,
                tmesh.topology.resolvePatch(arc.leftPatchId),
                tmesh.topology.resolvePatch(arc.rightPatchId), path.size(), path.get(0),
                path.get(path.size() - 1), path.contains(targetVertex));
    }

    /**
     * Prints the claimed spokes and face labels around a vertex in cyclic half-edge order, which
     * is the slot structure a dragged arc must arrive into.
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
        System.out.printf("[probe] ring of vertex %d %s (%d spokes):%s%n", vertexId, moment,
                spokes, ring);
    }

    /**
     * Prints an arc's path with the resolved cover labels flanking every hop, the direct
     * evidence of which cells the route ran between.
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
        System.out.printf("[probe] arc %d hops:%s%n", arcId, hops);
    }
}
