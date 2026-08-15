package unit.mesh;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.quadlayout.embedding.fixtures.StackedZeroRowTorusFixture;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.records.PatchCorridor;

/** Scratch: steps the contraction operator by operator and prints when the cover labels drift. */
class DriftLocalizeScratchTest {

    private static final int WATCHED_ARC = 23;

    private static final int WATCHED_FACE = 211;

    /**
     * Dumps the watched arc's layout neighbourhood and the watched face's surroundings.
     *
     * @param tmesh T-mesh being contracted
     * @param arcId arc about to be, or just, collapsed
     * @param when  "before" or "after"
     */
    private static void describe(EmbeddedTMesh tmesh, int arcId, String when) {
        EmbeddedMeshTopology topology = tmesh.topology;
        HalfEdgeMesh copy = topology.copy;
        System.out.printf("[watch %s] arc %d start=%d end=%d left=%d right=%d path=%s%n",
                when, arcId, tmesh.arcs.get(arcId).startNodeId, tmesh.arcs.get(arcId).endNodeId,
                tmesh.arcs.get(arcId).leftPatchId, tmesh.arcs.get(arcId).rightPatchId,
                tmesh.arcs.get(arcId).path.copyVertexPath);
        // The watched ids were captured from one historical run; a routing change can leave the
        // face unlabeled or unminted, which is not what this scratch is watching for.
        if (WATCHED_FACE >= topology.patchByCopyFace.length
                || WATCHED_FACE >= copy.faceCount()) {
            System.out.printf("[watch %s] face %d not minted or not labeled (faces %d, labels"
                    + " %d)%n", when, WATCHED_FACE, copy.faceCount(),
                    topology.patchByCopyFace.length);
            return;
        }
        System.out.printf("[watch %s] face %d label=%d vertices=%d,%d,%d%n", when, WATCHED_FACE,
                topology.patchLabelOf(WATCHED_FACE), copy.faceVertexAt(WATCHED_FACE, 0),
                copy.faceVertexAt(WATCHED_FACE, 1), copy.faceVertexAt(WATCHED_FACE, 2));
        for (int corner = 0; corner < 3; corner++) {
            int edgeId = copy.faceEdgeAt(WATCHED_FACE, corner);
            int halfEdge = copy.edgeHalfEdge(edgeId);
            int neighbour = copy.halfEdgeFace(halfEdge) == WATCHED_FACE
                    ? copy.halfEdgeFace(copy.halfEdgeTwin(halfEdge))
                    : copy.halfEdgeFace(halfEdge);
            System.out.printf("[watch %s]   edge %d owner=%d neighbour=%d label=%d%n", when, edgeId,
                    topology.ownerArcByCopyEdge[edgeId], neighbour,
                    neighbour < 0 ? -1 : topology.patchLabelOf(neighbour));
        }
    }

    @Test
    void localize() {
        StackedZeroRowTorusFixture fixture = new StackedZeroRowTorusFixture();
        EmbeddedTMesh tmesh = fixture.tmesh;
        tmesh.labelPatchCovers();
        report(tmesh, "start");
        while (true) {
            boolean applied = true;
            while (applied) {
                int simple = tmesh.collapsePatch.nextSimpleZeroPatch();
                if (simple != EmbeddedTMesh.NONE) {
                    tmesh.collapsePatch.collapse(simple);
                    report(tmesh, "patchCollapse " + simple);
                    continue;
                }
                int arc = tmesh.collapseArc.mostContendedArc();
                if (arc != EmbeddedTMesh.NONE) {
                    java.util.Map<Integer, String> before = new java.util.HashMap<>();
                    if (arc == WATCHED_ARC) {
                        describe(tmesh, arc, "before");
                        for (int id = 0; id < tmesh.arcs.size(); id++) {
                            if (tmesh.arcs.get(id).alive) {
                                before.put(id, tmesh.arcs.get(id).path.copyEdgePath.toString()
                                        + tmesh.arcs.get(id).path.copyVertexPath);
                            }
                        }
                    }
                    tmesh.collapseArc.collapse(arc);
                    if (arc == WATCHED_ARC) {
                        describe(tmesh, arc, "after");
                        for (int id = 0; id < tmesh.arcs.size(); id++) {
                            if (!tmesh.arcs.get(id).alive) {
                                continue;
                            }
                            String now = tmesh.arcs.get(id).path.copyEdgePath.toString()
                                    + tmesh.arcs.get(id).path.copyVertexPath;
                            if (!now.equals(before.get(id))) {
                                System.out.printf("[moved] arc %d left=%d right=%d %s -> %s%n", id,
                                        tmesh.arcs.get(id).leftPatchId,
                                        tmesh.arcs.get(id).rightPatchId, before.get(id), now);
                            }
                        }
                    }
                    report(tmesh, "arcCollapse " + arc);
                    continue;
                }
                applied = false;
            }
            int nonSimple = tmesh.splitPatch.nextNonSimpleZeroPatch();
            if (nonSimple == EmbeddedTMesh.NONE) {
                break;
            }
            tmesh.splitPatch.split(nonSimple);
            report(tmesh, "patchSplit " + nonSimple);
        }
        tmesh.conform();
        report(tmesh, "conform");
    }

    /**
     * Prints every face whose maintained label disagrees with a fresh flood.
     *
     * @param tmesh T-mesh to check
     * @param step  description of the operator just applied
     */
    private static void report(EmbeddedTMesh tmesh, String step) {
        EmbeddedMeshTopology topology = tmesh.topology;
        HalfEdgeMesh copy = topology.copy;
        PatchCorridor corridor = new PatchCorridor(tmesh);
        StringBuilder detail = new StringBuilder();
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            IntIdList trueFaces = corridor.patchFaces(patch.patchId);
            for (int index = 0; index < trueFaces.size(); index++) {
                int faceId = trueFaces.get(index);
                int label = topology.resolvePatch(topology.patchLabelOf(faceId));
                if (label != patch.patchId) {
                    detail.append(" face ").append(faceId).append(" in patch ")
                            .append(patch.patchId).append(" labeled ").append(label);
                }
            }
        }
        int orphaned = 0;
        for (int index = 0; index < copy.faceCount(); index++) {
            int label = topology.resolvePatch(topology.patchLabelOf(copy.faceIdAt(index)));
            if (label == EmbeddedMeshTopology.UNCLAIMED || !tmesh.patches.get(label).alive) {
                orphaned++;
            }
        }
        System.out.printf("[step] %-22s orphaned=%d drift=%s%n", step, orphaned,
                detail.length() == 0 ? "none" : detail.toString());
    }
}
